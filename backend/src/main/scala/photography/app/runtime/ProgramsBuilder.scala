package photography.app.runtime

import cats.effect.{Async, Resource}
import cats.effect.std.Mutex
import org.typelevel.doobie.{ConnectionIO, Transactor}
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.adapters.sqlite.album.{SqliteAlbumRepository, SqliteCountryRepository}
import photography.adapters.sqlite.chapter.SqliteChapterRepository
import photography.adapters.sqlite.gear.SqliteGearRepository
import photography.adapters.sqlite.spread.SqliteSpreadTemplateRepository
import photography.adapters.sqlite.photo.SqlitePhotoPlacementRepository
import photography.adapters.sqlite.cover.SqliteCoverRepository
import photography.adapters.filesystem.FilePhotoStorage
import photography.capabilities.{Clock, IdGenerator, Transaction}
import photography.adapters.sqlite.album.SqliteAlbumCompositionRepository
import photography.adapters.sqlite.spread.SqliteSpreadRepository
import photography.domain.album.*
import photography.domain.layout.SpreadTemplateId
import java.util.UUID
import photography.ports.photo.{PhotoRepository, ThumbnailStorage}
import photography.usecases.album.{AlbumManager, AlbumWorkspaceManager}
import photography.app.config.AlbumsConfig
import photography.usecases.chapter.{ChapterManager, ChapterMetadataManager}
import photography.usecases.gear.GearManager
import photography.usecases.photo.ChapterPhotoImport
import photography.usecases.photo.{AlbumPhotoEvents, PhotoInventory}
import photography.usecases.chapter.ChapterPlanning
import photography.usecases.spread.SpreadTemplateManager
import photography.usecases.spread.SpreadManager
import photography.usecases.cover.CoverManager
import photography.adapters.imageio.ImageIoPhotoThumbnailGenerator

final case class Programs[F[_]](
    albums: AlbumManager[F],
    chapters: ChapterMetadataManager[F],
    templates: SpreadTemplateManager[F],
    workspaces: AlbumWorkspaceManager[F],
    photoEvents: AlbumPhotoEvents[F],
    spreads: SpreadManager[F],
    coverPlacements: CoverManager[F],
    gear: GearManager[F],
    chapterGear: ChapterManager[F]
)

trait ProgramsBuilder[F[_]]:
  def build: Resource[F, Programs[F]]

object ProgramsBuilder:
  def make[F[_]: Async: Logger: Clock: IdGenerator](
      sqlite: Transactor[F],
      albums: AlbumsConfig,
      photoRepository: PhotoRepository[ConnectionIO],
      thumbnails: ThumbnailStorage[F],
      quality: ThumbnailQuality
  ): ProgramsBuilder[F] = new LiveProgramsBuilder(sqlite, albums, photoRepository, thumbnails, quality)

  final private class LiveProgramsBuilder[F[_]: Async: Logger: Clock: IdGenerator](
      sqlite: Transactor[F],
      albumSettings: AlbumsConfig,
      photoRepository: PhotoRepository[ConnectionIO],
      thumbnails: ThumbnailStorage[F],
      quality: ThumbnailQuality
  ) extends ProgramsBuilder[F]:
    override def build: Resource[F, Programs[F]] =
      Resource.eval(Mutex[F]).flatMap: mutex =>
        given Mutex[F]             = mutex
        given Logger[ConnectionIO] = Slf4jLogger.getLogger[ConnectionIO]
        given Transaction[ConnectionIO, F] with
          override def run[A](program: ConnectionIO[A]): F[A]     = program.transact(sqlite)
          override def resolve[A](program: ConnectionIO[A]): F[A] = program.transact(sqlite)
        val templateRepository = SqliteSpreadTemplateRepository.make[F](sqlite)
        val templates          = SpreadTemplateManager.make(templateRepository)
        val albumComposition   = SqliteAlbumCompositionRepository.make
        val chapterRepository  = SqliteChapterRepository.make
        val gearRepository     = SqliteGearRepository.make[F](sqlite)
        val gear               = GearManager.make(gearRepository, chapterRepository)
        val chapterGear        = ChapterManager.make(chapterRepository, gear)
        val albumRepository    = SqliteAlbumRepository.make[F](sqlite, albumComposition, chapterRepository)
        val countries          = SqliteCountryRepository.make[F](sqlite)
        val albums             = AlbumManager.make(albumRepository, templates, albumSettings.pageSize)
        val placements         = SqlitePhotoPlacementRepository.make
        val inventory          = PhotoInventory.make[F, ConnectionIO](photoRepository, placements, chapterRepository)
        val storage            = FilePhotoStorage.make(thumbnails)
        val generator          = ImageIoPhotoThumbnailGenerator.make[F](quality)

        val planning = ChapterPlanning.make(templates)
        Resource.eval(AlbumPhotoEvents.make(albums)).flatMap: events =>
          ChapterPhotoImport.make(storage, photoRepository, generator, events).map: imports =>
            val chapters         = ChapterMetadataManager.make(albumRepository, chapterRepository, planning, imports, photoRepository)
            val workspaces       = AlbumWorkspaceManager.make(albums, countries, inventory, imports, albumSettings.photoRoot)
            val spreadRepository = SqliteSpreadRepository.make
            val spreads          = SpreadManager.make(templates, albumComposition, spreadRepository, placements, defaultPhotoLayout)
            val coverRepository  = SqliteCoverRepository.make
            val coverPlacements  = CoverManager.make(coverRepository, placements)
            Programs(albums, chapters, templates, workspaces, events, spreads, coverPlacements, gear, chapterGear)

    private val defaultPhotoLayout = SpreadTemplateId(value = UUID.fromString("00000000-0000-0000-0000-000000000113"))
