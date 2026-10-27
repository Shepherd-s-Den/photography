package photography.app.runtime

import cats.effect.{Async, Resource}
import cats.effect.std.Mutex
import cats.syntax.all.*
import org.typelevel.doobie.{ConnectionIO, Transactor}
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import photography.adapters.sqlite.{SqliteAlbumRepository, SqliteChapterRepository, SqliteCountryRepository, SqliteSpreadTemplateRepository}
import photography.capabilities.{Clock, IdGenerator, Transaction}
import photography.domain.album.*
import photography.ports.{PhotoPlacementRepository, PhotoRepository, PhotoStorage}
import photography.usecases.{AlbumManager, AlbumPageSize, AlbumWorkspaceManager, ChapterManager, ChapterPhotoImport}
import photography.usecases.{PhotoInventory, PhotoThumbnailGenerator, SpreadTemplateManager}

final case class AppManagers[F[_]](
    albums: AlbumManager[F],
    chapters: ChapterManager[F],
    templates: SpreadTemplateManager[F],
    workspaces: AlbumWorkspaceManager[F]
)

object AppManagers:
  def make[F[_]: Async: Logger: Clock: IdGenerator](
      sqlite: Transactor[F],
      pageSize: AlbumPageSize,
      photoRoot: PhotoSource
  ): Resource[F, AppManagers[F]] =
    Resource.eval(Mutex[F]).flatMap: mutex =>
      given Mutex[F] = mutex
      given Transaction[ConnectionIO, F] with
        override def run[A](program: ConnectionIO[A]): F[A] = program.transact(sqlite)
      val templateRepository = SqliteSpreadTemplateRepository.make[F](sqlite)
      val templates          = SpreadTemplateManager.make(templateRepository)
      val albumRepository    = SqliteAlbumRepository.make[F](sqlite)
      val chapterRepository  = SqliteChapterRepository.make[F](sqlite)
      val countries          = SqliteCountryRepository.make[F](sqlite)
      val albums             = AlbumManager.make(albumRepository, templates, pageSize)
      val photoRepository    = buildPhotoRepository
      val placements         = buildPhotoPlacements
      val inventory          = PhotoInventory.make[F, ConnectionIO](photoRepository, placements)
      val storage            = buildPhotoStorage[F]
      val generator          = buildThumbnailGenerator[F]
      ChapterPhotoImport.make(storage, photoRepository, generator).map: imports =>
        val chapters   = ChapterManager.make(albumRepository, chapterRepository, templates, imports)
        val workspaces = AlbumWorkspaceManager.make(albums, countries, inventory, photoRoot)
        AppManagers(albums, chapters, templates, workspaces)

  private def buildPhotoRepository =
    new PhotoRepository[ConnectionIO]:
      override def listChapter(chapter: ChapterId): ConnectionIO[PhotoRepository.ListResult] =
        PhotoRepository.ListResult.Listed(photos = Vector.empty[Photo]).pure[ConnectionIO]
      override def save(photo: Photo): ConnectionIO[PhotoRepository.WriteResult]     = PhotoRepository.WriteResult.Failed.pure[ConnectionIO]
      override def remove(photo: PhotoId): ConnectionIO[PhotoRepository.WriteResult] = PhotoRepository.WriteResult.Failed.pure[ConnectionIO]

  private def buildPhotoPlacements =
    new PhotoPlacementRepository[ConnectionIO]:
      override def findPlacedPhotos(chapter: ChapterId): ConnectionIO[PhotoPlacementRepository.FindResult] =
        PhotoPlacementRepository.FindResult.Found(photos = Set.empty[PhotoId]).pure[ConnectionIO]

  private def buildPhotoStorage[F[_]: Async] =
    new PhotoStorage[F]:
      override def discoverOriginals(source: PhotoSource): F[PhotoStorage.DiscoveryResult]   = PhotoStorage.DiscoveryResult.Failed.pure[F]
      override def readOriginal(location: OriginalPhotoLocation): F[PhotoStorage.ReadResult] = PhotoStorage.ReadResult.Failed.pure[F]
      override def storeThumbnail(location: ThumbnailLocation, image: EncodedThumbnail): F[PhotoStorage.StoreResult] =
        PhotoStorage.StoreResult.Failed.pure[F]
      override def removeThumbnail(location: ThumbnailLocation): F[PhotoStorage.RemoveResult] = PhotoStorage.RemoveResult.Failed.pure[F]

  private def buildThumbnailGenerator[F[_]: Async] =
    new PhotoThumbnailGenerator[F]:
      override def quality: ThumbnailQuality                                                    = ThumbnailQuality.Standard
      override def generate(content: PhotoContent): F[PhotoThumbnailGenerator.GenerationResult] =
        PhotoThumbnailGenerator.GenerationResult.Failed.pure[F]
