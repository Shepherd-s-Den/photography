package photography.domain.layout

import photography.domain.album.PhotoId
import java.util.UUID

opaque type CoverPlacementId = UUID
object CoverPlacementId:
  def apply(value: UUID): CoverPlacementId         = value
  extension (id: CoverPlacementId) def value: UUID = id

final case class CoverPlacement(id: CoverPlacementId, slot: CoverSlotId, photo: PhotoId, transform: PhotoTransform)
