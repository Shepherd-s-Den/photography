package photography.usecases.cover

import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import photography.domain.album.PhotoId
import photography.domain.layout.*
import photography.usecases.cover.CoverPlacementEditor.*

trait CoverPlacementEditor:
  def edit(slots: Set[CoverSlotId], placements: Vector[CoverPlacement], request: Edit): Result

object CoverPlacementEditor:
  def make: CoverPlacementEditor = new LiveCoverPlacementEditor

  final private class LiveCoverPlacementEditor extends CoverPlacementEditor:
    override def edit(slots: Set[CoverSlotId], placements: Vector[CoverPlacement], request: Edit): Result =
      val changed = request match
        case Edit.Place(slot, photo, id) =>
          requireSlot(slots = slots, slot).map(_ => placements.filterNot(_.slot.value === slot.value) :+ CoverPlacement(id, slot, photo, initial))
        case Edit.Remove(id)                => findPlacement(placements = placements, id).map(_ => placements.filterNot(_.id.value === id.value))
        case Edit.Adjust(id, transform)     => adjust(placements = placements, id, transform)
        case Edit.Move(source, destination) => move(slots = slots, placements = placements, source, destination)
      changed.fold(Result.Rejected(_), values => Result.Changed(placements = values))

    private def adjust(placements: Vector[CoverPlacement], id: CoverPlacementId, transform: PhotoTransform) =
      for
        _ <- findPlacement(placements = placements, id)
        _ <- Either.cond(transform.zoom >= 1.0 && transform.zoom <= 5.0, (), Failure.InvalidTransform)
      yield placements.map(value => if value.id.value === id.value then value.copy(transform = transform) else value)

    private def move(slots: Set[CoverSlotId], placements: Vector[CoverPlacement], source: CoverPlacementId, destination: CoverSlotId) =
      for
        placed <- findPlacement(placements = placements, source)
        _      <- requireSlot(slots = slots, destination)
      yield placements.map: value =>
        if value.id.value === source.value then value.copy(slot = destination)
        else if value.slot.value === destination.value then value.copy(slot = placed.slot)
        else value

    private def requireSlot(slots: Set[CoverSlotId], slot: CoverSlotId) =
      Either.cond(slots.contains(slot), (), Failure.SlotNotFound)

    private def findPlacement(placements: Vector[CoverPlacement], id: CoverPlacementId) =
      placements.find(_.id.value === id.value).toRight(Failure.PlacementNotFound)

    private val initial = PhotoTransform(Shift(x = 0.0, y = 0.0), mirrored = false, zoom = 1.0)

  enum Edit:
    case Place(slot: CoverSlotId, photo: PhotoId, id: CoverPlacementId)
    case Remove(id: CoverPlacementId)
    case Adjust(id: CoverPlacementId, transform: PhotoTransform)
    case Move(source: CoverPlacementId, destination: CoverSlotId)

  enum Result:
    case Changed(placements: Vector[CoverPlacement])
    case Rejected(reason: Failure)

  enum Failure derives CanEqual:
    case SlotNotFound, PlacementNotFound, InvalidTransform
