package photography.domain

import munit.FunSuite
import cats.Order
import photography.domain.album.{AlbumId, ChapterId, CountryId, DestinationId, PhotoId}
import photography.domain.editing.{EditorId, SessionId}
import photography.domain.gear.{CameraId, LensId}
import photography.domain.layout.{CoverPlacementId, CoverSlotId, PhotoSpreadSlotId, SpreadId, SpreadPlacementId, SpreadTemplateId, TextSpreadSlotId}
import photography.domain.album.{AbsoluteLocation, RelativeLocation}

import java.nio.file.Path
import java.util.UUID

final class DomainValuesSuite extends FunSuite:

  test("domain identifiers preserve their UUID values"):
    val value = UUID.fromString("68c9c3d0-7f0e-4f11-a127-5e18fa87d843")

    assertEquals(AlbumId(value).value, value)
    assertEquals(ChapterId(value).value, value)
    assertEquals(CountryId(value).value, value)
    assertEquals(DestinationId(value).value, value)
    assertEquals(PhotoId(value).value, value)
    assertEquals(EditorId(value).value, value)
    assertEquals(SessionId(value).value, value)
    assertEquals(CameraId(value).value, value)
    assertEquals(LensId(value).value, value)
    assertEquals(CoverPlacementId(value).value, value)
    assertEquals(CoverSlotId(value).value, value)
    assertEquals(PhotoSpreadSlotId(value).value, value)
    assertEquals(SpreadId(value).value, value)
    assertEquals(SpreadPlacementId(value).value, value)
    assertEquals(SpreadTemplateId(value).value, value)
    assertEquals(TextSpreadSlotId(value).value, value)

  test("camera and lens identifiers have UUID ordering"):
    val lower  = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val higher = UUID.fromString("00000000-0000-0000-0000-000000000002")

    assertEquals(summon[Order[CameraId]].compare(CameraId(lower), CameraId(higher)) < 0, true)
    assertEquals(summon[Order[LensId]].compare(LensId(higher), LensId(lower)) > 0, true)

  test("location values preserve their paths"):
    val path = Path.of("albums", "summer")

    assertEquals(AbsoluteLocation(path).value, path)
    assertEquals(RelativeLocation(path).value, path)
