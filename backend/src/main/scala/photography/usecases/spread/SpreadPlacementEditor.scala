package photography.usecases.spread

import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import photography.domain.album.PhotoId
import photography.domain.layout.*
import photography.usecases.spread.SpreadPlacementEditor.*

trait SpreadPlacementEditor:
  def edit(spread: Spread, request: Edit): Result

object SpreadPlacementEditor:
  def make: SpreadPlacementEditor = new LiveSpreadPlacementEditor

  final private class LiveSpreadPlacementEditor extends SpreadPlacementEditor:
    override def edit(spread: Spread, request: Edit): Result =
      val slots = spread match
        case value: Spread.AlbumStart   => value.photoSlots.map(_.id).toSet
        case value: Spread.ChapterStart => value.photoSlots.map(_.id).toSet
        case value: Spread.PhotosOnly   => value.photoSlots.toList.map(_.id).toSet
      val changed = request match
        case Edit.Place(slot, photo, id) => place(slots = slots, placements = spread.placements, slot, photo, id)
        case Edit.Remove(id) => findPlacement(placements = spread.placements, id).map(_ => spread.placements.filterNot(_.id.value === id.value))
        case Edit.Adjust(id, transform)     => adjust(placements = spread.placements, id, transform)
        case Edit.Move(source, destination) => move(slots = slots, placements = spread.placements, source, destination)
      changed.fold(Result.Rejected(_), placements => Result.Changed(replacePlacements(spread, placements = placements)))

    private def place(
        slots: Set[PhotoSpreadSlotId],
        placements: Vector[SpreadPlacement],
        slot: PhotoSpreadSlotId,
        photo: PhotoId,
        id: SpreadPlacementId
    ) =
      requireSlot(slots = slots, slot).map: _ =>
        val fresh = SpreadPlacement(id, slot, photo, initial, CaptionCorner.BottomRight)
        placements.filterNot(_.slot.value === slot.value) :+ fresh

    private def adjust(placements: Vector[SpreadPlacement], id: SpreadPlacementId, transform: PhotoTransform) =
      for
        _ <- findPlacement(placements = placements, id)
        _ <- Either.cond(transform.zoom >= 1.0 && transform.zoom <= 5.0, (), Failure.InvalidTransform)
      yield placements.map(value => if value.id.value === id.value then value.copy(transform = transform) else value)

    private def move(slots: Set[PhotoSpreadSlotId], placements: Vector[SpreadPlacement], source: SpreadPlacementId, destination: PhotoSpreadSlotId) =
      for
        placed <- findPlacement(placements = placements, source)
        _      <- requireSlot(slots = slots, destination)
      yield placements.map: value =>
        if value.id.value === source.value then value.copy(slot = destination)
        else if value.slot.value === destination.value then value.copy(slot = placed.slot)
        else value

    private def requireSlot(slots: Set[PhotoSpreadSlotId], slot: PhotoSpreadSlotId) =
      Either.cond(slots.contains(slot), (), Failure.SlotNotFound)

    private def findPlacement(placements: Vector[SpreadPlacement], id: SpreadPlacementId) =
      placements.find(_.id.value === id.value).toRight(Failure.PlacementNotFound)

    private def replacePlacements(spread: Spread, placements: Vector[SpreadPlacement]) = spread match
      case value: Spread.AlbumStart   => value.copy(placements = placements)
      case value: Spread.ChapterStart => value.copy(placements = placements)
      case value: Spread.PhotosOnly   => value.copy(placements = placements)

    private val initial = PhotoTransform(Shift(x = 0.0, y = 0.0), mirrored = false, zoom = 1.0)

  enum Edit:
    case Place(slot: PhotoSpreadSlotId, photo: PhotoId, id: SpreadPlacementId)
    case Remove(id: SpreadPlacementId)
    case Adjust(id: SpreadPlacementId, transform: PhotoTransform)
    case Move(source: SpreadPlacementId, destination: PhotoSpreadSlotId)

  enum Result:
    case Changed(spread: Spread)
    case Rejected(reason: Failure)

  enum Failure derives CanEqual:
    case SlotNotFound, PlacementNotFound, InvalidTransform
