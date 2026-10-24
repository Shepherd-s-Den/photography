package photography.domain.layout

import photography.domain.album.AlbumId
import java.util.UUID

enum SpineOwner derives CanEqual:
  case Front, Back

opaque type CoverSlotId = UUID
object CoverSlotId:
  def apply(value: UUID): CoverSlotId         = value
  extension (id: CoverSlotId) def value: UUID = id

final case class CoverSlot(id: CoverSlotId)

final case class Cover(album: AlbumId, spineOwner: SpineOwner, front: CoverSlot, back: CoverSlot)
