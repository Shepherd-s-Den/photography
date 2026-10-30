package photography.usecases.cover

import io.github.iltotore.iron.autoRefine
import munit.FunSuite
import photography.domain.album.{AlbumId, PhotoId}
import photography.domain.layout.*
import photography.usecases.cover.CoverPlacementEditor.*
import java.util.UUID

class CoverPlacementEditorComponentTest extends FunSuite:
  private val cover = Cover(
    AlbumId(new UUID(0L, 1L)),
    SpineOwner.Back,
    CoverSlot(CoverSlotId(new UUID(0L, 2L))),
    CoverSlot(CoverSlotId(new UUID(0L, 3L)))
  )
  private val firstPhoto  = PhotoId(new UUID(0L, 4L))
  private val secondPhoto = PhotoId(new UUID(0L, 5L))
  private val firstId     = CoverPlacementId(new UUID(0L, 6L))
  private val secondId    = CoverPlacementId(new UUID(0L, 7L))
  private val staleSlot   = CoverSlotId(new UUID(0L, 8L))
  private val initial     = PhotoTransform(Shift(x = 0.0, y = 0.0), mirrored = false, zoom = 1.0)
  private val adjusted    = PhotoTransform(Shift(x = 20.0, y = -10.0), mirrored = true, zoom = 4.0)
  private val first       = CoverPlacement(firstId, cover.front.id, firstPhoto, adjusted)
  private val second      = CoverPlacement(secondId, cover.back.id, secondPhoto, initial)
  private val slots       = Set(cover.front.id, cover.back.id)
  private val editor      = CoverPlacementEditor.make

  test("should initialize and replace cover photographs with the initial transform"):
    val expected = Vector(second.copy(slot = cover.front.id))

    val placed   = editor.edit(slots = slots, placements = Vector.empty[CoverPlacement], Edit.Place(cover.front.id, secondPhoto, secondId))
    val replaced = editor.edit(slots = slots, placements = Vector(first), Edit.Place(cover.front.id, secondPhoto, secondId))
    val removed  = editor.edit(slots = slots, placements = Vector(first), Edit.Remove(firstId))

    assertEquals(placed, Result.Changed(placements = expected))
    assertEquals(replaced, Result.Changed(placements = expected))
    assertEquals(removed, Result.Changed(placements = Vector.empty[CoverPlacement]))

  test("should carry transforms between cover sides and save completed adjustments"):
    val expectedMove = Vector(first.copy(slot = cover.back.id))
    val expectedSwap = Vector(first.copy(slot = cover.back.id), second.copy(slot = cover.front.id))

    val moved     = editor.edit(slots = slots, placements = Vector(first), Edit.Move(firstId, cover.back.id))
    val swapped   = editor.edit(slots = slots, placements = Vector(first, second), Edit.Move(firstId, cover.back.id))
    val adjusted  = editor.edit(slots = slots, placements = Vector(first, second), Edit.Adjust(secondId, first.transform))
    val unchanged = editor.edit(slots = slots, placements = Vector(first, second), Edit.Move(firstId, cover.front.id))

    assertEquals(moved, Result.Changed(placements = expectedMove))
    assertEquals(swapped, Result.Changed(placements = expectedSwap))
    assertEquals(adjusted, Result.Changed(placements = Vector(first, second.copy(transform = first.transform))))
    assertEquals(unchanged, Result.Changed(placements = Vector(first, second)))

  test("should reject stale slots, vanished sources and invalid zoom"):
    val occupied = Vector(first)
    val requests = Vector(
      Edit.Place(staleSlot, secondPhoto, secondId)   -> Failure.SlotNotFound,
      Edit.Remove(secondId)                          -> Failure.PlacementNotFound,
      Edit.Move(secondId, cover.back.id)             -> Failure.PlacementNotFound,
      Edit.Move(firstId, staleSlot)                  -> Failure.SlotNotFound,
      Edit.Adjust(secondId, initial)                 -> Failure.PlacementNotFound,
      Edit.Adjust(firstId, initial.copy(zoom = 0.5)) -> Failure.InvalidTransform,
      Edit.Adjust(firstId, initial.copy(zoom = 6.0)) -> Failure.InvalidTransform
    )

    val actual = requests.map { case (request, _) => editor.edit(slots = slots, placements = occupied, request) }

    assertEquals(actual, requests.map { case (_, failure) => Result.Rejected(failure) })
