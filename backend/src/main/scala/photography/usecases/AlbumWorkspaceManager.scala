package photography.usecases

import cats.Monad
import cats.data.NonEmptyList
import cats.syntax.all.*
import photography.domain.album.{Album, AlbumId, ChapterId, Country, PhotoSource}
import photography.ports.CountryRepository
import photography.usecases.AlbumManager.GetAlbumResult
import photography.usecases.AlbumWorkspaceManager.*

trait AlbumWorkspaceManager[F[_]]:
  def get(id: AlbumId): F[GetResult]

object AlbumWorkspaceManager:
  def make[F[_]: Monad](
      albums: AlbumManager[F],
      countries: CountryRepository[F],
      photos: PhotoInventory[F],
      photoRoot: PhotoSource
  ): AlbumWorkspaceManager[F] =
    new LiveAlbumWorkspaceManager(albums, countries, photos, photoRoot)

  final private class LiveAlbumWorkspaceManager[F[_]: Monad](
      albums: AlbumManager[F],
      countries: CountryRepository[F],
      photos: PhotoInventory[F],
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

    private def readInventories(chapters: Vector[ChapterId]) =
      chapters.traverse: chapter =>
        photos.listChapter(chapter).map:
          case PhotoInventory.ListResult.Listed(inventory) => (chapter, inventory).some
          case PhotoInventory.ListResult.Failed            => None
      .map(_.sequence.map(_.toMap))

  enum GetResult:
    case Found(album: Album, countries: NonEmptyList[Country], photoRoot: PhotoSource, photoInventories: Map[ChapterId, PhotoInventory.Inventory])
    case AlbumNotFound, Failed
