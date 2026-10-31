package photography.adapters.http

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.jawn.parse
import fs2.Stream
import java.nio.charset.StandardCharsets
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.CirceEntityCodec.*
import photography.domain.album.{AlbumId, ChapterId}
import photography.domain.layout.SpreadId
import photography.usecases.chapter.ChapterComposition
import cats.data.NonEmptyList
import java.util.UUID

final class ChapterCompositionApiSeamIntegrationTest extends CatsEffectSuite:
  private val album   = AlbumId(new UUID(0L, 1L))
  private val chapter = ChapterId(new UUID(0L, 2L))
  private val opening = SpreadId(new UUID(0L, 3L))
  private val path    = s"/api/albums/${album.value}/chapters/${chapter.value}/composition"
  private val body    = Json.obj(
    "expectedSpreadIds" -> Json.arr(Json.fromString(opening.value.toString)),
    "spreadIds"         -> Json.arr(Json.fromString(opening.value.toString))
  )

  test("should replace a composition resource and preserve every rejection and uncertain-write outcome"):
    val cases = Vector(
      (ChapterComposition.Result.Saved, Status.NoContent, none[Json]),
      (
        ChapterComposition.Result.Invalid,
        Status.BadRequest,
        Json.obj("message" -> Json.fromString("Retain the opening and every spread exactly once")).some
      ),
      (ChapterComposition.Result.NotFound, Status.NotFound, Json.obj("code" -> Json.fromString("chapter_not_found")).some),
      (ChapterComposition.Result.Stale, Status.Conflict, Json.obj("code" -> Json.fromString("composition_changed")).some),
      (ChapterComposition.Result.Uncertain, Status.InternalServerError, Json.obj("code" -> Json.fromString("write_uncertain")).some)
    )
    cases.traverse_ { case (result, expected, expectedBody) =>
      val program = new ChapterComposition[IO]:
        override def replace(id: AlbumId, target: ChapterId, previous: NonEmptyList[SpreadId], order: NonEmptyList[SpreadId])
            : IO[ChapterComposition.Result] =
          IO(assertEquals((id, target, previous, order), (album, chapter, NonEmptyList.one(opening), NonEmptyList.one(opening)))).as(result)
      ChapterCompositionApi.make(program).orNotFound.run(Request[IO](Method.PUT, Uri.unsafeFromString(path)).withEntity(body))
        .flatMap(response =>
          response.bodyText.compile.string.map: body =>
            assertEquals(response.status, expected)
            assertEquals(parse(body).toOption, expectedBody)
        )
    }

  test("should reject malformed JSON, empty orders and noncanonical member IDs before mutation"):
    val program = new ChapterComposition[IO]:
      override def replace(
          album: AlbumId,
          chapter: ChapterId,
          previous: NonEmptyList[SpreadId],
          order: NonEmptyList[SpreadId]
      ): IO[ChapterComposition.Result] =
        IO.raiseError[ChapterComposition.Result](new AssertionError("Unexpected write"))
    val routes  = ChapterCompositionApi.make(program).orNotFound
    val invalid = Vector(
      "{}",
      "{",
      """{"expectedSpreadIds":[],"spreadIds":[]}""",
      """{"expectedSpreadIds":["1-1-1-1-1"],"spreadIds":["invalid"]}"""
    )
    invalid.traverse_ { value =>
      routes.run(Request[IO](
        Method.PUT,
        Uri.unsafeFromString(path)
      ).withEntity(value).putHeaders(org.http4s.headers.`Content-Type`(org.http4s.MediaType.application.json)))
        .map(response => assertEquals(response.status, Status.BadRequest))
    } *> routes.run(Request[IO](Method.PUT, Uri.unsafeFromString(path))
      .withBodyStream(Stream.emits("{".getBytes(StandardCharsets.UTF_8)).covary[IO])
      .putHeaders(org.http4s.headers.`Content-Type`(org.http4s.MediaType.application.json))).flatMap: response =>
      response.as[Json].map: body =>
        assertEquals(response.status, Status.BadRequest)
        assertEquals(body, Json.obj("message" -> Json.fromString("Supply a valid composition")))
    *> routes.run(Request[IO](Method.PUT, Uri.unsafeFromString("/api/albums/1-1-1-1-1/chapters/bad/composition")))
      .map(response => assertEquals(response.status, Status.NotFound))
