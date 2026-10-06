package photography.domain

import cats.data.{NonEmptyMap, NonEmptySet}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import munit.FunSuite
import photography.domain.album.*
import photography.domain.editing.*
import photography.domain.gear.*
import photography.domain.layout.*

import java.nio.file.Paths
import java.time.Instant
import java.util.UUID

final class DomainTypesSuite extends FunSuite:
  test("scalar aliases encode their refined domains"):
    val albumName: AlbumName = "Coast".refine[Not[Empty]]
    val countryCode: CountryCode = "CA".refine[Match["[A-Z]{2}"]]
    val pixels: PixelCount = 1200.refine[Positive]
    val albumPosition: PositionInAlbum = 0.refine[GreaterEqual[0] & Less[20]]
    val chapterPosition: PositionInChapter = 1.refine[Positive]
    val slotCoordinate: PositionOnSpread =
      12.5.refine[GreaterEqual[0.0] & Less[1.7976931348623157E308]]
    val slotSize: DistanceWithinSpread =
      20.0.refine[Positive & Less[1.7976931348623157E308]]
    val zoom: Zoom = 1.0.refine[Positive & Less[1.7976931348623157E308]]
    val lease: SessionLease = 1000L.refine[Positive]
    val pageCount: PageCount = 30.refine[Positive]

    assertEquals(albumName, "Coast")
    assertEquals(countryCode, "CA")
    assertEquals(pixels, 1200)
    assertEquals(albumPosition, 0)
    assertEquals(chapterPosition, 1)
    assertEquals(slotCoordinate, 12.5)
    assertEquals(slotSize, 20.0)
    assertEquals(zoom, 1.0)
    assertEquals(lease, 1000L)
    assertEquals(pageCount, 30)
    assert("".refineOption[Not[Empty]].isEmpty)
    assert("ca".refineOption[Match["[A-Z]{2}"]].isEmpty)
    assert(0.refineOption[Positive].isEmpty)

  test("location and identity wrappers are distinct data types"):
    val relative = RelativeLocation(Paths.get("trip/photo.jpg"))
    val absolute = AbsoluteLocation(Paths.get("/flags/ca.svg"))
    val albumId = AlbumId(UUID.randomUUID())

    assertEquals(relative.value, Paths.get("trip/photo.jpg"))
    assertEquals(absolute.value, Paths.get("/flags/ca.svg"))
    assertNotEquals(albumId.value, UUID.randomUUID())

  test("album and catalog values are immutable records"):
    val albumId = AlbumId(UUID.randomUUID())
    val country = Country(
      CountryId(UUID.randomUUID()),
      "CA".refine[Match["[A-Z]{2}"]],
      "Canada".refine[Not[Empty]],
      AbsoluteLocation(Paths.get("/flags/ca.svg"))
    )
    val album = Album(albumId, "Coast".refine[Not[Empty]], "A photo book")
    val catalog = CountryCatalog(Vector(country))

    assertEquals(album.id, albumId)
    assertEquals(catalog.countries, Vector(country))

  test("spread and slot kinds are represented by typed alternatives"):
    val spreadId = SpreadId(UUID.randomUUID())
    val templateId = SpreadTemplateId(UUID.randomUUID())
    val albumId = AlbumId(UUID.randomUUID())
    val spread: Spread = Spread.AlbumStart(spreadId, albumId, templateId)
    val slot: SpreadSlot = SpreadSlot.Photo(
      PhotoSpreadSlotId(UUID.randomUUID()),
      spreadId,
      SlotBox(
        0.0.refine[GreaterEqual[0.0] & Less[1.7976931348623157E308]],
        0.0.refine[GreaterEqual[0.0] & Less[1.7976931348623157E308]],
        20.0.refine[Positive & Less[1.7976931348623157E308]],
        20.0.refine[Positive & Less[1.7976931348623157E308]]
      ),
      Set(SpreadEdge.Top)
    )

    assert(spread.isInstanceOf[Spread.AlbumStart])
    assert(slot.isInstanceOf[SpreadSlot.Photo])

  test("gear selections use non-empty Typelevel collections"):
    val cameraId = CameraId(UUID.randomUUID())
    val lensId = LensId(UUID.randomUUID())
    val lenses = NonEmptySet.of(lensId)
    val selection: GearSelection =
      NonEmptyMap.of(cameraId -> CameraUse.Interchangeable(lenses))

    assertEquals(lenses.toSortedSet.size, 1)
    assertEquals(selection.toSortedMap.size, 1)

  test("cover and placement values contain typed measurements without deriving geometry"):
    val cover = Cover(
      AlbumId(UUID.randomUUID()),
      SpineOwner.Front,
      CoverSlot(CoverSlotId(UUID.randomUUID())),
      CoverSlot(CoverSlotId(UUID.randomUUID()))
    )
    val transform = PhotoTransform(
      Shift(
        0.0.refine[Greater[-1.7976931348623157E308] & Less[1.7976931348623157E308]],
        0.0.refine[Greater[-1.7976931348623157E308] & Less[1.7976931348623157E308]]
      ),
      mirrored = false,
      1.0.refine[Positive & Less[1.7976931348623157E308]]
    )
    val specification = SaalCoverSpecification(
      30.refine[Positive],
      10000.0.refine[Positive & Less[1.7976931348623157E308]],
      3000.0.refine[Positive & Less[1.7976931348623157E308]],
      150.0.refine[Positive & Less[1.7976931348623157E308]],
      100.0.refine[Positive & Less[1.7976931348623157E308]],
      100.0.refine[Positive & Less[1.7976931348623157E308]],
      80.0.refine[Positive & Less[1.7976931348623157E308]]
    )

    assertEquals(cover.spineOwner, SpineOwner.Front)
    assertEquals(transform.zoom, 1.0)
    assertEquals(specification.pageCount, 30)

  test("editor identity and session state are separate typed records"):
    val editorId = EditorId(UUID.randomUUID())
    val sessionId = SessionId(UUID.randomUUID())
    val session = EditorSession(sessionId, editorId, Instant.EPOCH)
    val state = EditorSessionState(Map(editorId -> session), Set.empty)

    assertEquals(state.sessions(editorId), session)
    assertEquals(session.owner, editorId)
