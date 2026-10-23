package photography.adapters.sqlite

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import munit.CatsEffectSuite
import photography.app.SqliteConfig
import photography.app.album.AlbumRepositoryFailure
import photography.domain.album.{Album, AlbumId, PhotoId}
import photography.domain.layout.*

import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.UUID

final class SqliteAlbumRepositorySeamIntegrationTest extends CatsEffectSuite:

  private val createdAt = Instant.parse("2026-10-07T12:00:00Z")

  test("should transactionally persist and read the Album while listing it"):
    buildMigratedDatabase.use: transactor =>
      val repository            = SqliteAlbumRepository.make[IO](transactor)
      val (album, start, cover) = buildInitialState

      for
        created <- repository.create(album, start, cover)
        loaded  <- repository.getAlbum(album.id)
        listed  <- repository.list(offset = 0, limit = 6)
      yield
        assertEquals(created, ().asRight[AlbumRepositoryFailure])
        assertEquals(loaded, album.some.asRight[AlbumRepositoryFailure])
        assertEquals(listed.map(_.albums.map(_.id)), Vector(album.id).asRight[AlbumRepositoryFailure])

  test("should reject initial resources that do not belong together or contain existing placements"):
    val (album, start, cover) = buildInitialState
    val unrelatedSlots        = start.slots.collect { case SpreadSlot.Text(id, _, box) => SpreadSlot.Text(id, SpreadId(buildUuid(99)), box) }
    val unrelatedSlot         = unrelatedSlots.headOption.getOrElse(fail("expected a text slot"))
    val invalidAlbumId        = (album, start.copy(album = AlbumId(buildUuid(98))), cover)
    val invalidPlacements     = (album, start.copy(placements = Vector(buildSampleSpreadPlacement)), cover)
    val invalidSlots          = (album, start.copy(slots = Vector(unrelatedSlot)), cover)
    val invalidCoverAlbum     = (album, start, cover.copy(album = AlbumId(buildUuid(97))))
    val invalidStates         = Vector(invalidAlbumId, invalidPlacements, invalidSlots, invalidCoverAlbum)

    buildMigratedDatabase.use: transactor =>
      val repository = SqliteAlbumRepository.make[IO](transactor)

      for
        results <- invalidStates.traverse { case (a, s, c) => repository.create(a, s, c) }
        reads   <- invalidStates.traverse(_ => repository.getAlbum(album.id))
        created <- repository.create(album, start, cover)
        loaded  <- repository.getAlbum(album.id)
      yield
        val invalidResult = AlbumRepositoryFailure.InvalidAlbumData.asLeft[Unit]
        val missingAlbum  = Option.empty[Album].asRight[AlbumRepositoryFailure]
        assertEquals(results, Vector.fill(invalidStates.size)(invalidResult))
        assertEquals(reads, Vector.fill(invalidStates.size)(missingAlbum))
        assertEquals(created, ().asRight[AlbumRepositoryFailure])
        assertEquals(loaded, album.some.asRight[AlbumRepositoryFailure])

  test("should rename, delete, and recreate an Album through repository APIs"):
    buildMigratedDatabase.use: transactor =>
      val repository            = SqliteAlbumRepository.make[IO](transactor)
      val (album, start, cover) = buildInitialState
      val renamedTitle          = "Retitled".refineUnsafe[Not[Empty]]
      val expectedAlbum         = album.copy(title = renamedTitle)

      for
        _             <- repository.create(album, start, cover)
        renamed       <- repository.rename(album.id, renamedTitle)
        deleted       <- repository.delete(album.id)
        afterDelete   <- repository.getAlbum(album.id)
        listed        <- repository.list(offset = 0, limit = 6)
        recreated     <- repository.create(album, start, cover)
        afterRecreate <- repository.getAlbum(album.id)
      yield
        assertEquals(renamed, expectedAlbum.some.asRight[AlbumRepositoryFailure])
        assertEquals(deleted, true.asRight[AlbumRepositoryFailure])
        assertEquals(afterDelete, Option.empty[Album].asRight[AlbumRepositoryFailure])
        assertEquals(listed.map(_.albums), Vector.empty[Album].asRight[AlbumRepositoryFailure])
        assertEquals(recreated, ().asRight[AlbumRepositoryFailure])
        assertEquals(afterRecreate, album.some.asRight[AlbumRepositoryFailure])

  test("should roll back a failed create before allowing the same Album to be created"):
    buildMigratedDatabase.use: transactor =>
      val repository            = SqliteAlbumRepository.make[IO](transactor)
      val (album, start, cover) = buildInitialState
      val repeatedSlot          = start.slots.headOption.fold(fail("expected a text slot"))(identity)
      val invalidStart          = start.copy(slots = Vector(repeatedSlot, repeatedSlot))

      for
        result       <- repository.create(album, invalidStart, cover)
        afterFailure <- repository.getAlbum(album.id)
        retried      <- repository.create(album, start, cover)
        afterRetry   <- repository.getAlbum(album.id)
      yield
        assert(result.isLeft)
        assertEquals(afterFailure, Option.empty[Album].asRight[AlbumRepositoryFailure])
        assertEquals(retried, ().asRight[AlbumRepositoryFailure])
        assertEquals(afterRetry, album.some.asRight[AlbumRepositoryFailure])

  private def buildInitialState =
    val albumId   = AlbumId(buildUuid(1))
    val spreadId  = SpreadId(buildUuid(11))
    val slotId    = TextSpreadSlotId(buildUuid(21))
    val frontSlot = CoverSlot(CoverSlotId(buildUuid(31)))
    val backSlot  = CoverSlot(CoverSlotId(buildUuid(41)))
    val box       = buildFullSpreadBox
    val album     = Album(albumId, "Album".refineUnsafe[Not[Empty]], "A synopsis", createdAt)
    val slots     = Vector(SpreadSlot.Text(slotId, spreadId, box))
    val start     = Spread.AlbumStart(spreadId, albumId, SpreadTemplateId(buildUuid(1)), slots, Vector.empty[SpreadPlacement])
    val cover     = Cover(albumId, SpineOwner.Front, frontSlot, backSlot)
    (album, start, cover)

  private def buildFullSpreadBox =
    val x      = 0.0.refineUnsafe[GreaterEqual[0.0] & Less[1.7976931348623157e308]]
    val y      = 0.0.refineUnsafe[GreaterEqual[0.0] & Less[1.7976931348623157e308]]
    val width  = 1.0.refineUnsafe[Positive & Less[1.7976931348623157e308]]
    val height = 1.0.refineUnsafe[Positive & Less[1.7976931348623157e308]]
    SlotBox(x, y, width, height)

  private def buildSampleSpreadPlacement =
    val shiftedZero: InDesignPoints = 0.0.refineUnsafe[Greater[-1.7976931348623157e308] & Less[1.7976931348623157e308]]
    val noZoom: Zoom                = 1.0.refineUnsafe[Positive & Less[1.7976931348623157e308]]
    val transform                   = PhotoTransform(Shift(shiftedZero, shiftedZero), mirrored = false, noZoom)
    SpreadPlacement(SpreadPlacementId(buildUuid(50)), PhotoSpreadSlotId(buildUuid(51)), PhotoId(buildUuid(52)), transform, CaptionCorner.BottomLeft)

  private def buildUuid(index: Int) =
    val suffix = index.toString.reverse.padTo(12, '0').reverse
    UUID.fromString(s"00000000-0000-4000-8000-$suffix")

  private def buildTempDatabasePath = IO.blocking(Files.createTempFile("photography-album-repository", ".sqlite"))

  private def deleteDatabaseFile(path: Path) = IO.blocking(Files.deleteIfExists(path)).void

  private def buildMigratedDatabase =
    for
      path       <- Resource.make(buildTempDatabasePath)(deleteDatabaseFile)
      transactor <- SqliteConnection.make[IO](SqliteConfig(path, 1, 1)).transactor
      flyway     <- FlywayMigrator.make[IO](path)
      _          <- Resource.eval(flyway.migrate)
    yield transactor
