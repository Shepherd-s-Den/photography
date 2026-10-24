package photography.adapters.sqlite

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import munit.CatsEffectSuite
import photography.app.config.SqliteConfig
import photography.domain.album.{Album, AlbumId, AlbumName}
import photography.domain.layout.*
import photography.ports.AlbumRepository.*
import photography.ports.SpreadTemplateRepository.ListAlbumStartTemplatesResult

import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.UUID

final class SqliteAlbumRepositoryIntegrationTest extends CatsEffectSuite:

  private val createdAt         = Instant.parse("2026-10-07T12:00:00Z")
  private val laterCreatedAt    = Instant.parse("2026-10-08T12:00:00Z")
  private val seededTemplateId  = SpreadTemplateId(UUID.fromString("1e63e2b2-1398-54e8-b059-fdec748f4c98"))
  private val seededTemplateNum = 9

  test("should list the seeded album start templates in insertion order"):
    buildMigratedDatabase.use: transactor =>
      val repository = SqliteAlbumRepository.make[IO](transactor)

      for result <- repository.listAlbumStartTemplates
      yield
        val actualTemplateCount = result match
          case ListAlbumStartTemplatesResult.Listed(templates) => templates.toList.size
          case ListAlbumStartTemplatesResult.Failed(_)         => -1
        val actualFirstTemplateId = result match
          case ListAlbumStartTemplatesResult.Listed(templates) => Option(templates.head.id)
          case ListAlbumStartTemplatesResult.Failed(_)         => None
        assertEquals(actualTemplateCount, seededTemplateNum)
        assertEquals(actualFirstTemplateId, Option(seededTemplateId))

  test("should persist an Album and read it back with its spreads and cover"):
    buildMigratedDatabase.use: transactor =>
      val repository = SqliteAlbumRepository.make[IO](transactor)
      val album      = buildAlbum(1, "First album", createdAt)

      for
        createResult <- repository.create(album, buildCover(1))
        getResult    <- repository.get(album.id)
      yield
        assertEquals(createResult, CreateResult.Created)
        assertEquals(getResult, GetResult.Found(album))

  test("should return NotFound when getting an Album that does not exist"):
    buildMigratedDatabase.use: transactor =>
      val repository = SqliteAlbumRepository.make[IO](transactor)
      val missingId  = AlbumId(buildUuid(99))

      for getResult <- repository.get(missingId)
      yield assertEquals(getResult, GetResult.NotFound)

  test("should list Albums newest first with a page and total count"):
    buildMigratedDatabase.use: transactor =>
      val repository = SqliteAlbumRepository.make[IO](transactor)
      val older      = buildAlbum(1, "Older album", createdAt)
      val newer      = buildAlbum(2, "Newer album", laterCreatedAt)

      for
        _          <- repository.create(older, buildCover(1))
        _          <- repository.create(newer, buildCover(2))
        listResult <- repository.list(offset = 0, limit = 1)
      yield
        val expectedSlice = AlbumPageSlice(totalAlbums = 2L, albums = Vector(newer))
        assertEquals(listResult, ListResult.Listed(expectedSlice))

  test("should rename an existing Album and return the updated Album"):
    buildMigratedDatabase.use: transactor =>
      val repository = SqliteAlbumRepository.make[IO](transactor)
      val album      = buildAlbum(1, "Old title", createdAt)
      val newTitle   = "New title".refineUnsafe[Not[Empty] & Not[Blank]]: AlbumName
      val renamed    = album.copy(title = newTitle)

      for
        _            <- repository.create(album, buildCover(1))
        renameResult <- repository.rename(album.id, newTitle)
      yield assertEquals(renameResult, RenameResult.Renamed(renamed))

  test("should return NotFound when renaming an Album that does not exist"):
    buildMigratedDatabase.use: transactor =>
      val repository = SqliteAlbumRepository.make[IO](transactor)
      val missingId  = AlbumId(buildUuid(99))
      val newTitle   = "Title".refineUnsafe[Not[Empty] & Not[Blank]]: AlbumName

      for renameResult <- repository.rename(missingId, newTitle)
      yield assertEquals(renameResult, RenameResult.NotFound)

  test("should delete an Album along with its spreads and cover"):
    buildMigratedDatabase.use: transactor =>
      val repository = SqliteAlbumRepository.make[IO](transactor)
      val album      = buildAlbum(1, "Doomed album", createdAt)

      for
        _            <- repository.create(album, buildCover(1))
        deleteResult <- repository.delete(album.id)
        getResult    <- repository.get(album.id)
      yield
        assertEquals(deleteResult, DeleteResult.Deleted)
        assertEquals(getResult, GetResult.NotFound)

  test("should return NotFound when deleting an Album that does not exist"):
    buildMigratedDatabase.use: transactor =>
      val repository = SqliteAlbumRepository.make[IO](transactor)
      val missingId  = AlbumId(buildUuid(99))

      for deleteResult <- repository.delete(missingId)
      yield assertEquals(deleteResult, DeleteResult.NotFound)

  test("should reject an Album whose spread already carries placements"):
    buildMigratedDatabase.use: transactor =>
      val repository = SqliteAlbumRepository.make[IO](transactor)
      val album      = buildAlbumWithPlacement(1)

      for createResult <- repository.create(album, buildCover(1))
      yield assertEquals(createResult, CreateResult.Failed(Failure.InvalidAlbumData))

  test("should roll back a failed create so the same Album can be created afterwards"):
    buildMigratedDatabase.use: transactor =>
      val repository   = SqliteAlbumRepository.make[IO](transactor)
      val album        = buildAlbum(1, "Retry album", createdAt)
      val repeatedSlot = album.spreads.head match
        case start: Spread.AlbumStart => start.slots.headOption.getOrElse(fail("expected a slot"))
        case _                        => fail("expected an AlbumStart spread")
      val invalidAlbum = album.copy(spreads = NonEmptyList.one(withSlots(album.spreads.head, Vector(repeatedSlot, repeatedSlot))))

      for
        failedResult <- repository.create(invalidAlbum, buildCover(1))
        afterFailure <- repository.get(album.id)
        retried      <- repository.create(album, buildCover(1))
        afterRetry   <- repository.get(album.id)
      yield
        assertEquals(failedResult, CreateResult.Failed(Failure.DatabaseFailure))
        assertEquals(afterFailure, GetResult.NotFound)
        assertEquals(retried, CreateResult.Created)
        assertEquals(afterRetry, GetResult.Found(album))

  private def withSlots(spread: Spread, slots: Vector[SpreadSlot]): Spread = spread match
    case start: Spread.AlbumStart => start.copy(slots = slots)
    case other                    => other

  private def buildAlbum(index: Int, title: String, instant: Instant): Album =
    val albumId   = AlbumId(buildUuid(index))
    val spreadId  = SpreadId(buildUuid(index * 10 + 1))
    val slotId    = TextSpreadSlotId(buildUuid(index * 10 + 2))
    val slots     = Vector(SpreadSlot.Text(slotId, spreadId, buildFullSpreadBox))
    val start     = Spread.AlbumStart(spreadId, albumId, seededTemplateId, slots, Vector.empty[SpreadPlacement])
    val albumName = title.refineUnsafe[Not[Empty] & Not[Blank]]: AlbumName
    Album(albumId, albumName, "A synopsis", instant, NonEmptyList.one(start))

  private def buildAlbumWithPlacement(index: Int): Album =
    val album  = buildAlbum(index, "Placed album", createdAt)
    val spread = album.spreads.head match
      case start: Spread.AlbumStart => start
      case _                        => fail("expected an AlbumStart spread")
    val placement = buildSpreadPlacement(index)
    album.copy(spreads = NonEmptyList.one(spread.copy(placements = Vector(placement))))

  private def buildCover(index: Int): Cover =
    val albumId   = AlbumId(buildUuid(index))
    val frontSlot = CoverSlot(CoverSlotId(buildUuid(index * 10 + 3)))
    val backSlot  = CoverSlot(CoverSlotId(buildUuid(index * 10 + 4)))
    Cover(albumId, SpineOwner.Front, frontSlot, backSlot)

  private def buildFullSpreadBox: SlotBox =
    val origin = 0.0.refineUnsafe[GreaterEqual[0.0] & Less[1.7976931348623157e308]]
    val unit   = 1.0.refineUnsafe[Positive & Less[1.7976931348623157e308]]
    SlotBox(origin, origin, unit, unit)

  private def buildSpreadPlacement(index: Int): SpreadPlacement =
    val zero: InDesignPoints = 0.0.refineUnsafe[Greater[-1.7976931348623157e308] & Less[1.7976931348623157e308]]
    val noZoom: Zoom         = 1.0.refineUnsafe[Positive & Less[1.7976931348623157e308]]
    val transform            = PhotoTransform(Shift(zero, zero), mirrored = false, noZoom)
    SpreadPlacement(
      SpreadPlacementId(buildUuid(index * 10 + 5)),
      PhotoSpreadSlotId(buildUuid(index * 10 + 6)),
      photoId(index),
      transform,
      CaptionCorner.BottomLeft
    )

  private def photoId(index: Int) = photography.domain.album.PhotoId(buildUuid(index * 10 + 7))

  private def buildUuid(index: Int): UUID =
    val suffix = index.toString.reverse.padTo(12, '0').reverse
    UUID.fromString(s"00000000-0000-4000-8000-$suffix")

  private def buildTempDatabasePath = IO.blocking(Files.createTempFile("photography-album-repository", ".sqlite"))

  private def deleteDatabaseFile(path: Path) = IO.blocking(Files.deleteIfExists(path)).void

  private def buildMigratedDatabase =
    for
      path       <- Resource.make(buildTempDatabasePath)(deleteDatabaseFile)
      transactor <- SqliteConnection.make[IO](SqliteConfig(path, 1, 1)).transactor
      flyway     <- photography.adapters.sqlite.FlywayMigrator.make[IO](path)
      _          <- Resource.eval(flyway.migrate)
    yield transactor
