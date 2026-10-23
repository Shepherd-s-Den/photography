package photography.domain.layout

import java.util.UUID

enum SpreadEdge derives CanEqual:
  case Top, Bottom, Left, Right

enum SpreadSlot derives CanEqual:
  case Photo(id: PhotoSpreadSlotId, spread: SpreadId, box: SlotBox, bleed: Set[SpreadEdge])
  case Text(id: TextSpreadSlotId, spread: SpreadId, box: SlotBox)

opaque type PhotoSpreadSlotId = UUID
object PhotoSpreadSlotId:
  def apply(value: UUID): PhotoSpreadSlotId         = value
  extension (id: PhotoSpreadSlotId) def value: UUID = id

opaque type TextSpreadSlotId = UUID
object TextSpreadSlotId:
  def apply(value: UUID): TextSpreadSlotId         = value
  extension (id: TextSpreadSlotId) def value: UUID = id
