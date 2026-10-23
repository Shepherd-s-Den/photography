package photography.app.album

import photography.domain.album.{Album, AlbumId, AlbumName}
import photography.domain.layout.{Cover, Spread}

final case class AlbumPageSlice(totalAlbums: Long, albums: Vector[Album])

enum AlbumRepositoryFailure derives CanEqual:
  case InvalidAlbumData, DatabaseFailure

trait AlbumRepository[F[_]]:
  def list(offset: Int, limit: Int): F[AlbumRepository.ListResult]
  def create(album: Album, start: Spread.AlbumStart, cover: Cover): F[AlbumRepository.CreateResult]
  def getAlbum(id: AlbumId): F[AlbumRepository.GetResult]
  def rename(id: AlbumId, title: AlbumName): F[AlbumRepository.RenameResult]
  def delete(id: AlbumId): F[AlbumRepository.DeleteResult]

object AlbumRepository:
  enum ListResult derives CanEqual:
    case Listed(slice: AlbumPageSlice)
    case Failed(reason: AlbumRepositoryFailure)

  enum CreateResult derives CanEqual:
    case Created
    case Failed(reason: AlbumRepositoryFailure)

  enum GetResult derives CanEqual:
    case Found(album: Album)
    case NotFound
    case Failed(reason: AlbumRepositoryFailure)

  enum RenameResult derives CanEqual:
    case Renamed(album: Album)
    case NotFound
    case Failed(reason: AlbumRepositoryFailure)

  enum DeleteResult derives CanEqual:
    case Deleted
    case NotFound
    case Failed(reason: AlbumRepositoryFailure)
