package photography.adapters.http

import photography.domain.album.{AlbumAuthors, AlbumTitle, AlbumYear}
import photography.usecases.album.{AlbumManager, AlbumWorkspaceManager}

import cats.effect.{IO, Ref, Resource}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite
import org.http4s.{MediaType, Request, ServerSentEvent, Status, Uri}
import org.typelevel.ci.CIString
import photography.domain.album.{AlbumId, ChapterId}
import photography.usecases.photo.AlbumPhotoEvents
import photography.usecases.photo.AlbumPhotoEvents.{Event, SubscribeResult}
import java.util.UUID

final class AlbumApiEventsSeamIntegrationTest extends CatsEffectSuite:
  private val album   = AlbumId(new UUID(0L, 1L))
  private val chapter = ChapterId(new UUID(0L, 2L))
  private val uri     = Uri.unsafeFromString(s"/api/albums/${album.value}/photo-events")

  test("should encode exact events, heartbeat and uncached unbuffered headers and release the stream on disconnect"):
    val program =
      for
        released <- Ref.of[IO, Boolean](false)
        lifecycle = Resource.make(IO.unit)(_ => released.set(true))
        events    = Stream.resource(lifecycle) >> (Stream.emits(Vector(Event.Subscribed, Event.InventoryChanged(chapter))) ++ Stream.never[IO])
        service   = buildEvents(SubscribeResult.Opened(events))
        response <- AlbumApi.make(buildUnusedAlbums, buildUnusedWorkspaces, service).routes.orNotFound.run(Request[IO](uri = uri))
        received <- response.body.through(ServerSentEvent.decoder[IO]).take(3).compile.toVector
        closed   <- released.get
      yield
        assertEquals(response.status, Status.Ok)
        assertEquals(response.contentType.map(_.mediaType), MediaType.`text/event-stream`.some)
        assertEquals(response.headers.get(CIString("Cache-Control")).map(_.head.value), "no-store".some)
        assertEquals(response.headers.get(CIString("X-Accel-Buffering")).map(_.head.value), "no".some)
        assertEquals(received.flatMap(_.eventType), Vector("subscribed", "inventory-changed"))
        assertEquals(received.flatMap(_.data), Vector("{}", s"""{"chapterId":"${chapter.value}"}"""))
        assert(received.exists(_.comment.nonEmpty))
        assert(closed)

    TestControl.executeEmbed(program)

  test("should reject invalid albums and failed validation before opening a stream"):
    Vector(
      SubscribeResult.AlbumNotFound[IO]() -> Status.NotFound,
      SubscribeResult.Failed[IO]()        -> Status.InternalServerError
    ).traverse_ { case (result, expected) =>
      val app = AlbumApi.make(buildUnusedAlbums, buildUnusedWorkspaces, buildEvents(result)).routes.orNotFound

      for
        response  <- app.run(Request[IO](uri = uri))
        body      <- response.as[String]
        malformed <- app.run(Request[IO](uri = Uri.unsafeFromString("/api/albums/not-an-id/photo-events")))
      yield
        assertEquals(response.status, expected)
        assertEquals(body, if expected === Status.NotFound then """{"code":"album_not_found"}""" else """{"code":"service_failure"}""")
        assertEquals(malformed.status, Status.NotFound)
    }

  private def buildEvents(result: SubscribeResult[IO]) =
    new AlbumPhotoEvents[IO]:
      override def subscribe(id: AlbumId): IO[SubscribeResult[IO]]       = IO(assertEquals(id, album)).as(result)
      override def invalidate(id: AlbumId, chapter: ChapterId): IO[Unit] = IO.raiseError[Unit](new AssertionError("Unexpected invalidation"))

  private def buildUnusedAlbums =
    new AlbumManager[IO]:
      override def saveSynopsis(id: AlbumId, text: String): IO[AlbumManager.SaveSynopsisResult] =
        IO.raiseError[AlbumManager.SaveSynopsisResult](new AssertionError("Unexpected synopsis mutation"))
      override def saveAuthors(id: AlbumId, authors: AlbumAuthors): IO[AlbumManager.SaveAuthorsResult] =
        IO.raiseError[AlbumManager.SaveAuthorsResult](new AssertionError("Unexpected author mutation"))
      override def get(id: AlbumId): IO[AlbumManager.GetAlbumResult] =
        IO.raiseError[AlbumManager.GetAlbumResult](new AssertionError("Unexpected album read"))
      override def list(page: Int): IO[AlbumManager.ListAlbumsResult] =
        IO.raiseError[AlbumManager.ListAlbumsResult](new AssertionError("Unexpected album list"))
      override def create(year: AlbumYear, title: AlbumTitle): IO[AlbumManager.CreateAlbumResult] =
        IO.raiseError[AlbumManager.CreateAlbumResult](new AssertionError("Unexpected album creation"))
      override def rename(id: AlbumId, title: AlbumTitle): IO[AlbumManager.RenameResult] =
        IO.raiseError[AlbumManager.RenameResult](new AssertionError("Unexpected album rename"))
      override def delete(id: AlbumId): IO[AlbumManager.DeleteAlbumResult] =
        IO.raiseError[AlbumManager.DeleteAlbumResult](new AssertionError("Unexpected album deletion"))

  private def buildUnusedWorkspaces =
    new AlbumWorkspaceManager[IO]:
      override def get(id: AlbumId): IO[AlbumWorkspaceManager.GetResult] =
        IO.raiseError[AlbumWorkspaceManager.GetResult](new AssertionError("Unexpected workspace read"))
      override def listChapter(id: ChapterId): IO[AlbumWorkspaceManager.ChapterResult] =
        IO.raiseError[AlbumWorkspaceManager.ChapterResult](new AssertionError("Unexpected photo inventory read"))
