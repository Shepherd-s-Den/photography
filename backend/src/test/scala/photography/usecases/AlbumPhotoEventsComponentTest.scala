package photography.usecases

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.capabilities.IdGenerator
import photography.domain.album.*
import photography.domain.layout.*
import photography.usecases.AlbumManager.*
import photography.usecases.AlbumPhotoEvents.{Event, SubscribeResult}
import java.util.UUID
import java.time.Instant
import io.github.iltotore.iron.autoRefine

final class AlbumPhotoEventsComponentTest extends CatsEffectSuite:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private given IdGenerator[IO] with
    private val next                  = Ref.unsafe[IO, Long](100L)
    override def generateId: IO[UUID] = next.getAndUpdate(_ + 1).map(value => new UUID(0L, value))
  private val album   = AlbumId(new UUID(0L, 1L))
  private val chapter = ChapterId(new UUID(0L, 2L))
  private val other   = ChapterId(new UUID(0L, 3L))
  private val saved   =
    val text    = SpreadSlot.Text(TextSpreadSlotId(new UUID(0L, 4L)), SlotBox(x = 0.0, y = 0.0, width = 1.0, height = 1.0))
    val opening = Spread.AlbumStart(
      SpreadId(new UUID(0L, 5L)),
      SpreadTemplateId(new UUID(0L, 6L)),
      photoSlots = Vector.empty[SpreadSlot.Photo],
      text,
      placements = Vector.empty[SpreadPlacement]
    )
    Album(album, year = 2026, title = "Delivery", synopsis = "", Instant.EPOCH, opening, chapters = Vector.empty[Chapter])

  test("should validate albums before creating streams and expose lookup failures"):
    Vector(GetAlbumResult.NotFound -> SubscribeResult.AlbumNotFound[IO](), GetAlbumResult.Failed -> SubscribeResult.Failed[IO]()).traverse_ {
      case (result, expected) =>
        for
          events     <- AlbumPhotoEvents.make(buildAlbums(result.pure[IO]))
          subscribed <- events.subscribe(album)
        yield assertEquals(subscribed, expected)
    } >> AlbumPhotoEvents.make(buildAlbums(IO.raiseError[GetAlbumResult](new IllegalStateException("lookup")))).flatMap(_.subscribe(album))
      .map(result => assertEquals(result, SubscribeResult.Failed[IO]()))

  test("should register before acknowledgement, coalesce chapter changes, isolate albums and release canceled subscribers"):
    for
      events       <- AlbumPhotoEvents.make(buildAlbums(GetAlbumResult.Found(saved).pure[IO]))
      stream       <- events.subscribe(album)
      received     <- Queue.unbounded[IO, Event]
      acknowledged <- Deferred[IO, Unit]
      release      <- Deferred[IO, Unit]
      worker       <- readEvents(stream).evalMap { event =>
        received.offer(event) >>
          (event match
            case Event.Subscribed => acknowledged.complete(()).void >> release.get
            case _                => IO.unit)
      }.compile.drain.start
      _       <- acknowledged.get
      _       <- (1 to 100).toVector.traverse_(_ => events.invalidate(album, chapter))
      _       <- events.invalidate(album, other)
      _       <- events.invalidate(AlbumId(new UUID(0L, 9L)), ChapterId(new UUID(0L, 10L)))
      _       <- release.complete(())
      first   <- received.take
      pending <- Vector.fill(2)(()).traverse(_ => received.take)
      _       <- worker.cancel
      _       <- events.invalidate(album, chapter)
      extra   <- received.tryTake
    yield
      assertEquals(first, Event.Subscribed)
      assertEquals(pending.toSet, Set(Event.InventoryChanged(chapter), Event.InventoryChanged(other)))
      assertEquals(extra, None)

  test("should keep a fast subscriber responsive while another is blocked and after it disconnects"):
    for
      events          <- AlbumPhotoEvents.make(buildAlbums(GetAlbumResult.Found(saved).pure[IO]))
      slow            <- events.subscribe(album)
      fast            <- events.subscribe(album)
      blocked         <- Deferred[IO, Unit]
      ready           <- Deferred[IO, Unit]
      received        <- Queue.unbounded[IO, Event]
      stalled         <- readEvents(slow).evalMap(_ => blocked.complete(()).void >> IO.never[Unit]).compile.drain.start
      worker          <- readEvents(fast).evalMap(event => received.offer(event) >> ready.complete(()).void).compile.drain.start
      _               <- blocked.get
      _               <- ready.get
      _               <- events.invalidate(album, chapter)
      acknowledgement <- received.take
      first           <- received.take
      _               <- stalled.cancel
      _               <- events.invalidate(album, other)
      second          <- received.take
      _               <- worker.cancel
    yield
      assertEquals(acknowledgement, Event.Subscribed)
      assertEquals(first, Event.InventoryChanged(chapter))
      assertEquals(second, Event.InventoryChanged(other))

  private def readEvents(result: SubscribeResult[IO]) = result match
    case SubscribeResult.Opened(events) => events
    case _                              => Stream.raiseError[IO](new AssertionError("Expected subscription"))

  private def buildAlbums(response: IO[GetAlbumResult]) =
    new AlbumManager[IO]:
      override def get(id: AlbumId): IO[GetAlbumResult]                              = response
      override def list(page: Int): IO[ListAlbumsResult]                             = ListAlbumsResult.Failed.pure[IO]
      override def create(year: AlbumYear, title: AlbumTitle): IO[CreateAlbumResult] = CreateAlbumResult.Failed.pure[IO]
      override def rename(id: AlbumId, title: AlbumTitle): IO[RenameResult]          = RenameResult.Failed.pure[IO]
      override def delete(id: AlbumId): IO[DeleteAlbumResult]                        = DeleteAlbumResult.Failed.pure[IO]
