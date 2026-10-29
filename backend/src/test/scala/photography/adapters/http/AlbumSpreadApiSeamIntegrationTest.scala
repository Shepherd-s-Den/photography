package photography.adapters.http

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.jawn.parse
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.`Content-Type`
import org.http4s.{HttpApp, MediaType, Method, Request, Status, Uri}
import photography.domain.album.{AlbumId, ChapterId}
import photography.domain.layout.{SpreadId, SpreadTemplateId}
import photography.usecases.SpreadManager
import photography.usecases.SpreadManager.*

import java.nio.charset.StandardCharsets
import java.util.UUID

final class AlbumSpreadApiSeamIntegrationTest extends CatsEffectSuite:
  private val album    = UUID.fromString("00000000-0000-0000-0000-000000000001")
  private val chapter  = UUID.fromString("00000000-0000-0000-0000-000000000002")
  private val spread   = UUID.fromString("00000000-0000-0000-0000-000000000003")
  private val layout   = UUID.fromString("00000000-0000-0000-0000-000000000113")
  private val albumUrl = s"/api/albums/$album"

  test("should create a spread after the target and answer with the inserted identity"):
    for
      calls <- Ref.of[IO, Vector[String]](Vector.empty)
      server = buildApi(calls, add = AddResult.Added(SpreadId(spread)))
      result <- respond(server, post(s"$albumUrl/chapters/$chapter/spreads", Json.obj("afterSpreadId" -> Json.fromString(spread.toString))))
      seen   <- calls.get
    yield
      assertEquals(result, Status.Created -> Some(json(s"""{"id":"$spread"}""")))
      assertEquals(seen, Vector(s"add:$spread"))

  test("should explain rejected or unavailable additions and malformed targets"):
    Vector(
      (AddResult.Rejected(Conflict.AtCapacity), Status.BadRequest, Some(json("""{"message":"An album can contain at most 65 interior spreads"}"""))),
      (
        AddResult.Rejected(Conflict.AddTarget),
        Status.BadRequest,
        Some(json("""{"message":"Add a spread after a chapter opening or photo spread"}"""))
      ),
      (AddResult.AlbumNotFound, Status.NotFound, Some(json("""{"code":"album_not_found"}"""))),
      (AddResult.ChapterNotFound, Status.NotFound, Some(json("""{"code":"chapter_not_found"}"""))),
      (AddResult.SpreadNotFound, Status.NotFound, Some(json("""{"code":"spread_not_found"}"""))),
      (AddResult.Failed, Status.InternalServerError, Some(json("""{"code":"service_failure"}""")))
    ).traverse_ { case (outcome, status, expected) =>
      for
        calls <- Ref.of[IO, Vector[String]](Vector.empty)
        server = buildApi(calls, add = outcome)
        result <- respond(server, post(s"$albumUrl/chapters/$chapter/spreads", Json.obj("afterSpreadId" -> Json.fromString(spread.toString))))
      yield assertEquals(result, status -> expected)
    } >> {
      for
        calls <- Ref.of[IO, Vector[String]](Vector.empty)
        server = buildApi(calls)
        invalid <- respond(server, post(s"$albumUrl/chapters/$chapter/spreads", Json.obj("afterSpreadId" -> Json.fromString("not-a-uuid"))))
        notJson <- respond(
          server,
          Request[IO](
            Method.POST,
            Uri.unsafeFromString(s"$albumUrl/chapters/$chapter/spreads")
          ).withBodyStream(rawJson).putHeaders(`Content-Type`(MediaType.application.json))
        )
        seen <- calls.get
      yield
        assertEquals(invalid, Status.BadRequest -> Some(json("""{"message":"Spread IDs must be canonical UUIDs"}""")))
        assertEquals(notJson, Status.BadRequest -> Some(json("""{"message":"Supply a valid JSON spread request"}""")))
        assertEquals(seen, Vector.empty[String])
    }

  test("should change templates with explicit confirmation and report occupied and invalid targets"):
    val body = Json.obj("templateId" -> Json.fromString(layout.toString))
    for
      calls <- Ref.of[IO, Vector[String]](Vector.empty)
      server = buildApi(calls, change = ChangeTemplateResult.Changed(SpreadId(spread), SpreadTemplateId(layout)))
      changed <- respond(server, put(s"$albumUrl/spreads/$spread/template?confirmPlacements=true", body))
      warned  <- respond(buildApi(calls, change = ChangeTemplateResult.Occupied(2)), put(s"$albumUrl/spreads/$spread/template", body))
      seen    <- calls.get
    yield
      assertEquals(changed, Status.Ok      -> Some(json(s"""{"id":"$spread","templateId":"$layout"}""")))
      assertEquals(warned, Status.Conflict -> Some(json("""{"code":"spread_occupied","placements":2}""")))
      assertEquals(seen, Vector(s"change:$layout:true", s"change:$layout:false"))

  test("should explain rejected template changes, unknown identities and invalid template requests"):
    val body = Json.obj("templateId" -> Json.fromString(layout.toString))
    Vector(
      (
        ChangeTemplateResult.Rejected(Conflict.ContextMismatch),
        Status.BadRequest,
        Some(json("""{"message":"Choose a layout for this spread's context"}"""))
      ),
      (ChangeTemplateResult.Rejected(Conflict.UnknownTemplate), Status.BadRequest, Some(json("""{"message":"Choose a layout from the catalog"}"""))),
      (ChangeTemplateResult.SpreadNotFound, Status.NotFound, Some(json("""{"code":"spread_not_found"}"""))),
      (ChangeTemplateResult.AlbumNotFound, Status.NotFound, Some(json("""{"code":"album_not_found"}"""))),
      (ChangeTemplateResult.Failed, Status.InternalServerError, Some(json("""{"code":"service_failure"}""")))
    ).traverse_ { case (outcome, status, expected) =>
      respond(buildApi(Ref.unsafe[IO, Vector[String]](Vector.empty), change = outcome), put(s"$albumUrl/spreads/$spread/template", body))
        .map(result => assertEquals(result, status -> expected))
    } >> respond(
      buildApi(Ref.unsafe[IO, Vector[String]](Vector.empty)),
      put(s"$albumUrl/spreads/$spread/template", Json.obj("templateId" -> Json.fromString("layout")))
    )
      .map(result => assertEquals(result, Status.BadRequest -> Some(json("""{"message":"Template IDs must be canonical UUIDs"}"""))))

  test("should move photo spreads in the requested direction and explain rejected moves"):
    for
      calls <- Ref.of[IO, Vector[String]](Vector.empty)
      moved <- respond(buildApi(calls, move = MoveResult.Moved(SpreadId(spread))), post(s"$albumUrl/spreads/$spread/move", direction("earlier")))
      seen  <- calls.get
      bad   <- respond(buildApi(calls), post(s"$albumUrl/spreads/$spread/move", direction("sideways")))
      edge  <- respond(buildApi(calls, move = MoveResult.Rejected(Conflict.NoNeighbour)), post(s"$albumUrl/spreads/$spread/move", direction("later")))
      opening <-
        respond(buildApi(calls, move = MoveResult.Rejected(Conflict.OpeningMove)), post(s"$albumUrl/spreads/$spread/move", direction("later")))
      missing <- respond(buildApi(calls, move = MoveResult.SpreadNotFound), post(s"$albumUrl/spreads/$spread/move", direction("later")))
      unknown <- respond(buildApi(calls, move = MoveResult.AlbumNotFound), post(s"$albumUrl/spreads/$spread/move", direction("later")))
      failed  <- respond(buildApi(calls, move = MoveResult.Failed), post(s"$albumUrl/spreads/$spread/move", direction("later")))
    yield
      assertEquals(moved, Status.Ok -> Some(json(s"""{"id":"$spread"}""")))
      assertEquals(seen, Vector("move:Earlier"))
      assertEquals(bad, Status.BadRequest             -> Some(json("""{"message":"Choose earlier or later"}""")))
      assertEquals(edge, Status.BadRequest            -> Some(json("""{"message":"Photo spreads can only move within their chapter"}""")))
      assertEquals(opening, Status.BadRequest         -> Some(json("""{"message":"Openings cannot be moved"}""")))
      assertEquals(missing, Status.NotFound           -> Some(json("""{"code":"spread_not_found"}""")))
      assertEquals(unknown, Status.NotFound           -> Some(json("""{"code":"album_not_found"}""")))
      assertEquals(failed, Status.InternalServerError -> Some(json("""{"code":"service_failure"}""")))

  test("should delete spreads with explicit confirmation and explain occupied, opening, and missing targets"):
    for
      calls    <- Ref.of[IO, Vector[String]](Vector.empty)
      deleted  <- respond(buildApi(calls, delete = DeleteResult.Deleted), delete(s"$albumUrl/spreads/$spread?confirmPlacements=true"))
      seen     <- calls.get
      occupied <- respond(buildApi(calls, delete = DeleteResult.Occupied(3)), delete(s"$albumUrl/spreads/$spread"))
      opening  <- respond(buildApi(calls, delete = DeleteResult.Rejected(Conflict.OpeningDelete)), delete(s"$albumUrl/spreads/$spread"))
      missing  <- respond(buildApi(calls, delete = DeleteResult.SpreadNotFound), delete(s"$albumUrl/spreads/$spread"))
      unknown  <- respond(buildApi(calls, delete = DeleteResult.AlbumNotFound), delete(s"$albumUrl/spreads/$spread"))
      failed   <- respond(buildApi(calls, delete = DeleteResult.Failed), delete(s"$albumUrl/spreads/$spread"))
    yield
      assertEquals(deleted, Status.NoContent -> None)
      assertEquals(seen, Vector("delete:true"))
      assertEquals(occupied, Status.Conflict          -> Some(json("""{"code":"spread_occupied","placements":3}""")))
      assertEquals(opening, Status.BadRequest         -> Some(json("""{"message":"Openings cannot be deleted"}""")))
      assertEquals(missing, Status.NotFound           -> Some(json("""{"code":"spread_not_found"}""")))
      assertEquals(unknown, Status.NotFound           -> Some(json("""{"code":"album_not_found"}""")))
      assertEquals(failed, Status.InternalServerError -> Some(json("""{"code":"service_failure"}""")))

  private def rawJson = Stream.emits("{".getBytes(StandardCharsets.UTF_8).toSeq).covary[IO]

  private def direction(value: String) = Json.obj("direction" -> Json.fromString(value))

  private def post(path: String, body: Json) =
    Request[IO](Method.POST, Uri.unsafeFromString(path)).withEntity(body)

  private def put(path: String, body: Json) =
    Request[IO](Method.PUT, Uri.unsafeFromString(path)).withEntity(body)

  private def delete(path: String) = Request[IO](Method.DELETE, Uri.unsafeFromString(path))

  private def json(text: String): Json = parse(text).fold(error => fail(error.message), identity)

  private def respond(app: HttpApp[IO], request: Request[IO]) =
    app.run(request).flatMap: response =>
      response.bodyText.compile.toList.map(chunks => (response.status, parse(chunks.mkString).toOption))

  // scalafix:off DisableSyntax.defaultArgs
  private def buildApi(
      calls: Ref[IO, Vector[String]],
      add: AddResult = AddResult.Failed,
      move: MoveResult = MoveResult.Failed,
      change: ChangeTemplateResult = ChangeTemplateResult.Failed,
      delete: DeleteResult = DeleteResult.Failed
  ): HttpApp[IO] =
    val addOutcome    = add
    val moveOutcome   = move
    val changeOutcome = change
    val deleteOutcome = delete
    val spreads       = new SpreadManager[IO]:
      override def add(album: AlbumId, chapter: ChapterId, after: SpreadId): IO[AddResult] =
        calls.update(_ :+ s"add:${after.value}").as(addOutcome)
      override def move(album: AlbumId, spread: SpreadId, direction: Direction): IO[MoveResult] =
        calls.update(_ :+ s"move:$direction").as(moveOutcome)
      override def changeTemplate(
          album: AlbumId,
          spread: SpreadId,
          template: SpreadTemplateId,
          confirmPlacements: Boolean
      ): IO[ChangeTemplateResult] =
        calls.update(_ :+ s"change:${template.value}:$confirmPlacements").as(changeOutcome)
      override def delete(album: AlbumId, spread: SpreadId, confirmPlacements: Boolean): IO[DeleteResult] =
        calls.update(_ :+ s"delete:$confirmPlacements").as(deleteOutcome)
    AlbumSpreadApi.make(spreads).routes.orNotFound
  // scalafix:on DisableSyntax.defaultArgs
