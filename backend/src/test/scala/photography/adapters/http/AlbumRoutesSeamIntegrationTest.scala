package photography.adapters.http

import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import munit.CatsEffectSuite
import org.http4s.{MalformedMessageBodyFailure, Method, Request, Uri}
import photography.app.album.*
import photography.domain.album.{Album, AlbumId}

import java.time.Instant
import java.util.UUID

final class AlbumRoutesSeamIntegrationTest extends CatsEffectSuite:

  private val createdAt          = Instant.parse("2026-10-07T12:00:00Z")
  private val missingId          = buildAlbumId(1)
  private val failingId          = buildAlbumId(2)
  private val existingId         = buildAlbumId(3)
  private val albumsPath         = "/api/albums"
  private val existingPath       = s"$albumsPath/${existingId.value}"
  private val failingPath        = s"$albumsPath/${failingId.value}"
  private val missingPath        = s"$albumsPath/${missingId.value}"
  private val serviceFailureBody =
    """{"code":"service_failure","message":"The request could not be completed."}"""
  private val albumNotFoundBody = """{"code":"album_not_found","message":"Album not found."}"""
  private val titleRequiredBody = """{"message":"Request body must contain a title."}"""
  private val blankTitleBody    = """{"message":"Title must not be blank."}"""
  private val coastAlbumBody    = s"""{"id":"${existingId.value}","title":"Coast","synopsis":"","createdAt":"$createdAt"}"""

  test("should return the first album page as JSON when page is omitted"):
    val expectedBody = """{"albums":[],"page":1,"pageSize":6,"totalAlbums":0,"totalPages":0}"""

    for
      response <- runRequest(Request[IO](Method.GET, buildUri(albumsPath)))
      body     <- response.as[String]
    yield
      assertEquals(response.status.code, 200)
      assertEquals(body, expectedBody)

  test("should encode Album fields in a populated page"):
    val expectedBody =
      s"""{"albums":[$coastAlbumBody],"page":2,"pageSize":6,"totalAlbums":7,"totalPages":2}"""

    for
      response <- runRequest(Request[IO](Method.GET, buildUri(s"$albumsPath?page=2")))
      body     <- response.as[String]
    yield
      assertEquals(response.status.code, 200)
      assertEquals(body, expectedBody)

  test("should reject a non-integer page with validation feedback"):
    for
      response <- runRequest(Request[IO](Method.GET, buildUri(s"$albumsPath?page=invalid")))
      body     <- response.as[String]
    yield
      assertEquals(response.status.code, 400)
      assertEquals(body, """{"message":"Page must be an integer."}""")

  test("should return a generic server error when album listing fails"):
    for
      response <- runRequest(Request[IO](Method.GET, buildUri(s"$albumsPath?page=0")))
      body     <- response.as[String]
    yield
      assertEquals(response.status.code, 500)
      assertEquals(body, serviceFailureBody)

  test("should create an album and return its Album resource"):
    val request = Request[IO](Method.POST, buildUri(albumsPath)).withEntity("""{"title":"Coast"}""")

    for
      response <- runRequest(request)
      body     <- response.as[String]
    yield
      assertEquals(response.status.code, 201)
      assertEquals(body, coastAlbumBody)

  test("should reject a malformed create body with validation feedback"):
    val request = Request[IO](Method.POST, buildUri(albumsPath)).withEntity("""{"name":"Coast"}""")

    for
      response <- runRequest(request)
      body     <- response.as[String]
    yield
      assertEquals(response.status.code, 400)
      assertEquals(body, titleRequiredBody)

  test("should reject malformed JSON and propagate unrelated body-read failures"):
    val malformedJson = Request[IO](Method.POST, buildUri(albumsPath)).withEntity("""{"title":""")
    val decodeFailure = Request[IO](Method.POST, buildUri(albumsPath))
      .withBodyStream(Stream.raiseError[IO](MalformedMessageBodyFailure("invalid body", None)))
    val failedBody = Request[IO](Method.POST, buildUri(albumsPath))
      .withBodyStream(Stream.raiseError[IO](new IllegalStateException("unreadable body")))

    for
      malformed     <- runRequest(malformedJson)
      malformedBody <- malformed.as[String]
      decodeFailed  <- runRequest(decodeFailure)
      decodeBody    <- decodeFailed.as[String]
      failed        <- runRequest(failedBody).attempt
    yield
      assertEquals(malformed.status.code, 400)
      assertEquals(malformedBody, titleRequiredBody)
      assertEquals(decodeFailed.status.code, 400)
      assertEquals(decodeBody, titleRequiredBody)
      assert(failed.isLeft)

  test("should return title validation feedback for a blank create title"):
    val request = Request[IO](Method.POST, buildUri(albumsPath)).withEntity("""{"title":"  "}""")

    for
      response <- runRequest(request)
      body     <- response.as[String]
    yield
      assertEquals(response.status.code, 400)
      assertEquals(body, blankTitleBody)

  test("should map create persistence failures to a generic server error"):
    val request = Request[IO](Method.POST, buildUri(albumsPath)).withEntity("""{"title":"error"}""")

    for
      response <- runRequest(request)
      body     <- response.as[String]
    yield
      assertEquals(response.status.code, 500)
      assertEquals(body, serviceFailureBody)

  test("should rename an album and return its Album resource"):
    val request      = Request[IO](Method.PATCH, buildUri(existingPath)).withEntity("""{"title":"Retitled"}""")
    val expectedBody =
      s"""{"id":"${existingId.value}","title":"Retitled","synopsis":"","createdAt":"$createdAt"}"""

    for
      response <- runRequest(request)
      body     <- response.as[String]
    yield
      assertEquals(response.status.code, 200)
      assertEquals(body, expectedBody)

  test("should reject malformed rename bodies with validation feedback"):
    val request = Request[IO](Method.PATCH, buildUri(existingPath)).withEntity("""{"title":""")

    for
      response <- runRequest(request)
      body     <- response.as[String]
    yield
      assertEquals(response.status.code, 400)
      assertEquals(body, titleRequiredBody)

  test("should map malformed rename identifiers and rename failures explicitly"):
    val malformedRequest = Request[IO](Method.PATCH, buildUri(s"$albumsPath/not-a-uuid"))
      .withEntity("""{"title":"Retitled"}""")
    val failingRequest = Request[IO](Method.PATCH, buildUri(existingPath))
      .withEntity("""{"title":"error"}""")

    for
      malformed     <- runRequest(malformedRequest)
      malformedBody <- malformed.as[String]
      failing       <- runRequest(failingRequest)
      failingBody   <- failing.as[String]
    yield
      assertEquals(malformed.status.code, 404)
      assertEquals(malformedBody, albumNotFoundBody)
      assertEquals(failing.status.code, 500)
      assertEquals(failingBody, serviceFailureBody)

  test("should map malformed and unknown album identifiers to the same not-found response"):
    val malformedRequest = Request[IO](Method.GET, buildUri(s"$albumsPath/not-a-uuid"))
    val unknownRequest   = Request[IO](Method.GET, buildUri(missingPath))

    for
      malformed     <- runRequest(malformedRequest)
      malformedBody <- malformed.as[String]
      unknown       <- runRequest(unknownRequest)
      unknownBody   <- unknown.as[String]
    yield
      assertEquals(malformed.status.code, 404)
      assertEquals(unknown.status.code, 404)
      assertEquals(malformedBody, unknownBody)
      assertEquals(malformedBody, albumNotFoundBody)

  test("should return only the Album resource when the album exists"):
    for
      response <- runRequest(Request[IO](Method.GET, buildUri(existingPath)))
      body     <- response.as[String]
    yield
      assertEquals(response.status.code, 200)
      assertEquals(body, coastAlbumBody)

  test("should map service failures to a generic internal error"):
    for
      response <- runRequest(Request[IO](Method.GET, buildUri(failingPath)))
      body     <- response.as[String]
    yield
      assertEquals(response.status.code, 500)
      assertEquals(body, serviceFailureBody)

  test("should return no content after deleting an album"):
    for
      response <- runRequest(Request[IO](Method.DELETE, buildUri(existingPath)))
      body     <- response.as[String]
    yield
      assertEquals(response.status.code, 204)
      assertEquals(body, "")

  test("should report a missing album when delete targets an unknown identifier"):
    for
      response <- runRequest(Request[IO](Method.DELETE, buildUri(missingPath)))
      body     <- response.as[String]
    yield
      assertEquals(response.status.code, 404)
      assertEquals(body, albumNotFoundBody)

  test("should map malformed identifiers and delete failures explicitly"):
    val malformedRequest = Request[IO](Method.DELETE, buildUri(s"$albumsPath/not-a-uuid"))
    val failingRequest   = Request[IO](Method.DELETE, buildUri(failingPath))

    for
      malformed     <- runRequest(malformedRequest)
      malformedBody <- malformed.as[String]
      failing       <- runRequest(failingRequest)
      failingBody   <- failing.as[String]
    yield
      assertEquals(malformed.status.code, 404)
      assertEquals(malformedBody, albumNotFoundBody)
      assertEquals(failing.status.code, 500)
      assertEquals(failingBody, serviceFailureBody)

  private def runRequest(request: Request[IO]) =
    AlbumRoutes.routes[IO](new FixedAlbumService).orNotFound.run(request)

  private def buildUri(value: String) =
    Uri.unsafeFromString(value)

  private def buildAlbumId(value: Int) =
    val suffix = value.toString.reverse.padTo(12, '0').reverse
    AlbumId(UUID.fromString(s"00000000-0000-4000-8000-$suffix"))

  private def buildAlbum(title: String) =
    Album(existingId, title.refineUnsafe[Not[Empty]], "", createdAt)

  final private class FixedAlbumService extends AlbumService[IO]:

    override def list(page: Int): IO[Either[AlbumServiceFailure, AlbumPage]] =
      if page === 0 then IO.pure(AlbumServiceFailure.ServiceFailure.asLeft[AlbumPage])
      else if page === 2 then
        IO.pure(AlbumPage(Vector(buildAlbum("Coast")), 2, 6, 7, 2).asRight[AlbumServiceFailure])
      else IO.pure(AlbumPage(Vector.empty[Album], 1, 6, 0, 0).asRight[AlbumServiceFailure])

    override def create(title: String): IO[Either[AlbumServiceFailure, Album]] =
      if title.trim === "error" then IO.pure(AlbumServiceFailure.ServiceFailure.asLeft[Album])
      else if title.trim.isEmpty then IO.pure(AlbumServiceFailure.InvalidTitle.asLeft[Album])
      else IO.pure(buildAlbum("Coast").asRight[AlbumServiceFailure])

    override def get(id: AlbumId): IO[Either[AlbumServiceFailure, Album]] =
      id.value.toString match
        case value if value === missingId.value.toString =>
          IO.pure(AlbumServiceFailure.AlbumNotFound.asLeft[Album])
        case value if value === failingId.value.toString =>
          IO.pure(AlbumServiceFailure.ServiceFailure.asLeft[Album])
        case _ => IO.pure(buildAlbum("Coast").asRight[AlbumServiceFailure])

    override def rename(id: AlbumId, title: String): IO[Either[AlbumServiceFailure, Album]] =
      if title.trim === "error" then IO.pure(AlbumServiceFailure.ServiceFailure.asLeft[Album])
      else if title.trim.isEmpty then IO.pure(AlbumServiceFailure.InvalidTitle.asLeft[Album])
      else buildAlbum(title.trim).asRight[AlbumServiceFailure].pure[IO]

    override def delete(id: AlbumId): IO[Either[AlbumServiceFailure, Unit]] =
      if id.value.toString === missingId.value.toString then
        IO.pure(AlbumServiceFailure.AlbumNotFound.asLeft[Unit])
      else if id.value.toString === failingId.value.toString then
        IO.pure(AlbumServiceFailure.ServiceFailure.asLeft[Unit])
      else IO.pure(().asRight[AlbumServiceFailure])
