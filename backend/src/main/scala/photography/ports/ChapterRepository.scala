package photography.ports

import photography.domain.album.{AlbumId, Chapter, ChapterConflict}

trait ChapterRepository[F[_]]:
  def create(album: AlbumId, chapter: Chapter): F[ChapterRepository.SaveResult]
  def update(album: AlbumId, chapter: Chapter): F[ChapterRepository.SaveResult]

object ChapterRepository:
  enum SaveResult:
    case Saved
    case Rejected(reason: ChapterConflict)
    case AlbumNotFound, Failed
