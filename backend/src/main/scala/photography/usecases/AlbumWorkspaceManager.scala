package photography.usecases

import cats.Monad
import cats.data.NonEmptyList
import cats.syntax.all.*
import photography.domain.album.{Album, AlbumId, Country, SourceFolder}
import photography.ports.CountryRepository
import photography.usecases.AlbumManager.GetAlbumResult
import photography.usecases.AlbumWorkspaceManager.*

trait AlbumWorkspaceManager[F[_]]:
  def get(id: AlbumId): F[GetResult]

object AlbumWorkspaceManager:
  def make[F[_]: Monad](albums: AlbumManager[F], countries: CountryRepository[F], photoRoot: SourceFolder): AlbumWorkspaceManager[F] =
    new LiveAlbumWorkspaceManager(albums, countries, photoRoot)

  final private class LiveAlbumWorkspaceManager[F[_]: Monad](
      albums: AlbumManager[F],
      countries: CountryRepository[F],
      photoRoot: SourceFolder
  ) extends AlbumWorkspaceManager[F]:
    override def get(id: AlbumId): F[GetResult] =
      albums.get(id).flatMap:
        case GetAlbumResult.Found(album) =>
          countries.list.map:
            case CountryRepository.ListResult.Listed(catalog) => GetResult.Found(album, catalog, photoRoot)
            case CountryRepository.ListResult.Failed          => GetResult.Failed
        case GetAlbumResult.NotFound => GetResult.AlbumNotFound.pure[F]
        case GetAlbumResult.Failed   => GetResult.Failed.pure[F]

  enum GetResult:
    case Found(album: Album, countries: NonEmptyList[Country], photoRoot: SourceFolder)
    case AlbumNotFound, Failed
