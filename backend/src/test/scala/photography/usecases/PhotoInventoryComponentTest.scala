package photography.usecases

import cats.data.StateT
import cats.effect.IO
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.capabilities.Transaction
import photography.domain.album.*
import photography.ports.{PhotoPlacementRepository, PhotoRepository}
import java.time.LocalDateTime
import java.util.UUID

final class PhotoInventoryComponentTest extends CatsEffectSuite:
  private type Db[A] = StateT[IO, Int, A]
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private val chapter      = ChapterId(new UUID(0L, 1L))
  private val metadata     = PhotoMetadata(hash = "hash", maybeCapturedLocal = None, PhotoDimensions(width = 10, height = 10))
  private val thumbnail    =
    Thumbnail(ThumbnailLocation(new UUID(0L, 2L)), version = "v", ThumbnailDimensions(width = 5, height = 5), ThumbnailQuality.Low, bytes = 1)
  private val photo =
    Photo(PhotoId(new UUID(0L, 3L)), chapter, OriginalPhotoLocation(source = "/photos", filename = "z.jpg"), metadata, thumbnail, maybeCaption = None)

  test("should read ordered photos and placements in one type-separated database snapshot"):
    val dated     = photo.copy(id = PhotoId(new UUID(0L, 4L)), metadata = metadata.copy(maybeCapturedLocal = LocalDateTime.of(2025, 1, 1, 0, 0).some))
    val alpha     = photo.copy(id = PhotoId(new UUID(0L, 5L)), original = photo.original.copy(filename = "a.jpg"))
    val upper     = photo.copy(id = PhotoId(new UUID(0L, 6L)), original = photo.original.copy(filename = "A.jpg"))
    val inventory = PhotoInventory.Inventory(photos = Vector(dated, upper, alpha, photo), placed = Set(photo.id))
    val listed    = PhotoRepository.ListResult.Listed(photos = Vector(photo, alpha, dated, upper))
    val placed    = PhotoPlacementRepository.FindResult.Found(photos = Set(photo.id))
    val manager   = buildInventory(listed, placed)

    manager.listChapter(chapter).map: actual =>
      assertEquals(actual, PhotoInventory.ListResult.Listed(inventory))
      assertEquals(inventory.total, 4)
      assertEquals(inventory.countUnplaced, 3)

  test("should report either inventory boundary failure"):
    Vector(
      (PhotoRepository.ListResult.Failed, PhotoPlacementRepository.FindResult.Found(Set.empty[PhotoId])),
      (PhotoRepository.ListResult.Listed(Vector.empty[Photo]), PhotoPlacementRepository.FindResult.Failed)
    ).traverse_ { case (photos, placed) =>
      buildInventory(photos, placed).listChapter(chapter).map(actual => assertEquals(actual, PhotoInventory.ListResult.Failed))
    }

  test("should report a failed transaction as inventory failure"):
    val manager = buildInventory(
      PhotoRepository.ListResult.Listed(Vector(photo)),
      PhotoPlacementRepository.FindResult.Found(Set.empty[PhotoId]),
      maybeTransactionError = new RuntimeException("transaction failed").some
    )
    manager.listChapter(chapter).map(actual => assertEquals(actual, PhotoInventory.ListResult.Failed))

  // scalafix:off DisableSyntax.defaultArgs
  private def buildInventory(
      listed: PhotoRepository.ListResult,
      placed: PhotoPlacementRepository.FindResult,
      maybeTransactionError: Option[Throwable] = None
  ) =
    given Transaction[Db, IO] with
      override def run[A](program: Db[A]): IO[A] =
        maybeTransactionError.fold(program.run(0).flatMap { case (steps, result) => IO(assertEquals(steps, 2)).as(result) })(IO.raiseError[A])
      override def resolve[A](program: Db[A]): IO[A] = program.runA(0)
    val photos = new PhotoRepository[Db]:
      override def listChapter(id: ChapterId): Db[PhotoRepository.ListResult] =
        StateT.modify[IO, Int](_ + 1).as(listed)
      override def save(value: Photo): Db[PhotoRepository.WriteResult] =
        StateT.liftF(IO.raiseError[PhotoRepository.WriteResult](new RuntimeException("unexpected write")))
      override def remove(value: PhotoId): Db[PhotoRepository.WriteResult] =
        StateT.liftF(IO.raiseError[PhotoRepository.WriteResult](new RuntimeException("unexpected removal")))
      override def recordWrite(intent: PhotoWriteIntent): Db[PhotoRepository.RecordWriteResult] =
        StateT.liftF(IO.raiseError[PhotoRepository.RecordWriteResult](new AssertionError("Unexpected evidence creation")))
      override def completeWrite(id: PhotoWriteIntentId): Db[PhotoRepository.CompleteWriteResult] =
        StateT.liftF(IO.raiseError[PhotoRepository.CompleteWriteResult](new AssertionError("Unexpected evidence completion")))
      override def listUnfinishedWrites: Db[PhotoRepository.ListUnfinishedWritesResult] =
        StateT.liftF(IO.raiseError[PhotoRepository.ListUnfinishedWritesResult](new AssertionError("Unexpected evidence listing")))
    val placements = new PhotoPlacementRepository[Db]:
      override def findPlacedPhotos(id: ChapterId): Db[PhotoPlacementRepository.FindResult] =
        StateT.modify[IO, Int](_ + 1).as(placed)
    PhotoInventory.make[IO, Db](photos, placements)
  // scalafix:on DisableSyntax.defaultArgs
