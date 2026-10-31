package photography.usecases.gear

import cats.effect.kernel.MonadCancelThrow
import cats.effect.std.Mutex
import cats.syntax.all.*
import photography.domain.album.{AlbumId, ChapterId}
import photography.domain.gear.{GearCatalog, GearSelection}
import photography.ports.gear.{ChapterGearRepository, GearCatalogRepository}
import photography.usecases.gear.ChapterGearManager.*

trait ChapterGearManager[F[_]]:
  def readCatalog: F[CatalogResult]
  def read(album: AlbumId, chapter: ChapterId): F[ReadResult]
  def replace(album: AlbumId, chapter: ChapterId, selection: GearSelection): F[ReplaceResult]

object ChapterGearManager:
  def make[F[_]: MonadCancelThrow: Mutex](
      catalog: GearCatalogRepository[F],
      repository: ChapterGearRepository[F],
      compatibility: GearCompatibility
  ): ChapterGearManager[F] = new LiveChapterGearManager(catalog, repository, compatibility)

  final private class LiveChapterGearManager[F[_]: MonadCancelThrow: Mutex](
      catalog: GearCatalogRepository[F],
      repository: ChapterGearRepository[F],
      compatibility: GearCompatibility
  ) extends ChapterGearManager[F]:
    override def readCatalog: F[CatalogResult] =
      catalog.read.map:
        case GearCatalogRepository.ReadResult.Found(current) => CatalogResult.Found(current)
        case GearCatalogRepository.ReadResult.Failed         => CatalogResult.Failed

    override def read(album: AlbumId, chapter: ChapterId): F[ReadResult] =
      repository.read(album, chapter).map:
        case ChapterGearRepository.ReadResult.Found(selection) => ReadResult.Found(selection = selection)
        case ChapterGearRepository.ReadResult.NotFound         => ReadResult.NotFound
        case ChapterGearRepository.ReadResult.Failed           => ReadResult.Failed

    override def replace(album: AlbumId, chapter: ChapterId, selection: GearSelection): F[ReplaceResult] =
      MonadCancelThrow[F].uncancelable: _ =>
        summon[Mutex[F]].lock.surround:
          catalog.read.flatMap:
            case GearCatalogRepository.ReadResult.Failed         => ReplaceResult.Failed.pure[F]
            case GearCatalogRepository.ReadResult.Found(current) =>
              compatibility.validate(current, selection = selection) match
                case GearCompatibility.ValidationResult.Rejected(reason) => ReplaceResult.Rejected(reason).pure[F]
                case GearCompatibility.ValidationResult.Accepted         =>
                  repository.replace(album, chapter, selection = selection).map:
                    case ChapterGearRepository.WriteResult.Saved     => ReplaceResult.Saved
                    case ChapterGearRepository.WriteResult.NotFound  => ReplaceResult.NotFound
                    case ChapterGearRepository.WriteResult.Failed    => ReplaceResult.Failed
                    case ChapterGearRepository.WriteResult.Uncertain => ReplaceResult.Uncertain

  enum CatalogResult:
    case Found(catalog: GearCatalog)
    case Failed

  enum ReadResult:
    case Found(selection: GearSelection)
    case NotFound, Failed

  enum ReplaceResult:
    case Saved, NotFound, Failed, Uncertain
    case Rejected(reason: GearCompatibility.Failure)
