package photography.adapters.http

import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Response, Uri}
import photography.app.album.*
import photography.domain.album.{Album, AlbumComposition, AlbumId}
import photography.domain.layout.*

import java.time.Instant
import java.util.UUID

final class AlbumRoutesComponentTest extends CatsEffectSuite:

  private val createdAt  = Instant.parse("2026-10-07T12:00:00Z")
  private val missingId  = albumId(1)
  private val failingId  = albumId(2)
  private val existingId = albumId(3)

  test("should return the first album page as JSON when page is omitted"):
    val request = Request[IO](Method.GET, uri("/api/albums"))

    run(request).flatMap: response =>
      for
        body <- response.as[String]
      yield
        assertEquals(response.status.code, 200)
        assertEquals(
          body,
          """{"albums":[],"page":1,"pageSize":6,"totalAlbums":0,"totalPages":0}"""
        )

  test("should reject a non-integer page with validation feedback"):
    val request = Request[IO](Method.GET, uri("/api/albums?page=invalid"))

    run(request).flatMap: response =>
      response.as[String].map: body =>
        assertEquals(response.status.code, 400)
        assertEquals(body, """{"message":"Page must be an integer."}""")

  test("should return a generic server error when album listing fails"):
    val request = Request[IO](Method.GET, uri("/api/albums?page=0"))

    run(request).flatMap: response =>
      response.as[String].map: body =>
        assertEquals(response.status.code, 500)
        assertEquals(body, """{"code":"service_failure","message":"The request could not be completed."}""")

  test("should create an album and return its stable identifier"):
    val request = Request[IO](Method.POST, uri("/api/albums")).withEntity("""{"title":"Coast"}""")

    run(request).flatMap: response =>
      response.as[String].map: body =>
        assertEquals(response.status.code, 201)
        assert(body.contains(existingId.value.toString))
        assert(body.contains("Coast"))

  test("should reject a malformed create body with validation feedback"):
    val request = Request[IO](Method.POST, uri("/api/albums")).withEntity("""{"name":"Coast"}""")

    run(request).flatMap: response =>
      response.as[String].map: body =>
        assertEquals(response.status.code, 400)
        assertEquals(body, """{"message":"Request body must contain a title."}""")

  test("should reject malformed JSON and body-read failures with validation feedback"):
    val malformedJson =
      Request[IO](Method.POST, uri("/api/albums")).withEntity("""{"title":""")
    val failedBody = Request[IO](Method.POST, uri("/api/albums"))
      .withBodyStream(Stream.raiseError[IO](new IllegalStateException("unreadable body")))

    for
      malformed      <- run(malformedJson)
      malformedBody  <- malformed.as[String]
      failed         <- run(failedBody)
      failedBodyText <- failed.as[String]
    yield
      assertEquals(malformed.status.code, 400)
      assertEquals(failed.status.code, 400)
      assertEquals(malformedBody, """{"message":"Request body must contain a title."}""")
      assertEquals(failedBodyText, malformedBody)

  test("should return title validation feedback for a blank create title"):
    val request = Request[IO](Method.POST, uri("/api/albums")).withEntity("""{"title":"  "}""")

    run(request).flatMap: response =>
      response.as[String].map: body =>
        assertEquals(response.status.code, 400)
        assertEquals(body, """{"message":"Title must not be blank."}""")

  test("should map create persistence failures to a generic server error"):
    val request = Request[IO](Method.POST, uri("/api/albums")).withEntity("""{"title":"error"}""")

    run(request).flatMap: response =>
      response.as[String].map: body =>
        assertEquals(response.status.code, 500)
        assertEquals(body, """{"code":"service_failure","message":"The request could not be completed."}""")

  test("should rename an album and return its summary"):
    val request = Request[IO](Method.PATCH, uri(s"/api/albums/${existingId.value}"))
      .withEntity("""{"title":"Retitled"}""")

    run(request).flatMap: response =>
      response.as[String].map: body =>
        assertEquals(response.status.code, 200)
        assert(body.contains("Retitled"))

  test("should reject malformed rename bodies with validation feedback"):
    val request = Request[IO](Method.PATCH, uri(s"/api/albums/${existingId.value}"))
      .withEntity("""{"title":""")

    run(request).flatMap: response =>
      response.as[String].map: body =>
        assertEquals(response.status.code, 400)
        assertEquals(body, """{"message":"Request body must contain a title."}""")

  test("should map malformed rename identifiers and rename failures explicitly"):
    val malformedRequest = Request[IO](Method.PATCH, uri("/api/albums/not-a-uuid"))
      .withEntity("""{"title":"Retitled"}""")
    val failingRequest = Request[IO](Method.PATCH, uri(s"/api/albums/${existingId.value}"))
      .withEntity("""{"title":"error"}""")

    for
      malformed     <- run(malformedRequest)
      malformedBody <- malformed.as[String]
      failing       <- run(failingRequest)
      failingBody   <- failing.as[String]
    yield
      assertEquals(malformed.status.code, 404)
      assertEquals(malformedBody, """{"code":"album_not_found","message":"Album not found."}""")
      assertEquals(failing.status.code, 500)
      assertEquals(failingBody, """{"code":"service_failure","message":"The request could not be completed."}""")

  test("should map malformed and unknown album identifiers to the same not-found response"):
    val malformedRequest = Request[IO](Method.GET, uri("/api/albums/not-a-uuid"))
    val unknownRequest   = Request[IO](Method.GET, uri(s"/api/albums/${missingId.value}"))

    for
      malformed     <- run(malformedRequest)
      malformedBody <- malformed.as[String]
      unknown       <- run(unknownRequest)
      unknownBody   <- unknown.as[String]
    yield
      assertEquals(malformed.status.code, 404)
      assertEquals(unknown.status.code, 404)
      assertEquals(malformedBody, unknownBody)
      assertEquals(malformedBody, """{"code":"album_not_found","message":"Album not found."}""")

  test("should return the persisted full composition when the album exists"):
    val request = Request[IO](Method.GET, uri(s"/api/albums/${existingId.value}"))

    run(request).flatMap: response =>
      response.as[String].map: body =>
        assertEquals(response.status.code, 200)
        assert(body.contains(""""chapters":[]}"""))
        assert(body.contains(""""spineOwner":"front""""))
        assert(body.contains(""""kind":"text""""))

  test("should map service failures to a generic internal error"):
    val request = Request[IO](Method.GET, uri(s"/api/albums/${failingId.value}"))

    run(request).flatMap: response =>
      response.as[String].map: body =>
        assertEquals(response.status.code, 500)
        assertEquals(
          body,
          """{"code":"service_failure","message":"The request could not be completed."}"""
        )

  test("should return no content after deleting an album"):
    val request = Request[IO](Method.DELETE, uri(s"/api/albums/${existingId.value}"))

    run(request).flatMap: response =>
      response.as[String].map: body =>
        assertEquals(response.status.code, 204)
        assertEquals(body, "")

  test("should report a missing album when delete targets an unknown identifier"):
    val request = Request[IO](Method.DELETE, uri(s"/api/albums/${missingId.value}"))

    run(request).flatMap: response =>
      response.as[String].map: body =>
        assertEquals(response.status.code, 404)
        assertEquals(body, """{"code":"album_not_found","message":"Album not found."}""")

  test("should map malformed identifiers and delete failures explicitly"):
    val malformedRequest = Request[IO](Method.DELETE, uri("/api/albums/not-a-uuid"))
    val failingRequest   = Request[IO](Method.DELETE, uri(s"/api/albums/${failingId.value}"))

    for
      malformed     <- run(malformedRequest)
      malformedBody <- malformed.as[String]
      failing       <- run(failingRequest)
      failingBody   <- failing.as[String]
    yield
      assertEquals(malformed.status.code, 404)
      assertEquals(malformedBody, """{"code":"album_not_found","message":"Album not found."}""")
      assertEquals(failing.status.code, 500)
      assertEquals(failingBody, """{"code":"service_failure","message":"The request could not be completed."}""")

  private def run(request: Request[IO]): IO[Response[IO]] =
    AlbumRoutes.routes[IO](new FixedAlbumService).orNotFound.run(request)

  private def uri(value: String): Uri =
    Uri.unsafeFromString(value)

  private def albumId(value: Int): AlbumId =
    val suffix = value.toString.reverse.padTo(12, '0').reverse
    AlbumId(UUID.fromString(s"00000000-0000-4000-8000-$suffix"))

  final private class FixedAlbumService extends AlbumService[IO]:

    override def list(page: Int): IO[Either[AlbumServiceFailure, AlbumPage]] =
      if page === 0 then IO.pure(AlbumServiceFailure.ServiceFailure.asLeft[AlbumPage])
      else IO.pure(AlbumPage(Vector.empty[AlbumSummary], 1, 6, 0, 0).asRight[AlbumServiceFailure])

    override def create(title: String): IO[Either[AlbumServiceFailure, AlbumSummary]] =
      if title.trim === "error" then IO.pure(AlbumServiceFailure.ServiceFailure.asLeft[AlbumSummary])
      else if title.trim.isEmpty then IO.pure(AlbumServiceFailure.InvalidTitle.asLeft[AlbumSummary])
      else IO.pure(summary("Coast").asRight[AlbumServiceFailure])

    override def get(id: AlbumId): IO[Either[AlbumServiceFailure, AlbumComposition]] =
      id.value.toString match
        case value if value === missingId.value.toString =>
          IO.pure(AlbumServiceFailure.AlbumNotFound.asLeft[AlbumComposition])
        case value if value === failingId.value.toString =>
          IO.pure(AlbumServiceFailure.ServiceFailure.asLeft[AlbumComposition])
        case _ => IO.pure(composition.asRight[AlbumServiceFailure])

    override def rename(id: AlbumId, title: String): IO[Either[AlbumServiceFailure, AlbumSummary]] =
      if title.trim === "error" then IO.pure(AlbumServiceFailure.ServiceFailure.asLeft[AlbumSummary])
      else if title.trim.isEmpty then IO.pure(AlbumServiceFailure.InvalidTitle.asLeft[AlbumSummary])
      else summary(title.trim).asRight[AlbumServiceFailure].pure[IO]

    override def delete(id: AlbumId): IO[Either[AlbumServiceFailure, Unit]] =
      if id.value.toString === missingId.value.toString then
        IO.pure(AlbumServiceFailure.AlbumNotFound.asLeft[Unit])
      else if id.value.toString === failingId.value.toString then
        IO.pure(AlbumServiceFailure.ServiceFailure.asLeft[Unit])
      else IO.pure(().asRight[AlbumServiceFailure])

    private def summary(title: String): AlbumSummary =
      AlbumSummary(existingId, title.refineUnsafe[Not[Empty]], createdAt)

    private def composition: AlbumComposition =
      val spreadId = SpreadId(uuid(4))
      val textSlot = SpreadSlot.Text(
        TextSpreadSlotId(uuid(5)),
        spreadId,
        fullSpreadBox
      )
      AlbumComposition(
        Album(existingId, "Coast".refineUnsafe[Not[Empty]], "", createdAt),
        SpreadComposition(
          spreadId,
          SpreadTemplateId(uuid(6)),
          Vector(textSlot),
          Vector.empty[SpreadPlacement]
        ),
        CoverComposition(
          SpineOwner.Front,
          CoverSlot(CoverSlotId(uuid(7))),
          Option.empty[CoverPlacement],
          CoverSlot(CoverSlotId(uuid(8))),
          Option.empty[CoverPlacement]
        ),
        Vector.empty[photography.domain.album.ChapterComposition]
      )

    private def fullSpreadBox: SlotBox =
      val x      = 0.0.refineUnsafe[GreaterEqual[0.0] & Less[1.7976931348623157e308]]
      val y      = 0.0.refineUnsafe[GreaterEqual[0.0] & Less[1.7976931348623157e308]]
      val width  = 1.0.refineUnsafe[Positive & Less[1.7976931348623157e308]]
      val height = 1.0.refineUnsafe[Positive & Less[1.7976931348623157e308]]
      SlotBox(x, y, width, height)

    private def uuid(value: Int): UUID =
      val suffix = value.toString.reverse.padTo(12, '0').reverse
      UUID.fromString(s"00000000-0000-4000-8000-$suffix")
