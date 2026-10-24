package photography.domain.layout

import photography.domain.album.PhotoId
import java.util.UUID

enum CaptionCorner derives CanEqual:
  case TopLeft, TopRight, BottomLeft, BottomRight

final case class Shift(x: InDesignPoints, y: InDesignPoints)

final case class PhotoTransform(shift: Shift, mirrored: Boolean, zoom: Zoom)

opaque type SpreadPlacementId = UUID
object SpreadPlacementId:
  def apply(value: UUID): SpreadPlacementId         = value
  extension (id: SpreadPlacementId) def value: UUID = id

final case class SpreadPlacement(
    id: SpreadPlacementId,
    slot: PhotoSpreadSlotId,
    photo: PhotoId,
    transform: PhotoTransform,
    captionCorner: CaptionCorner
)
