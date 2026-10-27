package photography.ports

import photography.domain.album.*

trait PhotoRepository[F[_]]:
  def listChapter(chapter: ChapterId): F[PhotoRepository.ListResult]

object PhotoRepository:
  enum ListResult:
    case Listed(photos: Vector[Photo])
    case Failed
