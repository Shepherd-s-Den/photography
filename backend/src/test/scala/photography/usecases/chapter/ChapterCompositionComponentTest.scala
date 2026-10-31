package photography.usecases.chapter

import cats.data.NonEmptyList
import cats.effect.{IO, Ref}
import cats.effect.std.Mutex
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.capabilities.Transaction
import photography.domain.album.{AlbumId, ChapterId}
import photography.domain.layout.{Spread, SpreadId}
import photography.ports.album.AlbumCompositionRepository
import photography.ports.spread.SpreadRepository
import java.util.UUID

final class ChapterCompositionComponentTest extends CatsEffectSuite:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private given Transaction[IO, IO] with
    override def run[A](program: IO[A]): IO[A]     = program
    override def resolve[A](program: IO[A]): IO[A] = program
  private val album     = AlbumId(new UUID(0L, 1L))
  private val chapter   = ChapterId(new UUID(0L, 2L))
  private val opening   = SpreadId(new UUID(0L, 3L))
  private val first     = SpreadId(new UUID(0L, 4L))
  private val second    = SpreadId(new UUID(0L, 5L))
  private val original  = NonEmptyList.of(opening, first, second)
  private val reordered = NonEmptyList.of(opening, second, first)

  test("should replace a complete chapter order atomically and reject a stale concurrent replacement"):
    for
      mutex  <- Mutex[IO]
      state  <- Ref.of[IO, Option[NonEmptyList[SpreadId]]](original.some)
      writes <- Ref.of[IO, Vector[NonEmptyList[SpreadId]]](Vector.empty)
      manager = buildManager(state, writes, mutex, failWrite = false)
      saved  <- manager.replace(album, chapter, original, reordered)
      stale  <- manager.replace(album, chapter, original, reordered)
      stored <- state.get
      seen   <- writes.get
    yield
      assertEquals(saved, ChapterComposition.Result.Saved)
      assertEquals(stale, ChapterComposition.Result.Stale)
      assertEquals(stored, reordered.some)
      assertEquals(seen, Vector(reordered))

  test("should preserve openings, membership and unique identities before touching storage"):
    Vector(NonEmptyList.of(first, opening, second), NonEmptyList.of(opening, first, first), NonEmptyList.of(opening, first)).traverse_ { order =>
      for
        mutex  <- Mutex[IO]
        state  <- Ref.of[IO, Option[NonEmptyList[SpreadId]]](original.some)
        writes <- Ref.of[IO, Vector[NonEmptyList[SpreadId]]](Vector.empty)
        result <- buildManager(state, writes, mutex, failWrite = false).replace(album, chapter, original, order)
        seen   <- writes.get
      yield
        assertEquals(result, ChapterComposition.Result.Invalid)
        assertEquals(seen, Vector.empty)
    }

  test("should retain absence and uncertain acknowledgement without retrying the write"):
    Vector(false, true).traverse_ { failed =>
      for
        mutex  <- Mutex[IO]
        state  <- Ref.of[IO, Option[NonEmptyList[SpreadId]]](if failed then original.some else None)
        writes <- Ref.of[IO, Vector[NonEmptyList[SpreadId]]](Vector.empty)
        result <- buildManager(state, writes, mutex, failWrite = failed).replace(album, chapter, original, reordered)
        seen   <- writes.get
      yield
        assertEquals(result, if failed then ChapterComposition.Result.Uncertain else ChapterComposition.Result.NotFound)
        assertEquals(seen.size, if failed then 1 else 0)
    }

  private def buildManager(
      state: Ref[IO, Option[NonEmptyList[SpreadId]]],
      writes: Ref[IO, Vector[NonEmptyList[SpreadId]]],
      mutex: Mutex[IO],
      failWrite: Boolean
  ) =
    given Mutex[IO] = mutex
    val composition = new AlbumCompositionRepository[IO]:
      override def hasAlbum(album: AlbumId): IO[Boolean]         = IO.raiseError[Boolean](new AssertionError("Unexpected album read"))
      override def countInteriorSpreads(album: AlbumId): IO[Int] = IO.raiseError[Int](new AssertionError("Unexpected count"))
      override def findSpread(album: AlbumId, spread: SpreadId): IO[Option[AlbumCompositionRepository.Located]] =
        IO.raiseError[Option[AlbumCompositionRepository.Located]](new AssertionError("Unexpected spread read"))
      override def findChapterOrder(id: AlbumId, target: ChapterId): IO[Option[NonEmptyList[SpreadId]]] =
        IO(assertEquals((id, target), (album, chapter))) *> state.get
    val spreads = new SpreadRepository[IO]:
      override def insert(chapter: ChapterId, spread: Spread.PhotosOnly): IO[Unit] = IO.raiseError[Unit](new AssertionError("Unexpected insert"))
      override def replace(spread: Spread): IO[Unit]  = IO.raiseError[Unit](new AssertionError("Unexpected template replacement"))
      override def remove(spread: SpreadId): IO[Unit] = IO.raiseError[Unit](new AssertionError("Unexpected removal"))
      override def reorder(target: ChapterId, order: NonEmptyList[SpreadId]): IO[Unit] =
        writes.update(_ :+ order) *> (if failWrite then IO.raiseError[Unit](new IllegalStateException("Unknown commit")) else state.set(order.some))
    ChapterComposition.make[IO, IO](composition, spreads)
