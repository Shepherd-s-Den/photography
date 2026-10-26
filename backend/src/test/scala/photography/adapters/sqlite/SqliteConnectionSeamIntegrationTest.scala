package photography.adapters.sqlite

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.typelevel.doobie.Fragment
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.app.config.SqliteConfig
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.AlbumRepository.GetResult

import java.nio.file.{Files, Path}
import java.time.{Instant, LocalDate}
import java.util.UUID

final class SqliteConnectionSeamIntegrationTest extends CatsEffectSuite:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  test("should enforce foreign keys on every pooled connection"):
    buildTransactor.use: xa =>
      val invalidWrite = sql"INSERT INTO cover (album_id, spine_owner) VALUES ('missing', 'front')".update.run.transact(xa)

      (invalidWrite.attempt, invalidWrite.attempt).parTupled.map { case (first, second) =>
        assert(first.isLeft)
        assert(second.isLeft)
      }

  test("should migrate a complete V1 album without changing its saved graph, Home identity or reference geometry"):
    val database =
      for
        path <- Resource.make(createDatabaseFile("upgrade-"))(path => IO.blocking(Files.deleteIfExists(path)).void)
        _    <- Resource.eval(migrateV1(path))
        xa   <- SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 2, maxSessions = 2)).buildTransactor
      yield (path, xa)

    database.use { case (path, xa) =>
      val statements = Vector(
        s"INSERT INTO album VALUES ('${uuid(1)}', 2024, 'Saved journeys', 'A year together', 1704067200123)",
        s"""INSERT INTO country VALUES
           ('${uuid(2)}', 'RO', 'Existing Romania', '/custom/ro.svg'), ('${uuid(3)}', 'FR', 'Existing France', '/custom/fr.svg')""",
        s"INSERT INTO destination VALUES ('${uuid(4)}', 'Home'), ('${uuid(5)}', 'Coast')",
        s"INSERT INTO destination_country VALUES ('${uuid(4)}', '${uuid(2)}', 0), ('${uuid(5)}', '${uuid(3)}', 0), ('${uuid(5)}', '${uuid(2)}', 1)",
        s"""INSERT INTO chapter VALUES
           ('${uuid(6)}', '${uuid(1)}', '${uuid(4)}', 'Everyday memories', 1704067200000, 1735689600000, 0, '/photos/Home'),
           ('${uuid(7)}', '${uuid(1)}', '${uuid(5)}', 'Summer by the sea', 1717200000000, 1717372800000, 21, '/photos/Coast')""",
        s"INSERT INTO spread_template VALUES ('${uuid(8)}', 'Saved chapter opening', 'chapter_start'), ('${uuid(9)}', 'Saved photos', 'photos_only')",
        s"""INSERT INTO spread_template_slot (template_id, kind, x, y, width, height) VALUES
           ('${uuid(8)}', 'photo', 1, 2, 48, 96), ('${uuid(8)}', 'text', 51, 3, 47, 94), ('${uuid(9)}', 'photo', 4, 5, 90, 89)""",
        s"""INSERT INTO spread VALUES
           ('${uuid(10)}', '00000000-0000-0000-0000-000000000002', 'album_start', '${uuid(1)}', NULL, 0),
           ('${uuid(11)}', '${uuid(8)}', 'chapter_start', NULL, '${uuid(6)}', 0),
           ('${uuid(12)}', '${uuid(8)}', 'chapter_start', NULL, '${uuid(7)}', 0),
           ('${uuid(13)}', '${uuid(9)}', 'photos_only', NULL, '${uuid(7)}', 22)""",
        s"""INSERT INTO spread_photo_slot VALUES
           ('${uuid(14)}', '${uuid(10)}', 0.293982, 0.914609, 49.601025, 98.170781),
           ('${uuid(15)}', '${uuid(11)}', 1, 2, 48, 96), ('${uuid(16)}', '${uuid(12)}', 1, 2, 48, 96),
           ('${uuid(17)}', '${uuid(13)}', 4, 5, 90, 89)""",
        s"""INSERT INTO spread_text_slot VALUES
           ('${uuid(18)}', '${uuid(10)}', 50.104993, 0.914609, 49.601025, 98.170781),
           ('${uuid(19)}', '${uuid(11)}', 51, 3, 47, 94), ('${uuid(20)}', '${uuid(12)}', 51, 3, 47, 94)""",
        s"INSERT INTO spread_photo_slot_bleed VALUES ('${uuid(17)}', 'left'), ('${uuid(17)}', 'bottom')",
        s"INSERT INTO cover VALUES ('${uuid(1)}', 'back')",
        s"INSERT INTO cover_slot VALUES ('${uuid(21)}', '${uuid(1)}', 'front'), ('${uuid(22)}', '${uuid(1)}', 'back')",
        s"""INSERT INTO photo VALUES
           ('${uuid(23)}', '${uuid(7)}', 'sea.jpg', '/thumbs/sea.jpg', 1717243200123, 6000, 4000, 'Sea breeze'),
           ('${uuid(24)}', '${uuid(6)}', 'home.jpg', NULL, NULL, 4000, 6000, NULL)""",
        s"""INSERT INTO placement VALUES
           ('${uuid(25)}', '${uuid(23)}', 'spread', '${uuid(17)}', NULL, 0.125, -0.25, 1, 1.5, 'bottom_left'),
           ('${uuid(26)}', '${uuid(24)}', 'cover', NULL, '${uuid(21)}', -0.1, 0.2, 0, 1.25, NULL)""",
        s"INSERT INTO camera VALUES ('${uuid(27)}', 'saved-camera', 'Travel camera', 'interchangeable_lens')",
        s"INSERT INTO lens VALUES ('${uuid(28)}', '${uuid(27)}', 'saved-lens', 'Travel lens')",
        s"INSERT INTO chapter_camera VALUES ('${uuid(7)}', '${uuid(27)}', 'interchangeable_lenses')",
        s"INSERT INTO chapter_camera_lens VALUES ('${uuid(7)}', '${uuid(27)}', '${uuid(28)}')",
        s"INSERT INTO editor_session VALUES ('${uuid(29)}', 'saved-owner', 1717243200123)",
        s"INSERT INTO spread_editing_lock VALUES ('${uuid(13)}', '${uuid(29)}')",
        s"INSERT INTO cover_editing_lock VALUES ('${uuid(1)}', '${uuid(29)}')"
      )
      val chapterBox  = SlotBox(x = 1.0, y = 2.0, width = 48.0, height = 96.0)
      val textBox     = SlotBox(x = 51.0, y = 3.0, width = 47.0, height = 94.0)
      val homeOpening = Spread.ChapterStart(
        SpreadId(uuid(11)),
        SpreadTemplateId(uuid(8)),
        photoSlots = Vector(SpreadSlot.Photo(PhotoSpreadSlotId(uuid(15)), chapterBox, bleed = Set.empty)),
        textSlot = SpreadSlot.Text(TextSpreadSlotId(uuid(19)), textBox),
        placements = Vector.empty[SpreadPlacement]
      )
      val vacationOpening = homeOpening.copy(
        id = SpreadId(uuid(12)),
        photoSlots = Vector(SpreadSlot.Photo(PhotoSpreadSlotId(uuid(16)), chapterBox, bleed = Set.empty)),
        textSlot = SpreadSlot.Text(TextSpreadSlotId(uuid(20)), textBox)
      )
      val placement = SpreadPlacement(
        SpreadPlacementId(uuid(25)),
        PhotoSpreadSlotId(uuid(17)),
        PhotoId(uuid(23)),
        PhotoTransform(Shift(x = 0.125, y = -0.25), mirrored = true, zoom = 1.5),
        CaptionCorner.BottomLeft
      )
      val photoSlot = SpreadSlot.Photo(
        PhotoSpreadSlotId(uuid(17)),
        SlotBox(x = 4.0, y = 5.0, width = 90.0, height = 89.0),
        bleed = Set(SpreadEdge.Left, SpreadEdge.Bottom)
      )
      val photoSpread = Spread.PhotosOnly(SpreadId(uuid(13)), SpreadTemplateId(uuid(9)), NonEmptyList.one(photoSlot), Vector(placement))
      val romania     = Country(CountryId(uuid(2)), code = "RO", name = "Existing Romania", AbsoluteLocation(Path.of("/custom/ro.svg")))
      val france      = Country(CountryId(uuid(3)), code = "FR", name = "Existing France", AbsoluteLocation(Path.of("/custom/fr.svg")))
      val home        = Chapter(
        ChapterId(uuid(6)),
        Destination(DestinationId(uuid(4)), name = "Home", NonEmptyList.one(romania)),
        VisitPeriod(firstDay = LocalDate.of(2024, 1, 1), days = 366),
        description = "Everyday memories",
        sourceFolder = "/photos/Home",
        homeOpening,
        spreads = Vector.empty[Spread.PhotosOnly]
      )
      val vacation = Chapter(
        ChapterId(uuid(7)),
        Destination(DestinationId(uuid(5)), name = "Coast", NonEmptyList.of(france, romania)),
        VisitPeriod(firstDay = LocalDate.of(2024, 6, 1), days = 2),
        description = "Summer by the sea",
        sourceFolder = "/photos/Coast",
        vacationOpening,
        spreads = Vector(photoSpread)
      )
      val opening = Spread.AlbumStart(
        SpreadId(uuid(10)),
        SpreadTemplateId(UUID.fromString("00000000-0000-0000-0000-000000000002")),
        photoSlots = Vector(SpreadSlot.Photo(
          PhotoSpreadSlotId(uuid(14)),
          SlotBox(x = 0.293982, y = 0.914609, width = 49.601025, height = 98.170781),
          bleed = Set.empty
        )),
        textSlot = SpreadSlot.Text(TextSpreadSlotId(uuid(18)), SlotBox(x = 50.104993, y = 0.914609, width = 49.601025, height = 98.170781)),
        placements = Vector.empty[SpreadPlacement]
      )
      val expectedAlbum = Album(
        AlbumId(uuid(1)),
        year = 2024,
        title = "Saved journeys",
        synopsis = "A year together",
        createdAt = Instant.ofEpochMilli(1704067200123L),
        opening,
        chapters = Vector(home, vacation)
      )
      val snapshots = Vector(
        "album"                   -> "id, year, title, synopsis, created_at_ms",
        "chapter"                 -> "id, album_id, destination_id, description, start_ms, end_ms, position, source_folder",
        "destination"             -> "id, name",
        "destination_country"     -> "destination_id, country_id, position",
        "photo"                   -> "id, chapter_id, source, thumbnail, captured_at_ms, width, height, caption",
        "camera"                  -> "id, code, friendly_name, kind",
        "lens"                    -> "id, camera_id, code, friendly_name",
        "chapter_camera"          -> "chapter_id, camera_id, use_kind",
        "chapter_camera_lens"     -> "chapter_id, camera_id, lens_id",
        "spread"                  -> "id, template_id, kind, album_id, chapter_id, position",
        "spread_photo_slot"       -> "id, spread_id, x, y, width, height",
        "spread_text_slot"        -> "id, spread_id, x, y, width, height",
        "spread_photo_slot_bleed" -> "slot_id, edge",
        "cover"                   -> "album_id, spine_owner",
        "cover_slot"              -> "id, album_id, side",
        "placement"               -> "id, photo_id, kind, spread_photo_slot_id, cover_slot_id, shift_x, shift_y, mirrored, zoom, caption_corner",
        "editor_session"          -> "id, owner, last_heartbeat_ms",
        "spread_editing_lock"     -> "spread_id, session_id",
        "cover_editing_lock"      -> "album_id, session_id"
      ).map { case (table, columns) => Fragment.const(s"SELECT json_array($columns) FROM $table ORDER BY 1").query[String].to[Vector] }
      val originalCountries = sql"SELECT id, code, name, flag FROM country WHERE code IN ('RO', 'FR') ORDER BY code"
      val originalTemplates = sql"""SELECT id, name, kind FROM spread_template
                                   WHERE id <= '00000000-0000-0000-0000-000000000004' OR id LIKE '90000000-%' ORDER BY id"""
      val originalSlots = sql"""SELECT id, template_id, kind, x, y, width, height FROM spread_template_slot
                               WHERE template_id <= '00000000-0000-0000-0000-000000000004' OR template_id LIKE '90000000-%' ORDER BY id"""
      val homeMarkers   = sql"SELECT id, position, is_home FROM chapter ORDER BY position".query[(String, Int, Int)].to[Vector]
      val duplicateHome = sql"""INSERT INTO chapter (id, album_id, destination_id, description, start_ms, end_ms, position, source_folder, is_home)
                               SELECT ${uuid(30).toString}, album_id, destination_id, description, start_ms, end_ms, 23, '/photos/duplicate', 1
                               FROM chapter WHERE is_home = 1""".update.run

      for
        _               <- statements.traverse_(statement => Fragment.const(statement).update.run).transact(xa)
        before          <- snapshots.sequence.transact(xa)
        countriesBefore <- originalCountries.query[(String, String, String, String)].to[Vector].transact(xa)
        templatesBefore <- originalTemplates.query[(String, String, String)].to[Vector].transact(xa)
        slotsBefore     <- originalSlots.query[(Int, String, String, Double, Double, Double, Double)].to[Vector].transact(xa)

        _              <- FlywayMigrator.make[IO](path).use(_.migrate).void
        found          <- SqliteAlbumRepository.make[IO](xa).get(expectedAlbum.id)
        after          <- snapshots.sequence.transact(xa)
        countriesAfter <- originalCountries.query[(String, String, String, String)].to[Vector].transact(xa)
        templatesAfter <- originalTemplates.query[(String, String, String)].to[Vector].transact(xa)
        slotsAfter     <- originalSlots.query[(Int, String, String, Double, Double, Double, Double)].to[Vector].transact(xa)
        markers        <- homeMarkers.transact(xa)
        duplicate      <- duplicateHome.transact(xa).attempt
        markersAfter   <- homeMarkers.transact(xa)
      yield
        assertEquals(after, before)
        assertEquals(countriesAfter, countriesBefore)
        assertEquals(templatesAfter, templatesBefore)
        assertEquals(slotsAfter, slotsBefore)
        assertEquals(found, GetResult.Found(expectedAlbum))
        assertEquals(markers, Vector((uuid(6).toString, 0, 1), (uuid(7).toString, 21, 0)))
        assert(duplicate.isLeft)
        assertEquals(markersAfter, markers)
    }

  private def migrateV1(path: Path) =
    IO.blocking {
      val source = Flyway.configure().dataSource(s"jdbc:sqlite:$path", "", "").table("flyway").locations("db")
      source.target(MigrationVersion.fromVersion("1")).load().migrate()
    }.void

  private def uuid(index: Int) = new UUID(0x9000000000000000L, index.toLong)

  private def createDatabaseFile(prefix: String) =
    IO.blocking:
      val directory = Files.createDirectories(Path.of(".agent-work/album-workspace-structure/sqlite-migration"))
      Files.createTempFile(directory, prefix, ".sqlite")

  private def buildTransactor =
    for
      path <- Resource.make(createDatabaseFile("connection-"))(path => IO.blocking(Files.deleteIfExists(path)).void)
      _    <- FlywayMigrator.make[IO](path).evalMap(_.migrate)
      xa   <- SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 2, maxSessions = 2)).buildTransactor
    yield xa
