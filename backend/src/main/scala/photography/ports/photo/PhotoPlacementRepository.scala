package photography.ports.photo

import photography.domain.album.{ChapterId, PhotoId}

trait PhotoPlacementRepository[Db[_]]:
  def findPlacedPhotos(chapter: ChapterId): Db[PhotoPlacementRepository.FindResult]

object PhotoPlacementRepository:
  enum FindResult:
    case Found(photos: Set[PhotoId])
    case Failed
