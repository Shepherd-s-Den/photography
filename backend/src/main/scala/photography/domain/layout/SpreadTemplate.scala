package photography.domain.layout

import cats.data.NonEmptySet
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import java.util.UUID

type PositionOnSpread = Double :| (GreaterEqual[0.0] & Less[1.7976931348623157e308])

type DistanceWithinSpread = Double :| (Positive & Less[1.7976931348623157e308])

type SpreadTemplateName = String :| Not[Empty]

opaque type SpreadTemplateId = UUID
object SpreadTemplateId:
  def apply(value: UUID): SpreadTemplateId         = value
  extension (id: SpreadTemplateId) def value: UUID = id

enum SpreadTemplateKind derives CanEqual:
  case AlbumStart, ChapterStart, PhotosOnly

final case class SlotBox(
    x: PositionOnSpread,
    y: PositionOnSpread,
    width: DistanceWithinSpread,
    height: DistanceWithinSpread
)

enum SpreadTemplateSlot derives CanEqual:
  case Photo(box: SlotBox)
  case Text(box: SlotBox)

object SpreadTemplateSlot:
  given cats.Order[SpreadTemplateSlot] = cats.Order.from: (left, right) =>
    val leftKey  = orderingKey(left)
    val rightKey = orderingKey(right)
    summon[Ordering[(Int, Double, Double, Double, Double)]].compare(leftKey, rightKey)

  private def orderingKey(slot: SpreadTemplateSlot): (Int, Double, Double, Double, Double) =
    val (kind, box) = slot match
      case SpreadTemplateSlot.Photo(value) => (0, value)
      case SpreadTemplateSlot.Text(value)  => (1, value)
    val x: Double      = box.x
    val y: Double      = box.y
    val width: Double  = box.width
    val height: Double = box.height
    (kind, x, y, width, height)

final case class SpreadTemplate(
    id: SpreadTemplateId,
    name: SpreadTemplateName,
    kind: SpreadTemplateKind,
    slots: NonEmptySet[SpreadTemplateSlot]
)
