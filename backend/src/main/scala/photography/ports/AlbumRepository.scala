package photography.ports

import photography.domain.album.{Album, AlbumId, AlbumTitle}
import photography.domain.layout.Cover

trait AlbumRepository[F[_]]:
  def list(offset: Int, limit: Int): F[AlbumRepository.ListResult]
  def create(album: Album, cover: Cover): F[AlbumRepository.CreateResult]
  def get(id: AlbumId): F[AlbumRepository.GetResult]
  def rename(id: AlbumId, title: AlbumTitle): F[AlbumRepository.RenameResult]
  def delete(id: AlbumId): F[AlbumRepository.DeleteResult]

object AlbumRepository:
  enum ListResult derives CanEqual:
    case Listed(slice: AlbumPageSlice)
    case Failed(reason: Failure)

  enum CreateResult derives CanEqual:
    case Created
    case Failed(reason: Failure)

  enum GetResult derives CanEqual:
    case Found(album: Album)
    case NotFound
    case Failed(reason: Failure)

  enum RenameResult derives CanEqual:
    case Renamed(album: Album)
    case NotFound
    case Failed(reason: Failure)

  enum DeleteResult derives CanEqual:
    case Deleted
    case NotFound
    case Failed(reason: Failure)

  final case class AlbumPageSlice(totalAlbums: Long, albums: Vector[Album])

  enum Failure derives CanEqual:
    case YearAlreadyUsed, DatabaseFailure
