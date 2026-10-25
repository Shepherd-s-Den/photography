package photography.ports

import photography.domain.album.{Album, AlbumId, ChapterConflict}

trait ChapterRepository[F[_]]:
  def modifyAtomically(album: AlbumId, decide: Album => ChapterRepository.Decision): F[ChapterRepository.SaveResult]

object ChapterRepository:
  enum Decision:
    case Save(album: Album)
    case Reject(reason: ChapterConflict)

  enum SaveResult:
    case Saved
    case Rejected(reason: ChapterConflict)
    case AlbumNotFound, Failed
