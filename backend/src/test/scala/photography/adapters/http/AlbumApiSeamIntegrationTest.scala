package photography.adapters.http

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.jawn.parse
import io.github.iltotore.iron.*
import munit.CatsEffectSuite
import org.http4s.{HttpApp, MediaType, Method, Request, Status, Uri}
import org.http4s.headers.`Content-Type`
import photography.domain.album.{Album, AlbumId, AlbumYear}
import photography.domain.layout.*
import photography.usecases.AlbumService
import photography.usecases.AlbumService.*

import java.time.Instant
import java.util.UUID

final class AlbumApiSeamIntegrationTest extends CatsEffectSuite:
  private val id    = AlbumId(new UUID(0L, 1L))
  private val start = Spread.AlbumStart(
    SpreadId(new UUID(0L, 3L)),
    SpreadTemplateId(new UUID(0L, 2L)),
    Vector.empty,
    SpreadSlot.Text(TextSpreadSlotId(new UUID(0L, 4L)), SlotBox(0.0, 0.0, 100.0, 100.0)),
    Vector.empty
  )
  private val album         = Album(id, 2026, "A synopsis", Instant.parse("2026-10-24T10:00:00.123Z"), start, Vector.empty, maybeHome = None)
  private val metadata      = s"""{"id":"${id.value}","year":2026,"title":"Journey of the Shepherds 2026","createdAt":"2026-10-24T10:00:00.123Z"}"""
  private val albumPath     = s"/api/albums/${id.value}"
  private val jsonMediaType = MediaType.application.json.some

  test("should list exact album metadata and forward default and explicit pages"):
    val refs         = Refs()
    val page         = AlbumPage(Vector(album), 2, 6, 7, 2)
    val server       = buildAlbumApi(refs, listAlbumsResult = ListAlbumsResult.Listed(page))
    val paths        = Vector("/api/albums", "/api/albums?page=99", "/api/albums?page=0", "/api/albums?page=-1")
    val emptyServer  = buildAlbumApi(Refs())
    val expectedPage =
      parse(s"""{"albums":[$metadata],"page":2,"pageSize":6,"totalAlbums":7,"totalPages":2}""").fold(error => fail(error.message), identity)
    val expectedEmpty = parse("""{"albums":[],"page":1,"pageSize":6,"totalAlbums":0,"totalPages":0}""").fold(error => fail(error.message), identity)
    val expectedResponses = Vector.fill(paths.size)(ApiResponse(Status.Ok, jsonMediaType, expectedPage.some))

    for
      responses <- paths.traverse(send(server, Method.GET, _, ""))
      empty     <- send(emptyServer, Method.GET, "/api/albums", "")
      calls     <- refs.calls.get
    yield
      assertEquals(responses, expectedResponses)
      assertEquals(empty, ApiResponse(Status.Ok, jsonMediaType, expectedEmpty.some))
      assertEquals(calls, Vector("list:1", "list:99", "list:0", "list:-1"))

  test("should reject malformed and repeated page parameters without calling the application"):
    val refs    = Refs()
    val server  = buildAlbumApi(refs)
    val queries = Vector("page", "page=", "page=no", "page=1.5", "page=2147483648", "page=1&page=2", "page=1&page=no", "page=1&page")

    for
      responses <- queries.traverse(query => send(server, Method.GET, s"/api/albums?$query", ""))
      calls     <- refs.calls.get
    yield
      assertEquals(responses.map(_.status), Vector.fill(queries.size)(Status.BadRequest))
      assertEquals(responses.map(_.maybeContentType), Vector.fill(queries.size)(jsonMediaType))
      assert(responses.forall(hasValidationFeedback))
      assertEquals(calls, Vector.empty)

  test("should create with a required year and decode the canonical rename title at the HTTP boundary"):
    val refs             = Refs()
    val server           = buildAlbumApi(refs)
    val expectedMetadata = parse(metadata).fold(error => fail(error.message), identity).some

    for
      created <- send(server, Method.POST, "/api/albums", """{"year":2026}""")
      renamed <- send(server, Method.PATCH, albumPath, """{"title":"\tJourney of the Shepherds 2026\n"}""")
      calls   <- refs.calls.get
    yield
      assertEquals(created, ApiResponse(Status.Created, jsonMediaType, expectedMetadata))
      assertEquals(renamed, ApiResponse(Status.Ok, jsonMediaType, expectedMetadata))
      assertEquals(calls, Vector("create:2026", s"get:${id.value}"))

  test("should return validation feedback for invalid title bodies without calling the application"):
    val refs         = Refs()
    val server       = buildAlbumApi(refs)
    val malformed    = Vector("{}", "{", "[]")
    val invalidYears =
      Vector("""{"title":"Coast"}""", """{"year":null}""", """{"year":"2026"}""", """{"year":2026.5}""", """{"year":1899}""", """{"year":2101}""")
    val invalidTitles = Vector(
      """{"title":""}""",
      """{"title":" \t\n "}""",
      """{"title":null}""",
      """{"title":3}""",
      """{"title":"Coast"}""",
      """{"title":"Journey of the Shepherds 02026"}""",
      """{"title":"Journey of the Shepherds 2101"}""",
      """{"title":"Journey of the Shepherds no"}"""
    )
    val requests = (malformed ++ invalidYears).map(body => (Method.POST, "/api/albums", body)) ++
      (malformed ++ invalidTitles).map(body => (Method.PATCH, albumPath, body))

    for
      responses <- requests.traverse { case (method, path, body) => send(server, method, path, body) }
      calls     <- refs.calls.get
    yield
      assertEquals(responses.map(_.status), Vector.fill(requests.size)(Status.BadRequest))
      assertEquals(responses.map(_.maybeContentType), Vector.fill(requests.size)(jsonMediaType))
      assert(responses.forall(hasValidationFeedback))
      assertEquals(calls, Vector.empty)

  test("should read metadata without composition and delete with an empty 204 response"):
    val refs     = Refs()
    val server   = buildAlbumApi(refs)
    val expected = parse(
      s"""{"id":"${id.value}","year":2026,"title":"Journey of the Shepherds 2026","synopsis":"A synopsis","createdAt":"2026-10-24T10:00:00.123Z"}"""
    )
      .fold(error => fail(error.message), identity)

    for
      found   <- send(server, Method.GET, albumPath, "")
      deleted <- send(server, Method.DELETE, albumPath, "")
      calls   <- refs.calls.get
    yield
      assertEquals(found, ApiResponse(Status.Ok, jsonMediaType, expected.some))
      assertEquals(deleted, ApiResponse(Status.NoContent, maybeContentType = None, maybeBody = None))
      assertEquals(calls, Vector(s"get:${id.value}", s"delete:${id.value}"))

  test("should give malformed and unknown album IDs the same missing-resource response"):
    val refs   = Refs()
    val server = buildAlbumApi(
      refs,
      getAlbumResult = GetAlbumResult.NotFound,
      changeYearResult = ChangeYearResult.NotFound,
      deleteAlbumResult = DeleteAlbumResult.NotFound
    )
    val paths    = Vector(albumPath, "/api/albums/not-a-uuid", "/api/albums/1-1-1-1-1")
    val requests = Vector(Method.GET, Method.PATCH, Method.DELETE).flatMap(method => paths.map(method -> _)) ++
      paths.map(path => Method.PUT -> s"$path/year")
    val expectedBody = parse("""{"code":"album_not_found"}""").fold(error => fail(error.message), identity)
    val expected     = Vector.fill(requests.size)(ApiResponse(Status.NotFound, jsonMediaType, expectedBody.some))

    for
      responses <- requests.traverse { case (method, path) =>
        send(server, method, path, """{"year":2026,"title":"Journey of the Shepherds 2026"}""")
      }
      calls <- refs.calls.get
    yield
      assertEquals(responses, expected)
      assertEquals(calls, Vector(s"get:${id.value}", s"get:${id.value}", s"delete:${id.value}", s"year:${id.value}:2026"))

  test("should translate every application's service failure to the same JSON error"):
    val server = buildAlbumApi(
      Refs(),
      listAlbumsResult = ListAlbumsResult.Failed,
      createAlbumResult = CreateAlbumResult.Failed,
      getAlbumResult = GetAlbumResult.Failed,
      changeYearResult = ChangeYearResult.Failed,
      deleteAlbumResult = DeleteAlbumResult.Failed
    )
    val operations = Vector(
      Method.GET    -> "/api/albums",
      Method.POST   -> "/api/albums",
      Method.GET    -> albumPath,
      Method.PATCH  -> albumPath,
      Method.DELETE -> albumPath,
      Method.PUT    -> s"$albumPath/year"
    )
    val expectedBody = parse("""{"code":"service_failure"}""").fold(error => fail(error.message), identity)
    val expected     = Vector.fill(operations.size)(ApiResponse(Status.InternalServerError, jsonMediaType, expectedBody.some))

    operations.traverse { case (method, path) => send(server, method, path, """{"year":2026,"title":"Journey of the Shepherds 2026"}""") }
      .map(responses => assertEquals(responses, expected))

  test("should return year title and readable feedback for business conflicts"):
    val server    = buildAlbumApi(Refs())
    val conflicts = Vector(ChangeYearResult.HasChapters, ChangeYearResult.YearAlreadyUsed)
    val taken     = buildAlbumApi(Refs(), createAlbumResult = CreateAlbumResult.YearAlreadyUsed)
    val rename    = buildAlbumApi(Refs())

    for
      changed  <- send(server, Method.PUT, s"$albumPath/year", """{"year":2026}""")
      invalid  <- send(server, Method.PUT, s"$albumPath/year", """{"year":null}""")
      rejected <-
        conflicts.traverse(result => send(buildAlbumApi(Refs(), changeYearResult = result), Method.PUT, s"$albumPath/year", """{"year":2027}"""))
      duplicate <- send(taken, Method.POST, "/api/albums", """{"year":2026}""")
      renamed   <- send(rename, Method.PATCH, albumPath, """{"title":"Journey of the Shepherds 2027"}""")
    yield
      val expectedYear = parse("""{"year":2026,"title":"Journey of the Shepherds 2026"}""").fold(error => fail(error.message), identity)
      val errors       = rejected ++ Vector(invalid, duplicate, renamed)
      assertEquals(changed, ApiResponse(Status.Ok, jsonMediaType, expectedYear.some))
      assertEquals(errors.map(_.status), Vector.fill(errors.size)(Status.BadRequest))
      assert(errors.forall(hasValidationFeedback))

  final private case class Refs():
    val calls: Ref[IO, Vector[String]] = Ref.unsafe(Vector.empty)

  final private case class ApiResponse(status: Status, maybeContentType: Option[MediaType], maybeBody: Option[Json])

  // scalafix:off DisableSyntax.defaultArgs
  private def buildAlbumApi(
      refs: Refs,
      listAlbumsResult: ListAlbumsResult = ListAlbumsResult.Listed(AlbumPage(Vector.empty, 1, 6, 0, 0)),
      createAlbumResult: CreateAlbumResult = CreateAlbumResult.Created(album),
      getAlbumResult: GetAlbumResult = GetAlbumResult.Found(album),
      changeYearResult: ChangeYearResult = ChangeYearResult.Changed(album),
      deleteAlbumResult: DeleteAlbumResult = DeleteAlbumResult.Deleted
  ) =
    val albums = new AlbumService[IO]:
      override def list(page: Int): IO[ListAlbumsResult]          = refs.calls.update(_ :+ s"list:$page").as(listAlbumsResult)
      override def create(year: AlbumYear): IO[CreateAlbumResult] = refs.calls.update(_ :+ s"create:$year").as(createAlbumResult)
      override def get(albumId: AlbumId): IO[GetAlbumResult]      = refs.calls.update(_ :+ s"get:${albumId.value}").as(getAlbumResult)
      override def changeYear(albumId: AlbumId, year: AlbumYear): IO[ChangeYearResult] =
        refs.calls.update(_ :+ s"year:${albumId.value}:$year").as(changeYearResult)
      override def delete(albumId: AlbumId): IO[DeleteAlbumResult] = refs.calls.update(_ :+ s"delete:${albumId.value}").as(deleteAlbumResult)
    AlbumApi.make(albums).routes.orNotFound
  // scalafix:on DisableSyntax.defaultArgs

  private def send(server: HttpApp[IO], method: Method, path: String, body: String) =
    val request = Request[IO](method, Uri.unsafeFromString(path)).withEntity(body).putHeaders(`Content-Type`(MediaType.application.json))

    for
      response <- server.run(request)
      body     <- response.as[String]
    yield
      val maybeBody = Option.when(body.nonEmpty)(parse(body).fold(error => fail(error.message), identity))
      ApiResponse(response.status, response.contentType.map(_.mediaType), maybeBody)

  private def hasValidationFeedback(response: ApiResponse) =
    response.maybeBody.exists: body =>
      body.asObject.exists(_.keys.toSet === Set("message")) && body.hcursor.get[String]("message").toOption.exists(_.nonEmpty)
