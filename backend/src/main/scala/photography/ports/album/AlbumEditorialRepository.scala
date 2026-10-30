package photography.ports.album

import photography.domain.album.{AlbumAuthors, AlbumEditorial, AlbumId}

/** Each write atomically changes only its named field. Uncertain outcomes require an authoritative read before retry. */
trait AlbumEditorialRepository[F[_]]:
  def read(album: AlbumId): F[AlbumEditorialRepository.ReadResult]
  def saveSynopsis(album: AlbumId, text: String): F[AlbumEditorialRepository.WriteResult]
  def saveAuthors(album: AlbumId, authors: AlbumAuthors): F[AlbumEditorialRepository.WriteResult]

object AlbumEditorialRepository:
  enum ReadResult derives CanEqual:
    case Found(editorial: AlbumEditorial)
    case NotFound, Failed

  enum WriteResult derives CanEqual:
    case Saved, NotFound, Failed, Uncertain
