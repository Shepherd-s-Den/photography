package photography.ports

import photography.domain.album.{Album, AlbumId, AlbumYear}
import photography.domain.layout.Cover

trait AlbumRepository[F[_]]:
  def list(offset: Int, limit: Int): F[AlbumRepository.ListResult]
  def create(album: Album, cover: Cover): F[AlbumRepository.CreateResult]
  def get(id: AlbumId): F[AlbumRepository.GetResult]
  def changeYear(id: AlbumId, year: AlbumYear): F[AlbumRepository.ChangeYearResult]
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

  enum ChangeYearResult derives CanEqual:
    case Changed(album: Album)
    case HasChapters
    case NotFound
    case Failed(reason: Failure)

  enum DeleteResult derives CanEqual:
    case Deleted
    case NotFound
    case Failed(reason: Failure)

  final case class AlbumPageSlice(totalAlbums: Long, albums: Vector[Album])

  enum Failure derives CanEqual:
    case YearAlreadyUsed, DatabaseFailure
