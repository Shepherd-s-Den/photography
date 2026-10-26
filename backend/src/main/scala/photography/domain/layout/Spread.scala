package photography.domain.layout

import cats.data.NonEmptyList

import java.util.UUID

opaque type SpreadId = UUID
object SpreadId:
  def apply(value: UUID): SpreadId         = value
  extension (id: SpreadId) def value: UUID = id

sealed trait Spread:
  def id: SpreadId
  def template: SpreadTemplateId
  def placements: Vector[SpreadPlacement]

object Spread:
  final case class AlbumStart(
      id: SpreadId,
      template: SpreadTemplateId,
      photoSlots: Vector[SpreadSlot.Photo],
      textSlot: SpreadSlot.Text,
      placements: Vector[SpreadPlacement]
  ) extends Spread
  final case class ChapterStart(
      id: SpreadId,
      template: SpreadTemplateId,
      photoSlots: Vector[SpreadSlot.Photo],
      textSlot: SpreadSlot.Text,
      placements: Vector[SpreadPlacement]
  ) extends Spread
  final case class PhotosOnly(
      id: SpreadId,
      template: SpreadTemplateId,
      photoSlots: NonEmptyList[SpreadSlot.Photo],
      placements: Vector[SpreadPlacement]
  ) extends Spread
