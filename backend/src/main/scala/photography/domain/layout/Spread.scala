package photography.domain.layout

import photography.domain.album.{AlbumId, ChapterId}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

import java.util.UUID

type PositionInChapter = Int :| Positive

opaque type SpreadId = UUID
object SpreadId:
  def apply(value: UUID): SpreadId         = value
  extension (id: SpreadId) def value: UUID = id

sealed trait Spread:
  def id: SpreadId
  def template: SpreadTemplateId
  def slots: Vector[SpreadSlot]
  def placements: Vector[SpreadPlacement]

object Spread:
  final case class AlbumStart(
      id: SpreadId,
      album: AlbumId,
      template: SpreadTemplateId,
      slots: Vector[SpreadSlot],
      placements: Vector[SpreadPlacement]
  ) extends Spread
  final case class ChapterStart(
      id: SpreadId,
      chapter: ChapterId,
      template: SpreadTemplateId,
      slots: Vector[SpreadSlot],
      placements: Vector[SpreadPlacement]
  ) extends Spread
  final case class PhotosOnly(
      id: SpreadId,
      chapter: ChapterId,
      position: PositionInChapter,
      template: SpreadTemplateId,
      slots: Vector[SpreadSlot],
      placements: Vector[SpreadPlacement]
  ) extends Spread
