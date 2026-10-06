package photography.domain.album

import java.time.Instant
import java.util.UUID
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

opaque type PhotoId = UUID
object PhotoId:
  def apply(value: UUID): PhotoId = value
  extension (id: PhotoId) def value: UUID = id

type PixelCount = Int :| Positive

final case class PhotoDimensions(width: PixelCount, height: PixelCount)

final case class Photo(
    id: PhotoId,
    chapter: ChapterId,
    source: RelativeLocation,
    maybeThumbnail: Option[RelativeLocation],
    maybeCapturedAt: Option[Instant],
    dimensions: PhotoDimensions,
    maybeCaption: Option[String]
)
