package photography.usecases.spread

import cats.data.NonEmptyList
import io.github.iltotore.iron.autoRefine
import munit.FunSuite
import photography.domain.album.PhotoId
import photography.domain.layout.*
import photography.usecases.spread.SpreadPlacementEditor.*
import java.util.UUID

class SpreadPlacementEditorComponentTest extends FunSuite:
  private val firstSlot = SpreadSlot.Photo(
    PhotoSpreadSlotId(new UUID(0L, 1L)),
    SlotBox(x = 0.0, y = 0.0, width = 50.0, height = 100.0),
    bleed = Set.empty[SpreadEdge]
  )
  private val secondSlot = firstSlot.copy(id = PhotoSpreadSlotId(new UUID(0L, 2L)))
  private val thirdSlot  = firstSlot.copy(id = PhotoSpreadSlotId(new UUID(0L, 3L)))
  private val original   = Spread.PhotosOnly(
    SpreadId(new UUID(0L, 4L)),
    SpreadTemplateId(new UUID(0L, 5L)),
    photoSlots = NonEmptyList.of(firstSlot, secondSlot, thirdSlot),
    placements = Vector.empty[SpreadPlacement]
  )
  private val firstPhoto  = PhotoId(new UUID(0L, 6L))
  private val secondPhoto = PhotoId(new UUID(0L, 7L))
  private val firstId     = SpreadPlacementId(new UUID(0L, 8L))
  private val secondId    = SpreadPlacementId(new UUID(0L, 9L))
  private val initial     = PhotoTransform(Shift(x = 0.0, y = 0.0), mirrored = false, zoom = 1.0)
  private val adjusted    = PhotoTransform(Shift(x = 12.0, y = -8.0), mirrored = true, zoom = 3.0)
  private val first       = SpreadPlacement(firstId, firstSlot.id, firstPhoto, adjusted, CaptionCorner.TopLeft)
  private val second      = SpreadPlacement(secondId, secondSlot.id, secondPhoto, initial, CaptionCorner.BottomRight)
  private val editor      = SpreadPlacementEditor.make

  test("should initialize placement and replacement without changing slot geometry"):
    val occupied = original.copy(placements = Vector(first))
    val expected = original.copy(placements = Vector(second.copy(slot = firstSlot.id)))

    val placed   = editor.edit(original, Edit.Place(firstSlot.id, secondPhoto, secondId))
    val replaced = editor.edit(occupied, Edit.Place(firstSlot.id, secondPhoto, secondId))

    assertEquals(placed, Result.Changed(expected))
    assertEquals(replaced, Result.Changed(expected))

  test("should release a removed photograph and initialize a fresh placement"):
    val occupied = original.copy(placements = Vector(first))
    val expected = original.copy(placements = Vector(first.copy(transform = initial, captionCorner = CaptionCorner.BottomRight)))

    val removed  = editor.edit(occupied, Edit.Remove(firstId))
    val replaced = editor.edit(original, Edit.Place(firstSlot.id, firstPhoto, firstId))

    assertEquals(removed, Result.Changed(original))
    assertEquals(replaced, Result.Changed(expected))

  test("should carry transforms and caption corners when moving or swapping"):
    val occupied     = original.copy(placements = Vector(first, second))
    val expectedMove = occupied.copy(placements = Vector(first.copy(slot = thirdSlot.id), second))
    val expectedSwap = occupied.copy(placements = Vector(first.copy(slot = secondSlot.id), second.copy(slot = firstSlot.id)))

    val moved   = editor.edit(occupied, Edit.Move(firstId, thirdSlot.id))
    val swapped = editor.edit(occupied, Edit.Move(firstId, secondSlot.id))

    assertEquals(moved, Result.Changed(expectedMove))
    assertEquals(swapped, Result.Changed(expectedSwap))

  test("should preserve opening identity, text and geometry while changing placements"):
    val text         = SpreadSlot.Text(TextSpreadSlotId(new UUID(0L, 70L)), firstSlot.box)
    val albumOpening =
      Spread.AlbumStart(original.id, original.template, photoSlots = Vector(firstSlot), text, placements = Vector.empty[SpreadPlacement])
    val chapterOpening =
      Spread.ChapterStart(original.id, original.template, photoSlots = Vector(firstSlot), text, placements = Vector.empty[SpreadPlacement])
    val placement                       = first.copy(transform = initial, captionCorner = CaptionCorner.BottomRight)
    val cases: Vector[(Spread, Spread)] = Vector(
      albumOpening   -> albumOpening.copy(placements = Vector(placement)),
      chapterOpening -> chapterOpening.copy(placements = Vector(placement))
    )

    val actual = cases.map { case (opening, _) => editor.edit(opening, Edit.Place(firstSlot.id, firstPhoto, firstId)) }

    assertEquals(actual, cases.map { case (_, expected) => Result.Changed(expected) })

  test("should reject stale identities without producing a changed spread"):
    val occupied = original.copy(placements = Vector(first))
    val stale    = PhotoSpreadSlotId(new UUID(0L, 99L))
    val requests = Vector(
      Edit.Place(stale, secondPhoto, secondId) -> Failure.SlotNotFound,
      Edit.Remove(secondId)                    -> Failure.PlacementNotFound,
      Edit.Move(secondId, secondSlot.id)       -> Failure.PlacementNotFound,
      Edit.Move(firstId, stale)                -> Failure.SlotNotFound,
      Edit.Adjust(secondId, adjusted)          -> Failure.PlacementNotFound
    )

    val actual = requests.map { case (request, _) => editor.edit(occupied, request) }

    assertEquals(actual, requests.map { case (_, failure) => Result.Rejected(failure) })

  test("should save bounded zoom adjustments while retaining the placement identity"):
    val occupied = original.copy(placements = Vector(first.copy(transform = initial), second))
    val expected = original.copy(placements = Vector(first, second))
    val invalid  = Vector(initial.copy(zoom = 0.5), initial.copy(zoom = 6.0))

    val changed  = editor.edit(occupied, Edit.Adjust(firstId, adjusted))
    val rejected = invalid.map(transform => editor.edit(occupied, Edit.Adjust(firstId, transform)))

    assertEquals(changed, Result.Changed(expected))
    assertEquals(rejected, Vector.fill(invalid.size)(Result.Rejected(Failure.InvalidTransform)))
