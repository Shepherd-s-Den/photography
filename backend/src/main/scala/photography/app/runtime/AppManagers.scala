package photography.app.runtime

import cats.effect.{Async, Resource}
import cats.effect.std.Mutex
import org.typelevel.doobie.{ConnectionIO, Transactor}
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import photography.adapters.sqlite.{SqliteAlbumRepository, SqliteChapterRepository, SqliteCountryRepository, SqliteSpreadTemplateRepository}
import photography.adapters.sqlite.SqlitePhotoPlacementRepository
import photography.adapters.filesystem.FilePhotoStorage
import photography.capabilities.{Clock, IdGenerator, Transaction}
import photography.domain.album.*
import photography.ports.{PhotoRepository, ThumbnailStorage}
import photography.usecases.{AlbumManager, AlbumPageSize, AlbumWorkspaceManager, ChapterManager, ChapterPhotoImport}
import photography.usecases.{AlbumPhotoEvents, ChapterPlanning, PhotoInventory, SpreadTemplateManager}
import photography.adapters.imageio.ImageIoPhotoThumbnailGenerator

final case class AppManagers[F[_]](
    albums: AlbumManager[F],
    chapters: ChapterManager[F],
    templates: SpreadTemplateManager[F],
    workspaces: AlbumWorkspaceManager[F],
    photoEvents: AlbumPhotoEvents[F]
)

object AppManagers:
  def make[F[_]: Async: Logger: Clock: IdGenerator](
      sqlite: Transactor[F],
      pageSize: AlbumPageSize,
      photoRoot: PhotoSource,
      photoRepository: PhotoRepository[ConnectionIO],
      thumbnails: ThumbnailStorage[F],
      quality: ThumbnailQuality
  ): Resource[F, AppManagers[F]] =
    Resource.eval(Mutex[F]).flatMap: mutex =>
      given Mutex[F] = mutex
      given Transaction[ConnectionIO, F] with
        override def run[A](program: ConnectionIO[A]): F[A]     = program.transact(sqlite)
        override def resolve[A](program: ConnectionIO[A]): F[A] = program.transact(sqlite)
      val templateRepository = SqliteSpreadTemplateRepository.make[F](sqlite)
      val templates          = SpreadTemplateManager.make(templateRepository)
      val albumRepository    = SqliteAlbumRepository.make[F](sqlite)
      val chapterRepository  = SqliteChapterRepository.make
      val countries          = SqliteCountryRepository.make[F](sqlite)
      val albums             = AlbumManager.make(albumRepository, templates, pageSize)
      val placements         = SqlitePhotoPlacementRepository.make
      val inventory          = PhotoInventory.make[F, ConnectionIO](photoRepository, placements, chapterRepository)
      val storage            = FilePhotoStorage.make(thumbnails)
      val generator          = ImageIoPhotoThumbnailGenerator.make[F](quality)

      val planning = ChapterPlanning.make(templates)
      Resource.eval(AlbumPhotoEvents.make(albums)).flatMap: events =>
        ChapterPhotoImport.make(storage, photoRepository, generator, events).map: imports =>
          val chapters   = ChapterManager.make(albumRepository, chapterRepository, planning, imports, photoRepository)
          val workspaces = AlbumWorkspaceManager.make(albums, countries, inventory, imports, photoRoot)
          AppManagers(albums, chapters, templates, workspaces, events)
