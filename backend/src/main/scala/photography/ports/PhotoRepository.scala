package photography.ports

import photography.domain.album.*

trait PhotoRepository[Db[_]]:
  def listChapter(chapter: ChapterId): Db[PhotoRepository.ListResult]
  def save(photo: Photo): Db[PhotoRepository.WriteResult]
  def remove(photo: PhotoId): Db[PhotoRepository.WriteResult]
  def recordWrite(intent: PhotoWriteIntent): Db[PhotoRepository.RecordWriteResult]
  def completeWrite(id: PhotoWriteIntentId): Db[PhotoRepository.CompleteWriteResult]
  def listUnfinishedWrites: Db[PhotoRepository.ListUnfinishedWritesResult]

object PhotoRepository:
  enum ListResult:
    case Listed(photos: Vector[Photo])
    case Failed

  enum WriteResult:
    case Applied, Failed

  enum RecordWriteResult:
    case Recorded, Failed

  enum CompleteWriteResult:
    case Completed, Failed

  enum ListUnfinishedWritesResult:
    case Listed(intents: Vector[PhotoWriteIntent])
    case Failed
