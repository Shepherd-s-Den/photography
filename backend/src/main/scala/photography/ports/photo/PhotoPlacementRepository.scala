package photography.ports.photo

import photography.domain.album.{AlbumId, ChapterId, PhotoId}

trait PhotoPlacementRepository[Db[_]]:
  def findPlacedPhotos(chapter: ChapterId): Db[PhotoPlacementRepository.FindResult]
  def inspect(album: AlbumId, maybeChapter: Option[ChapterId], photo: PhotoId): Db[PhotoPlacementRepository.Inspection]

object PhotoPlacementRepository:
  final case class Inspection(inCollection: Boolean, placed: Boolean)

  enum FindResult:
    case Found(photos: Set[PhotoId])
    case Failed
