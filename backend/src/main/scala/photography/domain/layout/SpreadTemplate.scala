package photography.domain.layout

import cats.data.NonEmptyList
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

final case class SlotBox(x: PositionOnSpread, y: PositionOnSpread, width: DistanceWithinSpread, height: DistanceWithinSpread) derives CanEqual

sealed trait SpreadTemplate:
  def id: SpreadTemplateId
  def name: SpreadTemplateName

object SpreadTemplate:
  final case class AlbumStart(id: SpreadTemplateId, name: SpreadTemplateName, photoSlots: Vector[SlotBox], textSlot: SlotBox)   extends SpreadTemplate
  final case class ChapterStart(id: SpreadTemplateId, name: SpreadTemplateName, photoSlots: Vector[SlotBox], textSlot: SlotBox) extends SpreadTemplate
  final case class PhotosOnly(id: SpreadTemplateId, name: SpreadTemplateName, photoSlots: NonEmptyList[SlotBox])                extends SpreadTemplate
