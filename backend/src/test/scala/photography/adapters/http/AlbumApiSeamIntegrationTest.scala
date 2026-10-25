package photography.adapters.http

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.jawn.parse
import io.github.iltotore.iron.*
import munit.CatsEffectSuite
import org.http4s.{HttpApp, MediaType, Method, Request, Status, Uri}
import org.http4s.headers.`Content-Type`
import photography.domain.album.{Album, AlbumId, AlbumTitle, AlbumYear, Chapter}
import photography.domain.layout.*
import photography.usecases.AlbumManager
import photography.usecases.AlbumManager.*

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
  private val album =
    Album(id, 2026, "Journey of the Shepherds 2026", "A synopsis", Instant.parse("2026-10-24T10:00:00.123Z"), start, chapters = Vector.empty[Chapter])
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

  test("should create with a required year and accept a trimmed free-form rename"):
    val refs             = Refs()
    val server           = buildAlbumApi(refs)
    val expectedMetadata = parse(metadata).fold(error => fail(error.message), identity).some

    for
      created <- send(server, Method.POST, "/api/albums", """{"year":2026}""")
      renamed <- send(server, Method.PATCH, albumPath, """{"title":"\tAsian capitals\n"}""")
      calls   <- refs.calls.get
    yield
      assertEquals(created, ApiResponse(Status.Created, jsonMediaType, expectedMetadata))
      assertEquals(renamed, ApiResponse(Status.Ok, jsonMediaType, expectedMetadata))
      assertEquals(calls, Vector("create:2026:Journey of the Shepherds 2026", s"rename:${id.value}:Asian capitals"))

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
      """{"title":3}"""
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
      renameResult = RenameResult.NotFound,
      deleteAlbumResult = DeleteAlbumResult.NotFound
    )
    val paths        = Vector(albumPath, "/api/albums/not-a-uuid", "/api/albums/1-1-1-1-1")
    val requests     = Vector(Method.GET, Method.PATCH, Method.DELETE).flatMap(method => paths.map(method -> _))
    val expectedBody = parse("""{"code":"album_not_found"}""").fold(error => fail(error.message), identity)
    val expected     = Vector.fill(requests.size)(ApiResponse(Status.NotFound, jsonMediaType, expectedBody.some))
    val responses    = requests.traverse { case (method, path) =>
      send(server, method, path, """{"year":2026,"title":"Journey of the Shepherds 2026"}""")
    }

    for
      actual <- responses
      calls  <- refs.calls.get
    yield
      assertEquals(actual, expected)
      assertEquals(calls, Vector(s"get:${id.value}", s"rename:${id.value}:Journey of the Shepherds 2026", s"delete:${id.value}"))

  test("should translate every application's service failure to the same JSON error"):
    val server = buildAlbumApi(
      Refs(),
      listAlbumsResult = ListAlbumsResult.Failed,
      createAlbumResult = CreateAlbumResult.Failed,
      getAlbumResult = GetAlbumResult.Failed,
      renameResult = RenameResult.Failed,
      deleteAlbumResult = DeleteAlbumResult.Failed
    )
    val operations = Vector(
      Method.GET    -> "/api/albums",
      Method.POST   -> "/api/albums",
      Method.GET    -> albumPath,
      Method.PATCH  -> albumPath,
      Method.DELETE -> albumPath
    )
    val expectedBody = parse("""{"code":"service_failure"}""").fold(error => fail(error.message), identity)
    val expected     = Vector.fill(operations.size)(ApiResponse(Status.InternalServerError, jsonMediaType, expectedBody.some))

    operations.traverse { case (method, path) => send(server, method, path, """{"year":2026,"title":"Journey of the Shepherds 2026"}""") }
      .map(responses => assertEquals(responses, expected))

  test("should reject duplicate years and expose no year-edit operation"):
    val refs     = Refs()
    val server   = buildAlbumApi(refs)
    val taken    = buildAlbumApi(Refs(), createAlbumResult = CreateAlbumResult.YearAlreadyUsed)
    val yearEdit = Request[IO](Method.PUT, Uri.unsafeFromString(s"$albumPath/year")).withEntity("""{"year":2027}""")

    for
      changed   <- server.run(yearEdit)
      duplicate <- send(taken, Method.POST, "/api/albums", """{"year":2026}""")
      calls     <- refs.calls.get
    yield
      assertEquals(changed.status, Status.NotFound)
      assertEquals(duplicate.status, Status.BadRequest)
      assert(hasValidationFeedback(duplicate))
      assertEquals(calls, Vector.empty)

  final private case class Refs():
    val calls: Ref[IO, Vector[String]] = Ref.unsafe(Vector.empty)

  final private case class ApiResponse(status: Status, maybeContentType: Option[MediaType], maybeBody: Option[Json])

  // scalafix:off DisableSyntax.defaultArgs
  private def buildAlbumApi(
      refs: Refs,
      listAlbumsResult: ListAlbumsResult = ListAlbumsResult.Listed(AlbumPage(Vector.empty, 1, 6, 0, 0)),
      createAlbumResult: CreateAlbumResult = CreateAlbumResult.Created(album),
      getAlbumResult: GetAlbumResult = GetAlbumResult.Found(album),
      renameResult: RenameResult = RenameResult.Renamed(album),
      deleteAlbumResult: DeleteAlbumResult = DeleteAlbumResult.Deleted
  ) =
    val albums = new AlbumManager[IO]:
      override def list(page: Int): IO[ListAlbumsResult]                             = refs.calls.update(_ :+ s"list:$page").as(listAlbumsResult)
      override def create(year: AlbumYear, title: AlbumTitle): IO[CreateAlbumResult] =
        refs.calls.update(_ :+ s"create:$year:$title").as(createAlbumResult)
      override def get(albumId: AlbumId): IO[GetAlbumResult]                     = refs.calls.update(_ :+ s"get:${albumId.value}").as(getAlbumResult)
      override def rename(albumId: AlbumId, title: AlbumTitle): IO[RenameResult] =
        refs.calls.update(_ :+ s"rename:${albumId.value}:$title").as(renameResult)
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
