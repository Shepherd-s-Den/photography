package photography.domain.layout

import photography.domain.album.PhotoId
import java.util.UUID

enum CaptionCorner derives CanEqual:
  case TopLeft, TopRight, BottomLeft, BottomRight

final case class Shift(x: InDesignPoints, y: InDesignPoints)

final case class PhotoTransform(shift: Shift, mirrored: Boolean, zoom: Zoom)

final case class PhotoFrame(xPt: InDesignPoints, yPt: InDesignPoints, widthPt: InDesignPoints, heightPt: InDesignPoints)

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

opaque type CoverPlacementId = UUID
object CoverPlacementId:
  def apply(value: UUID): CoverPlacementId         = value
  extension (id: CoverPlacementId) def value: UUID = id

final case class CoverPlacement(id: CoverPlacementId, slot: CoverSlot, photo: PhotoId, transform: PhotoTransform)

enum PhotoPlacement:
  case Spread(value: SpreadPlacement)
  case Cover(value: CoverPlacement)

final case class PlacementBook(placements: Vector[PhotoPlacement])
