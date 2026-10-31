package photography.adapters.http

import cats.data.NonEmptyVector
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.jawn.parse
import munit.CatsEffectSuite
import org.http4s.{HttpApp, MediaType, Method, Request, Status, Uri}
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import photography.domain.album.{AlbumId, ChapterId}
import photography.domain.gear.*
import photography.usecases.chapter.ChapterManager
import photography.usecases.chapter.ChapterManager.*
import photography.usecases.gear.GearManager
import java.util.UUID

final class ChapterGearApiSeamIntegrationTest extends CatsEffectSuite:
  private val album     = AlbumId(value = new UUID(0L, 1L))
  private val chapter   = ChapterId(value = new UUID(0L, 2L))
  private val fixed     = CameraId(value = new UUID(0L, 3L))
  private val body      = CameraId(value = new UUID(0L, 4L))
  private val second    = CameraId(value = new UUID(0L, 5L))
  private val lens      = LensId(value = new UUID(0L, 6L))
  private val other     = LensId(value = new UUID(0L, 7L))
  private val path      = s"/api/albums/${album.value}/chapters/${chapter.value}/gear"
  private val selection = Vector(
    CameraSelection(fixed, CameraUse.FixedLens),
    CameraSelection(body, CameraUse.Interchangeable(lenses = NonEmptyVector.of(other, lens))),
    CameraSelection(second, CameraUse.Interchangeable(lenses = NonEmptyVector.one(lens)))
  )
  private val selectedJson =
    s"""{"cameras":[{"cameraId":"${fixed.value}","lensIds":[]},{"cameraId":"${body.value}","lensIds":["${other.value}","${lens.value}"]},{"cameraId":"${second.value}","lensIds":["${lens.value}"]}]}"""

  test("should read and replace ordered shared selections with typed fixed and interchangeable cardinality and explicit empty success"):
    Vector(selection -> selectedJson, Vector.empty[CameraSelection] -> """{"cameras":[]}""").traverse_ { case (values, json) =>
      val refs   = Refs()
      val server = buildChapterGearApi(refs, readResult = GearReadResult.Found(selection = values))

      for
        read  <- send(server, Method.GET, path = path, body = "")
        saved <- send(server, Method.PUT, path = path, body = json)
        calls <- refs.calls.get
      yield
        assertEquals(read, (Status.Ok, json, MediaType.application.json.some, "no-store".some))
        assertEquals(saved, (Status.NoContent, "", None, None))
        assertEquals(calls, Vector(Call.Read(album, chapter), Call.Replace(album, chapter, selection = values)))
    }

  test("should translate missing and failed reads and every acknowledged failed uncertain or rejected replacement"):
    val reads = Vector(
      GearReadResult.NotFound -> (Status.NotFound, "chapter_not_found"),
      GearReadResult.Failed   -> (Status.InternalServerError, "service_failure")
    )
    val writes = Vector(
      GearReplaceResult.Saved     -> (Status.NoContent, ""),
      GearReplaceResult.NotFound  -> (Status.NotFound, "chapter_not_found"),
      GearReplaceResult.Failed    -> (Status.InternalServerError, "service_failure"),
      GearReplaceResult.Uncertain -> (Status.InternalServerError, "mutation_uncertain")
    )

    val checkReads = reads.traverse_ { case (result, (status, code)) =>
      val refs   = Refs()
      val server = buildChapterGearApi(refs, readResult = result)

      for
        response <- send(server, Method.GET, path = path, body = "")
        calls    <- refs.calls.get
      yield
        assertEquals(response, errorResponse(status, code = code))
        assertEquals(calls, Vector(Call.Read(album, chapter)))
    }
    val checkWrites = writes.traverse_ { case (result, (status, code)) =>
      val refs     = Refs()
      val server   = buildChapterGearApi(refs, replaceResult = result)
      val expected = if status === Status.NoContent then (status, "", None, None) else errorResponse(status, code = code)

      for
        response <- send(server, Method.PUT, path = path, body = selectedJson)
        calls    <- refs.calls.get
      yield
        assertEquals(response, expected)
        assertEquals(calls, Vector(Call.Replace(album, chapter, selection = selection)))
    }
    val checkRejections = GearManager.Failure.values.toVector.traverse_ { reason =>
      val refs     = Refs()
      val server   = buildChapterGearApi(refs, replaceResult = GearReplaceResult.Rejected(reason))
      val expected = (Status.BadRequest, Json.obj("message" -> Json.fromString(explain(reason))).noSpaces, MediaType.application.json.some, None)

      for
        response <- send(server, Method.PUT, path = path, body = selectedJson)
        calls    <- refs.calls.get
      yield
        assertEquals(response, expected)
        assertEquals(calls, Vector(Call.Replace(album, chapter, selection = selection)))
    }

    checkReads >> checkWrites >> checkRejections

  test("should reject malformed selection fields or identities before application calls while leaving duplicates to the application"):
    val malformedLensIds = Vector("bad", "1-1-1-1-1", "00000000-0000-0000-0000-00000000000A").map(id =>
      s"""{"cameras":[{"cameraId":"${body.value}","lensIds":["$id"]}]}"""
    )
    val malformed = malformedLensIds ++ Vector(
      "{",
      "[]",
      "null",
      "{}",
      """{"cameras":null}""",
      """{"cameras":{}}""",
      """{"cameras":[null]}""",
      s"""{"cameras":[{"cameraId":"${fixed.value}"}]}""",
      s"""{"cameras":[{"cameraId":"${fixed.value}","lensIds":null}]}""",
      s"""{"cameras":[{"cameraId":"${fixed.value}","lensIds":"bad"}]}""",
      """{"cameras":[{"cameraId":1,"lensIds":[]}]}""",
      """{"cameras":[{"cameraId":"1-1-1-1-1","lensIds":[]}]}""",
      """{"cameras":[{"cameraId":"00000000-0000-0000-0000-00000000000A","lensIds":[]}]}""",
      s"""{"cameras":[{"cameraId":"${body.value}","lensIds":[null]}]}""",
      s"""{"cameras":[{"cameraId":"${body.value}","lensIds":["bad"]}]}"""
    )
    val refs          = Refs()
    val server        = buildChapterGearApi(refs)
    val duplicate     = Vector.fill(2)(CameraSelection(body, CameraUse.Interchangeable(lenses = NonEmptyVector.of(lens, lens))))
    val duplicateJson =
      s"""{"cameras":[{"cameraId":"${body.value}","lensIds":["${lens.value}","${lens.value}"]},{"cameraId":"${body.value}","lensIds":["${lens.value}","${lens.value}"]}]}"""

    for
      responses <- malformed.traverse(json => send(server, Method.PUT, path = path, body = json))
      before    <- refs.calls.get
      forwarded <- send(server, Method.PUT, path = path, body = duplicateJson)
      after     <- refs.calls.get
    yield
      assert(responses.forall { case (status, text, contentType, _) =>
        val json = parse(text).fold(error => fail(error.message), identity)
        status === Status.BadRequest && contentType.contains(MediaType.application.json) &&
        json.asObject.exists(_.keys.toSet === Set("message")) && json.hcursor.get[String]("message").toOption.exists(_.nonEmpty)
      })
      assertEquals(before, Vector.empty)
      assertEquals(forwarded, (Status.NoContent, "", None, None))
      assertEquals(after, Vector(Call.Replace(album, chapter, selection = duplicate)))

  test("should forward independent album and chapter scopes and reject malformed route identities"):
    val refs         = Refs()
    val server       = buildChapterGearApi(refs, readResult = GearReadResult.NotFound, replaceResult = GearReplaceResult.NotFound)
    val otherAlbum   = AlbumId(value = new UUID(0L, 9L))
    val otherPath    = s"/api/albums/${otherAlbum.value}/chapters/${chapter.value}/gear"
    val malformedIds = Vector("bad", "1-1-1-1-1", "00000000-0000-0000-0000-00000000000A")

    val checkOtherScope = Vector(Method.GET, Method.PUT).traverse_ { method =>
      send(server, method, path = otherPath, body = selectedJson).map(response =>
        assertEquals(response, errorResponse(Status.NotFound, code = "chapter_not_found"))
      )
    }
    val checkMalformedScopes = malformedIds.traverse_ { id =>
      Vector(Method.GET, Method.PUT).traverse_ { method =>
        val chapterPath = s"/api/albums/${album.value}/chapters/$id/gear"
        val albumPath   = s"/api/albums/$id/chapters/${chapter.value}/gear"

        for
          badChapter <- send(server, method, path = chapterPath, body = "{}")
          badAlbum   <- send(server, method, path = albumPath, body = "{}")
        yield
          assertEquals(badChapter, errorResponse(Status.NotFound, code = "chapter_not_found"))
          assertEquals(badAlbum, errorResponse(Status.NotFound, code = "album_not_found"))
      }
    }

    for
      _     <- checkOtherScope
      _     <- checkMalformedScopes
      calls <- refs.calls.get
    yield assertEquals(calls, Vector(Call.Read(otherAlbum, chapter), Call.Replace(otherAlbum, chapter, selection = selection)))

  private enum Call:
    case Read(album: AlbumId, chapter: ChapterId)
    case Replace(album: AlbumId, chapter: ChapterId, selection: GearSelection)

  final private case class Refs():
    val calls: Ref[IO, Vector[Call]] = Ref.unsafe(Vector.empty)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildChapterGearApi(
      refs: Refs,
      readResult: GearReadResult = GearReadResult.Found(selection = Vector.empty[CameraSelection]),
      replaceResult: GearReplaceResult = GearReplaceResult.Saved
  ) =
    val chapters = new ChapterManager[IO]:
      override def readGear(album: AlbumId, chapter: ChapterId): IO[GearReadResult] = refs.calls.update(_ :+ Call.Read(album, chapter)).as(readResult)
      override def replaceGear(album: AlbumId, chapter: ChapterId, selection: GearSelection): IO[GearReplaceResult] =
        refs.calls.update(_ :+ Call.Replace(album, chapter, selection = selection)).as(replaceResult)
    ChapterGearApi.make(chapters).routes.orNotFound
  // scalafix:on DisableSyntax.defaultArgs

  private def send(server: HttpApp[IO], method: Method, path: String, body: String) =
    val request = Request[IO](method, Uri.unsafeFromString(path)).withEntity(body).putHeaders(`Content-Type`(MediaType.application.json))

    for
      response <- server.run(request)
      text     <- response.as[String]
    yield (
      response.status,
      text,
      response.contentType.map(_.mediaType),
      response.headers.get(CIString("Cache-Control")).map(_.head.value)
    )

  private def errorResponse(status: Status, code: String) =
    (status, Json.obj("code" -> Json.fromString(code)).noSpaces, MediaType.application.json.some, None)

  private def explain(reason: GearManager.Failure) = reason match
    case GearManager.Failure.DuplicateCamera    => "Select each camera only once"
    case GearManager.Failure.UnknownCamera      => "Select a camera from the current catalog"
    case GearManager.Failure.CameraKindMismatch => "Select lenses only for interchangeable cameras"
    case GearManager.Failure.DuplicateLens      => "Select each lens only once per camera"
    case GearManager.Failure.UnknownLens        => "Select a lens from the current catalog"
    case GearManager.Failure.IncompatibleLens   => "Select lenses with a compatible mount and coverage"
