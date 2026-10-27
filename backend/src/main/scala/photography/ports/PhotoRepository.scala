package photography.ports

import photography.domain.album.*

trait PhotoRepository[Db[_]]:
  def listChapter(chapter: ChapterId): Db[PhotoRepository.ListResult]
  def save(photo: Photo): Db[PhotoRepository.WriteResult]
  def remove(photo: PhotoId): Db[PhotoRepository.WriteResult]

object PhotoRepository:
  enum ListResult:
    case Listed(photos: Vector[Photo])
    case Failed

  enum WriteResult:
    case Applied, Failed
