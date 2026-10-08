package photography.adapters.http

import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import munit.FunSuite
import photography.app.album.{AlbumPage, AlbumSummary}
import photography.domain.album.*
import photography.domain.layout.*

import java.nio.file.Path
import java.time.Instant
import java.util.UUID

final class AlbumJsonComponentTest extends FunSuite:

  test("should serialize every persisted composition member and optional value"):
    val encoded = AlbumJson.composition(composition).noSpaces

    assert(encoded.contains(""""spineOwner":"back""""))
    assert(encoded.contains(""""kind":"photo""""))
    assert(encoded.contains(""""kind":"text""""))
    assert(encoded.contains(""""bleed":["""))
    assert(encoded.contains(""""captionCorner":"top_left""""))
    assert(encoded.contains(""""captionCorner":"top_right""""))
    assert(encoded.contains(""""captionCorner":"bottom_left""""))
    assert(encoded.contains(""""captionCorner":"bottom_right""""))
    assert(encoded.contains(""""placement":{"id":"""))
    assert(encoded.contains(""""thumbnail":null"""))
    assert(encoded.contains(""""capturedAt":null"""))
    assert(encoded.contains(""""caption":null"""))
    assert(encoded.contains(""""chapters":["""))

  test("should serialize album summaries in a populated page"):
    val summary = AlbumSummary(
      AlbumId(uuid(10)),
      "A persisted album".refineUnsafe[Not[Empty]],
      Instant.parse("2026-10-07T12:00:00Z")
    )
    val page = AlbumPage(Vector(summary), page = 1, pageSize = 6, totalAlbums = 1, totalPages = 1)

    val encoded = AlbumJson.page(page).noSpaces

    assert(encoded.contains(""""id":"00000000-0000-4000-8000-000000000010""""))
    assert(encoded.contains(""""title":"A persisted album""""))
    assert(encoded.contains(""""createdAt":"2026-10-07T12:00:00Z""""))

  private def composition: AlbumComposition =
    val albumId     = AlbumId(uuid(1))
    val chapterId   = ChapterId(uuid(2))
    val photoId     = PhotoId(uuid(3))
    val photoSlotId = PhotoSpreadSlotId(uuid(4))
    val spreadId    = SpreadId(uuid(5))
    val frontSlot   = CoverSlot(CoverSlotId(uuid(6)))
    val backSlot    = CoverSlot(CoverSlotId(uuid(7)))
    val box         = fullSpreadBox
    val transform   = PhotoTransform(
      Shift(
        0.25.refineUnsafe[Greater[-1.7976931348623157e308] & Less[1.7976931348623157e308]],
        0.5.refineUnsafe[Greater[-1.7976931348623157e308] & Less[1.7976931348623157e308]]
      ),
      mirrored = true,
      zoom = 2.0.refineUnsafe[Positive & Less[1.7976931348623157e308]]
    )
    val placements = Vector(
      CaptionCorner.TopLeft,
      CaptionCorner.TopRight,
      CaptionCorner.BottomLeft,
      CaptionCorner.BottomRight
    ).zipWithIndex.map: (corner, index) =>
      SpreadPlacement(
        SpreadPlacementId(uuid(20 + index)),
        photoSlotId,
        photoId,
        transform,
        corner
      )
    val photos = Vector(
      Photo(
        photoId,
        chapterId,
        RelativeLocation(Path.of("chapter/photo.jpg")),
        RelativeLocation(Path.of("thumb/photo.jpg")).some,
        Instant.parse("2026-10-07T12:00:00Z").some,
        PhotoDimensions(
          120.refineUnsafe[Positive],
          80.refineUnsafe[Positive]
        ),
        "Caption".some
      ),
      Photo(
        PhotoId(uuid(8)),
        chapterId,
        RelativeLocation(Path.of("chapter/photo-2.jpg")),
        Option.empty[RelativeLocation],
        Option.empty[Instant],
        PhotoDimensions(
          60.refineUnsafe[Positive],
          40.refineUnsafe[Positive]
        ),
        Option.empty[String]
      )
    )
    val chapter = Chapter(
      chapterId,
      albumId,
      DestinationId(uuid(9)),
      "A chapter",
      VisitPeriod(
        Instant.parse("2026-10-06T00:00:00Z"),
        Instant.parse("2026-10-07T00:00:00Z")
      ),
      0.refineUnsafe[GreaterEqual[0] & Less[20]],
      RelativeLocation(Path.of("chapter"))
    )
    val frontPlacement = CoverPlacement(
      CoverPlacementId(uuid(10)),
      frontSlot,
      photoId,
      transform
    )
    val backPlacement = CoverPlacement(
      CoverPlacementId(uuid(11)),
      backSlot,
      PhotoId(uuid(8)),
      transform
    )
    AlbumComposition(
      Album(albumId, "Album".refineUnsafe[Not[Empty]], "Synopsis", Instant.parse("2026-10-07T12:00:00Z")),
      SpreadComposition(
        spreadId,
        SpreadTemplateId(uuid(12)),
        Vector(
          SpreadSlot.Photo(
            photoSlotId,
            spreadId,
            box,
            Set(SpreadEdge.Top, SpreadEdge.Bottom, SpreadEdge.Left, SpreadEdge.Right)
          ),
          SpreadSlot.Text(TextSpreadSlotId(uuid(13)), spreadId, box)
        ),
        placements
      ),
      CoverComposition(SpineOwner.Back, frontSlot, frontPlacement.some, backSlot, backPlacement.some),
      Vector(
        ChapterComposition(
          chapter,
          SpreadComposition(spreadId, SpreadTemplateId(uuid(12)), Vector.empty, Vector.empty),
          photos,
          Vector(SpreadComposition(spreadId, SpreadTemplateId(uuid(12)), Vector.empty, Vector.empty))
        )
      )
    )

  private def fullSpreadBox: SlotBox =
    val x      = 0.0.refineUnsafe[GreaterEqual[0.0] & Less[1.7976931348623157e308]]
    val y      = 0.0.refineUnsafe[GreaterEqual[0.0] & Less[1.7976931348623157e308]]
    val width  = 1.0.refineUnsafe[Positive & Less[1.7976931348623157e308]]
    val height = 1.0.refineUnsafe[Positive & Less[1.7976931348623157e308]]
    SlotBox(x, y, width, height)

  private def uuid(index: Int): UUID =
    val suffix = index.toString.reverse.padTo(12, '0').reverse
    UUID.fromString(s"00000000-0000-4000-8000-$suffix")
