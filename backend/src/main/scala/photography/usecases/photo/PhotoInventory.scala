package photography.usecases.photo

import cats.{Monad, MonadThrow}
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import photography.capabilities.Transaction
import photography.domain.album.*
import photography.ports.chapter.ChapterRepository
import photography.ports.photo.{PhotoPlacementRepository, PhotoRepository}
import photography.usecases.photo.PhotoInventory.*
import java.time.LocalDateTime
import java.util.Locale

trait PhotoInventory[F[_]]:
  def listChapter(chapter: ChapterId): F[ListResult]

object PhotoInventory:
  def make[F[_]: MonadThrow: Logger, Db[_]: Monad](
      photos: PhotoRepository[Db],
      placements: PhotoPlacementRepository[Db],
      chapters: ChapterRepository.Metadata[Db]
  )(using Transaction[Db, F]): PhotoInventory[F] = new LivePhotoInventory(photos, placements, chapters)

  final private class LivePhotoInventory[F[_]: MonadThrow: Logger, Db[_]: Monad](
      photos: PhotoRepository[Db],
      placements: PhotoPlacementRepository[Db],
      chapters: ChapterRepository.Metadata[Db]
  )(using Transaction[Db, F]) extends PhotoInventory[F]:
    override def listChapter(chapter: ChapterId): F[ListResult] =
      val snapshot = (photos.listChapter(chapter), placements.findPlacedPhotos(chapter)).mapN:
        case (PhotoRepository.ListResult.Listed(values), PhotoPlacementRepository.FindResult.Found(placed)) =>
          ListResult.Listed(Inventory(photos = values.sortBy(deriveOrder), placed = placed))
        case _ => ListResult.Failed
      val read = chapters.findSource(chapter).flatMap:
        case _: ChapterRepository.SourceResult.Found        => snapshot
        case ChapterRepository.SourceResult.ChapterNotFound => ListResult.ChapterNotFound.pure[Db]
        case ChapterRepository.SourceResult.Failed          => ListResult.Failed.pure[Db]
      Transaction[Db, F].run(read).handleErrorWith(error => Logger[F].error(error)("Photo inventory read failed").as(ListResult.Failed))

    private def deriveOrder(photo: Photo) =
      val filename: String = photo.original.filename
      (
        photo.metadata.maybeCapturedLocal.isEmpty,
        photo.metadata.maybeCapturedLocal.getOrElse(LocalDateTime.MIN),
        filename.toLowerCase(Locale.ROOT),
        filename,
        photo.chapter.value.toString
      )

  enum ListResult:
    case Listed(inventory: Inventory)
    case ChapterNotFound, Failed

  final case class Inventory(photos: Vector[Photo], placed: Set[PhotoId]):
    def total: Int         = photos.size
    def countUnplaced: Int = photos.count(photo => !placed.contains(photo.id))
