package photography.usecases.album

import photography.usecases.photo.ChapterPhotoImport
import photography.usecases.photo.PhotoInventory

import cats.Monad
import cats.data.NonEmptyList
import cats.syntax.all.*
import photography.domain.album.{Album, AlbumId, ChapterId, Country, PhotoSource}
import photography.ports.album.CountryRepository
import photography.usecases.album.AlbumManager.GetAlbumResult
import photography.usecases.album.AlbumWorkspaceManager.*

trait AlbumWorkspaceManager[F[_]]:
  def get(id: AlbumId): F[GetResult]
  def listChapter(chapter: ChapterId): F[ChapterResult]

object AlbumWorkspaceManager:
  def make[F[_]: Monad](
      albums: AlbumManager[F],
      countries: CountryRepository[F],
      photos: PhotoInventory[F],
      imports: ChapterPhotoImport[F],
      photoRoot: PhotoSource
  ): AlbumWorkspaceManager[F] =
    new LiveAlbumWorkspaceManager(albums, countries, photos, imports, photoRoot)

  final private class LiveAlbumWorkspaceManager[F[_]: Monad](
      albums: AlbumManager[F],
      countries: CountryRepository[F],
      photos: PhotoInventory[F],
      imports: ChapterPhotoImport[F],
      photoRoot: PhotoSource
  ) extends AlbumWorkspaceManager[F]:
    override def get(id: AlbumId): F[GetResult] =
      albums.get(id).flatMap:
        case GetAlbumResult.Found(album) =>
          countries.list.flatMap:
            case CountryRepository.ListResult.Listed(catalog) =>
              readInventories(chapters = album.chapters.map(_.id)).map:
                case Some(inventories) => GetResult.Found(album, catalog, photoRoot, photoInventories = inventories)
                case None              => GetResult.Failed
            case CountryRepository.ListResult.Failed => GetResult.Failed.pure[F]
        case GetAlbumResult.NotFound => GetResult.AlbumNotFound.pure[F]
        case GetAlbumResult.Failed   => GetResult.Failed.pure[F]

    override def listChapter(chapter: ChapterId): F[ChapterResult] =
      photos.listChapter(chapter).flatMap:
        case PhotoInventory.ListResult.Listed(inventory) =>
          imports.status(chapter).map(execution => ChapterResult.Found(ChapterPhotos(inventory, execution)))
        case PhotoInventory.ListResult.ChapterNotFound => ChapterResult.ChapterNotFound.pure[F]
        case PhotoInventory.ListResult.Failed          => ChapterResult.Failed.pure[F]

    private def readInventories(chapters: Vector[ChapterId]) =
      chapters.traverse: chapter =>
        listChapter(chapter).map:
          case ChapterResult.Found(snapshot) => (chapter, snapshot).some
          case _                             => None
      .map(_.sequence.map(_.toMap))

  enum GetResult:
    case Found(album: Album, countries: NonEmptyList[Country], photoRoot: PhotoSource, photoInventories: Map[ChapterId, ChapterPhotos])
    case AlbumNotFound, Failed

  enum ChapterResult:
    case Found(photos: ChapterPhotos)
    case ChapterNotFound, Failed

  final case class ChapterPhotos(inventory: PhotoInventory.Inventory, execution: ChapterPhotoImport.ExecutionOutcome)
