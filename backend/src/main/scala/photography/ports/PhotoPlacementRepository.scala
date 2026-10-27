package photography.ports

import photography.domain.album.{ChapterId, PhotoId}

trait PhotoPlacementRepository[F[_]]:
  def findPlacedPhotos(chapter: ChapterId): F[PhotoPlacementRepository.FindResult]

object PhotoPlacementRepository:
  enum FindResult:
    case Found(photos: Set[PhotoId])
    case Failed
