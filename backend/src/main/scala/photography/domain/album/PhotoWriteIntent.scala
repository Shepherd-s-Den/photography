package photography.domain.album

import java.util.UUID

opaque type PhotoWriteIntentId = UUID
object PhotoWriteIntentId:
  def apply(value: UUID): PhotoWriteIntentId         = value
  extension (id: PhotoWriteIntentId) def value: UUID = id

enum PhotoWriteIntent:
  def id: PhotoWriteIntentId
  def chapter: ChapterId
  def photo: PhotoId

  case Publication(
      id: PhotoWriteIntentId,
      chapter: ChapterId,
      photo: PhotoId,
      location: ThumbnailLocation,
      maybePrevious: Option[ThumbnailLocation]
  )
  case Retirement(id: PhotoWriteIntentId, chapter: ChapterId, photo: PhotoId, location: ThumbnailLocation)
