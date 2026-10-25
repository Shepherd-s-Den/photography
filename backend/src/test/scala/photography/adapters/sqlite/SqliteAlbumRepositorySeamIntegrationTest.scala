package photography.adapters.sqlite

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import munit.CatsEffectSuite
import org.typelevel.doobie.*
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.app.config.SqliteConfig
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.AlbumRepository.*

import java.time.{Instant, LocalDate}
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

final class SqliteAlbumRepositorySeamIntegrationTest extends CatsEffectSuite:
  private given Logger[IO]           = Slf4jLogger.getLogger[IO]
  private val createdAt              = Instant.parse("2026-10-07T12:00:00.123Z")
  private val changedYear: AlbumYear = 2026
  private val countInitialRows       = sql"""SELECT (SELECT count(*) FROM album) + (SELECT count(*) FROM spread) +
                                      (SELECT count(*) FROM spread_photo_slot) + (SELECT count(*) FROM spread_text_slot) +
                                      (SELECT count(*) FROM spread_photo_slot_bleed) +
                                      (SELECT count(*) FROM cover) + (SELECT count(*) FROM cover_slot)""".query[Long].unique

  test("should persist the complete initial Album and cover atomically and preserve composition when its year changes"):
    Vector(SpineOwner.Front -> "front", SpineOwner.Back -> "back").traverse_ { case (spineOwner, expectedSpine) =>
      buildTransactor.use: xa =>
        val repository = SqliteAlbumRepository.make[IO](xa)
        val album      = buildAlbum(1)
        val cover      = buildCover(album).copy(spineOwner = spineOwner)

        for
          created  <- repository.create(album, cover)
          found    <- repository.get(album.id)
          spine    <- sql"SELECT spine_owner FROM cover".query[String].unique.transact(xa)
          sides    <- sql"SELECT side FROM cover_slot ORDER BY side".query[String].to[Vector].transact(xa)
          renamed  <- repository.changeYear(album.id, changedYear)
          reloaded <- repository.get(album.id)
        yield
          assertEquals(created, CreateResult.Created)
          assertEquals(found, GetResult.Found(album))
          assertEquals(spine, expectedSpine)
          assertEquals(sides, Vector("back", "front"))
          assertEquals(renamed, ChangeYearResult.Changed(album.copy(year = changedYear)))
          assertEquals(reloaded, GetResult.Found(album.copy(year = changedYear)))
    }

  test("should list newest-first slices with stable ordering for equal creation instants and an empty collection"):
    buildTransactor.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa)
      val albums     = Vector(1, 2, 3).map(buildAlbum)

      for
        empty  <- repository.list(offset = 0, limit = 6)
        _      <- albums.traverse_(album => repository.create(album, buildCover(album)))
        first  <- repository.list(offset = 0, limit = 2)
        last   <- repository.list(offset = 2, limit = 2)
        beyond <- repository.list(offset = 3, limit = 2)
      yield
        val newestFirst = albums.take(1) ++ albums.drop(1).reverse
        assertEquals(empty, ListResult.Listed(AlbumPageSlice(0, Vector.empty)))
        assertEquals(first, ListResult.Listed(AlbumPageSlice(3, newestFirst.take(2))))
        assertEquals(last, ListResult.Listed(AlbumPageSlice(3, newestFirst.drop(2))))
        assertEquals(beyond, ListResult.Listed(AlbumPageSlice(3, Vector.empty)))

  test("should enforce unique required years atomically under concurrent creation and roll back conflicting year changes"):
    buildTransactor.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa)
      val first      = buildAlbum(1)
      val second     = buildAlbum(2).copy(year = first.year)
      val later      = buildAlbum(3)

      for
        results   <- Vector(first, second).parTraverse(album => repository.create(album, buildCover(album)))
        before    <- countInitialRows.transact(xa)
        _         <- repository.create(later, buildCover(later))
        rejected  <- repository.changeYear(later.id, first.year)
        loaded    <- repository.get(later.id)
        unchanged <- repository.changeYear(later.id, later.year)
        after     <- countInitialRows.transact(xa)
      yield
        assertEquals(results.collect { case CreateResult.Created => () }.size, 1)
        assertEquals(results.collect { case CreateResult.Failed(Failure.YearAlreadyUsed) => () }.size, 1)
        assertEquals(rejected, ChangeYearResult.Failed(Failure.YearAlreadyUsed))
        assertEquals(loaded, GetResult.Found(later))
        assertEquals(unchanged, ChangeYearResult.Changed(later))
        assertEquals(after, before * 2L)

  test("should report unknown albums consistently and delete all initial owned rows without deleting shared templates"):
    buildTransactor.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa)
      val unknownId  = AlbumId(buildUuid(999))
      val album      = buildAlbum(1)

      for
        _             <- repository.create(album, buildCover(album))
        missing       <- repository.get(unknownId)
        missingRename <- repository.changeYear(unknownId, changedYear)
        missingDelete <- repository.delete(unknownId)
        deleted       <- repository.delete(album.id)
        removed       <- repository.get(album.id)
        owned         <- countInitialRows.transact(xa)
        templates     <- sql"SELECT count(*) FROM spread_template".query[Long].unique.transact(xa)
      yield
        assertEquals(missing, GetResult.NotFound)
        assertEquals(missingRename, ChangeYearResult.NotFound)
        assertEquals(missingDelete, DeleteResult.NotFound)
        assertEquals(deleted, DeleteResult.Deleted)
        assertEquals(removed, GetResult.NotFound)
        assertEquals(owned, 0L)
        assertEquals(templates, 4L)

  test("should roll back every album-owned row when a late cover write fails"):
    buildTransactor.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa)
      val failCover  = sql"""CREATE TRIGGER fail_cover BEFORE INSERT ON cover_slot WHEN NEW.side = 'back'
                           BEGIN SELECT RAISE(ABORT, 'injected cover failure'); END"""
      val album = buildAlbum(1)

      for
        _      <- failCover.update.run.transact(xa)
        result <- repository.create(album, buildCover(album))
        count  <- countInitialRows.transact(xa)
      yield
        assertEquals(result, CreateResult.Failed(Failure.DatabaseFailure))
        assertEquals(count, 0L)

  test("should reject noninitial composition or a foreign cover without writing owned rows"):
    buildTransactor.use: xa =>
      val repository     = SqliteAlbumRepository.make[IO](xa)
      val album          = buildAlbum(1)
      val chapterOpening = Spread.ChapterStart(
        SpreadId(buildUuid(10)),
        SpreadTemplateId(buildUuid(1)),
        photoSlots = Vector.empty[SpreadSlot.Photo],
        SpreadSlot.Text(TextSpreadSlotId(buildUuid(11)), SlotBox(0.0, 0.0, 100.0, 100.0)),
        placements = Vector.empty[SpreadPlacement]
      )
      val country = Country(CountryId(buildUuid(12)), "RO", "Romania", AbsoluteLocation(Path.of("/flags/ro.svg")))
      val home    = Chapter(
        ChapterId(buildUuid(13)),
        Destination(DestinationId(buildUuid(14)), "Home", country),
        VisitPeriod(LocalDate.of(1901, 1, 1), 365),
        "",
        "/photos/Home",
        chapterOpening,
        spreads = Vector.empty[Spread.PhotosOnly]
      )

      Vector(
        album.copy(chapters = Vector(home)) -> buildCover(album),
        album                               -> buildCover(album).copy(album = AlbumId(buildUuid(999)))
      ).traverse_ { case (invalidAlbum, invalidCover) =>
        for
          result <- repository.create(invalidAlbum, invalidCover)
          count  <- countInitialRows.transact(xa)
        yield
          assertEquals(result, CreateResult.Failed(Failure.DatabaseFailure))
          assertEquals(count, 0L)
      }

  test("should reject incomplete or invalid persisted albums rather than returning partial success"):
    Vector(
      "DELETE FROM cover",
      "DELETE FROM cover_slot WHERE side = 'front'",
      "DELETE FROM cover_slot WHERE side = 'back'",
      "DELETE FROM spread_text_slot",
      "UPDATE album SET year = 1899",
      "UPDATE spread_photo_slot SET x = x + 1",
      "UPDATE spread_template SET kind = 'photos_only' WHERE id = '00000000-0000-0000-0000-000000000001'",
      "DELETE FROM spread",
      "UPDATE spread_photo_slot SET width = -1",
      "UPDATE spread_text_slot SET id = '0-0-0-0-1'"
    ).traverse_ { corruption =>
      buildTransactor.use: xa =>
        val repository = SqliteAlbumRepository.make[IO](xa)
        val album      = buildAlbum(1)

        for
          _       <- repository.create(album, buildCover(album))
          _       <- (sql"PRAGMA ignore_check_constraints=ON".update.run >> Fragment.const(corruption).update.run).transact(xa)
          found   <- repository.get(album.id)
          listed  <- repository.list(offset = 0, limit = 6)
          before  <- sql"SELECT year FROM album".query[Int].unique.transact(xa)
          changed <- repository.changeYear(album.id, changedYear)
          after   <- sql"SELECT year FROM album".query[Int].unique.transact(xa)
          deleted <- repository.delete(album.id)
          removed <- repository.get(album.id)
        yield
          assertEquals(found, GetResult.Failed(Failure.DatabaseFailure))
          assertEquals(listed, ListResult.Failed(Failure.DatabaseFailure))
          assertEquals(changed, ChangeYearResult.Failed(Failure.DatabaseFailure))
          assertEquals(after, before)
          assertEquals(deleted, DeleteResult.Deleted)
          assertEquals(removed, GetResult.NotFound)
    }

  test("should report database failures explicitly for every operation"):
    buildTransactor.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa)
      val album      = buildAlbum(1)

      for
        _       <- sql"DROP TABLE album".update.run.transact(xa)
        listed  <- repository.list(offset = 0, limit = 6)
        created <- repository.create(album, buildCover(album))
        found   <- repository.get(album.id)
        renamed <- repository.changeYear(album.id, changedYear)
        deleted <- repository.delete(album.id)
      yield
        assertEquals(listed, ListResult.Failed(Failure.DatabaseFailure))
        assertEquals(created, CreateResult.Failed(Failure.DatabaseFailure))
        assertEquals(found, GetResult.Failed(Failure.DatabaseFailure))
        assertEquals(renamed, ChangeYearResult.Failed(Failure.DatabaseFailure))
        assertEquals(deleted, DeleteResult.Failed(Failure.DatabaseFailure))

  test("should preserve persisted photo-slot bleed when reading an album"):
    buildTransactor.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa)
      val album      = buildAlbum(1)

      for
        _      <- repository.create(album, buildCover(album))
        slotId <- sql"SELECT id FROM spread_photo_slot ORDER BY id LIMIT 1".query[String].unique.transact(xa)
        _      <- sql"INSERT INTO spread_photo_slot_bleed (slot_id, edge) VALUES ($slotId, 'left')".update.run.transact(xa)
        found  <- repository.get(album.id)
      yield
        val expectedSlots = album.opening.photoSlots.map:
          case slot if slot.id.value.toString === slotId => slot.copy(bleed = slot.bleed + SpreadEdge.Left)
          case slot                                      => slot
        val expectedAlbum = album.copy(opening = album.opening.copy(photoSlots = expectedSlots))
        assertEquals(found, GetResult.Found(expectedAlbum))

  test("should read chapter spreads and placements in domain order and cascade deletion through the owned graph"):
    buildTransactor.use: xa =>
      val repository         = SqliteAlbumRepository.make[IO](xa)
      val countryId          = buildUuid(400).toString
      val destinationId      = buildUuid(401).toString
      val chapterId          = buildUuid(402).toString
      val photoId            = buildUuid(403).toString
      val templateId         = buildUuid(404).toString
      val spreadId           = buildUuid(405).toString
      val slotId             = buildUuid(406).toString
      val placementId        = buildUuid(407).toString
      val sessionId          = buildUuid(408).toString
      val albumId            = buildUuid(1).toString
      val photosTemplateId   = buildUuid(409).toString
      val photosSpreadId     = buildUuid(410).toString
      val photosSlotId       = buildUuid(411).toString
      val insertTemplateSlot = sql"""INSERT INTO spread_template_slot (template_id, kind, x, y, width, height)
                                     VALUES ($templateId, 'photo', 0, 0, 100, 100)"""
      val insertChapterStart = sql"""INSERT INTO spread (id, template_id, kind, chapter_id, position)
                                     VALUES ($spreadId, $templateId, 'chapter_start', $chapterId, 0)"""
      val insertPlacement = sql"""INSERT INTO placement
                                  (id, photo_id, kind, spread_photo_slot_id, shift_x, shift_y, mirrored, zoom, caption_corner)
                                  VALUES ($placementId, $photoId, 'spread', $slotId, 0, 0, 0, 1, 'bottom_left')"""
      val insertPhotosTemplateSlot = sql"""INSERT INTO spread_template_slot (template_id, kind, x, y, width, height)
                                           VALUES ($photosTemplateId, 'photo', 0, 0, 100, 100)"""
      val insertPhotosSpread = sql"""INSERT INTO spread (id, template_id, kind, chapter_id, position)
                                     VALUES ($photosSpreadId, $photosTemplateId, 'photos_only', $chapterId, 1)"""
      val countOwned = sql"""SELECT (SELECT count(*) FROM album) + (SELECT count(*) FROM chapter) +
                             (SELECT count(*) FROM photo) + (SELECT count(*) FROM spread) +
                             (SELECT count(*) FROM placement) + (SELECT count(*) FROM spread_editing_lock) +
                             (SELECT count(*) FROM cover_editing_lock)""".query[Long].unique
      val countShared = sql"""SELECT (SELECT count(*) FROM country), (SELECT count(*) FROM destination),
                              (SELECT count(*) FROM spread_template), (SELECT count(*) FROM editor_session)"""
        .query[(Long, Long, Long, Long)].unique
      val initialiseChapter =
        for
          _ <- sql"INSERT INTO country VALUES ($countryId, 'IS', 'Iceland', '/flags/is.svg')".update.run
          _ <- sql"INSERT INTO destination VALUES ($destinationId, 'Coast', $countryId)".update.run
          _ <- sql"INSERT INTO chapter VALUES ($chapterId, $albumId, $destinationId, '', 0, 86400000, 0, '/photos/coast')".update.run
          _ <- sql"INSERT INTO photo VALUES ($photoId, $chapterId, 'photo.jpg', NULL, NULL, 100, 100, NULL)".update.run
          _ <- sql"INSERT INTO spread_template VALUES ($templateId, 'Chapter', 'chapter_start')".update.run
          _ <- insertTemplateSlot.update.run
          _ <- sql"INSERT INTO spread_template_slot (template_id, kind, x, y, width, height) VALUES ($templateId, 'text', 0, 0, 100, 100)".update.run
          _ <- insertChapterStart.update.run
          _ <- sql"INSERT INTO spread_photo_slot VALUES ($slotId, $spreadId, 0, 0, 100, 100)".update.run
          _ <- sql"INSERT INTO spread_text_slot VALUES (${buildUuid(412).toString}, $spreadId, 0, 0, 100, 100)".update.run
          _ <- insertPlacement.update.run
          _ <- sql"INSERT INTO spread_template VALUES ($photosTemplateId, 'Photos', 'photos_only')".update.run
          _ <- insertPhotosTemplateSlot.update.run
          _ <- insertPhotosSpread.update.run
          _ <- sql"INSERT INTO spread_photo_slot VALUES ($photosSlotId, $photosSpreadId, 0, 0, 100, 100)".update.run
          _ <- sql"INSERT INTO editor_session VALUES ($sessionId, 'owner', 0)".update.run
          _ <- sql"INSERT INTO spread_editing_lock VALUES ($spreadId, $sessionId)".update.run
          _ <- sql"INSERT INTO cover_editing_lock VALUES ($albumId, $sessionId)".update.run
        yield ()
      val corruptions = Vector(
        (s"UPDATE chapter SET destination_id = '${buildUuid(499)}'", s"UPDATE chapter SET destination_id = '$destinationId'"),
        (s"UPDATE destination SET country_id = '${buildUuid(499)}'", s"UPDATE destination SET country_id = '$countryId'"),
        ("UPDATE chapter SET source_folder = 'relative'", "UPDATE chapter SET source_folder = '/photos/coast'"),
        ("UPDATE chapter SET source_folder = '/photos/../coast'", "UPDATE chapter SET source_folder = '/photos/coast'"),
        ("UPDATE destination SET name = 'Home'", "UPDATE destination SET name = 'Coast'"),
        ("UPDATE chapter SET position = -1", "UPDATE chapter SET position = 0"),
        (
          "UPDATE spread_template SET kind = 'photos_only' WHERE name = 'Chapter'",
          "UPDATE spread_template SET kind = 'chapter_start' WHERE name = 'Chapter'"
        ),
        (
          "UPDATE spread SET kind = 'photos_only', position = 2 WHERE position = 0 AND chapter_id IS NOT NULL",
          "UPDATE spread SET kind = 'chapter_start', position = 0 WHERE position = 2 AND chapter_id IS NOT NULL"
        )
      )
      def rejectCorruptions(album: Album) =
        corruptions.traverse_ { case (corruption, repair) =>
          val corrupt = sql"PRAGMA foreign_keys = OFF".update.run >> Fragment.const(corruption).update.run >>
            sql"PRAGMA foreign_keys = ON".update.run
          for
            _       <- xa.rawTrans.apply(corrupt)
            invalid <- repository.get(album.id)
            _       <- Fragment.const(repair).update.run.transact(xa)
          yield assertEquals(invalid, GetResult.Failed(Failure.DatabaseFailure))
        }
      val album = buildAlbum(1)

      for
        _         <- repository.create(album, buildCover(album))
        _         <- initialiseChapter.transact(xa)
        found     <- repository.get(album.id)
        unchanged <- repository.changeYear(album.id, album.year)
        rejected  <- repository.changeYear(album.id, changedYear)
        _         <- rejectCorruptions(album)
        deleted   <- repository.delete(album.id)
        owned     <- countOwned.transact(xa)
        shared    <- countShared.transact(xa)
      yield
        val box               = SlotBox(0.0.refineUnsafe, 0.0.refineUnsafe, 100.0.refineUnsafe, 100.0.refineUnsafe)
        val expectedSlot      = SpreadSlot.Photo(PhotoSpreadSlotId(buildUuid(406)), box, bleed = Set.empty[SpreadEdge])
        val transform         = PhotoTransform(Shift(0.0.refineUnsafe, 0.0.refineUnsafe), mirrored = false, 1.0.refineUnsafe)
        val expectedPlacement =
          SpreadPlacement(
            SpreadPlacementId(buildUuid(407)),
            PhotoSpreadSlotId(buildUuid(406)),
            PhotoId(buildUuid(403)),
            transform,
            CaptionCorner.BottomLeft
          )
        val expectedChapter = Spread.ChapterStart(
          SpreadId(buildUuid(405)),
          SpreadTemplateId(buildUuid(404)),
          Vector(expectedSlot),
          SpreadSlot.Text(TextSpreadSlotId(buildUuid(412)), box),
          Vector(expectedPlacement)
        )
        val expectedPhotosSlot =
          SpreadSlot.Photo(PhotoSpreadSlotId(buildUuid(411)), box, bleed = Set.empty[SpreadEdge])
        val expectedPhotos = Spread.PhotosOnly(
          SpreadId(buildUuid(410)),
          SpreadTemplateId(buildUuid(409)),
          NonEmptyList.one(expectedPhotosSlot),
          placements = Vector.empty[SpreadPlacement]
        )
        val expectedCountry = Country(CountryId(buildUuid(400)), "IS", "Iceland", AbsoluteLocation(Path.of("/flags/is.svg")))
        val destination     = Destination(DestinationId(buildUuid(401)), "Coast", expectedCountry)
        val chapter         =
          Chapter(
            ChapterId(buildUuid(402)),
            destination,
            VisitPeriod(LocalDate.of(1970, 1, 1), 1),
            "",
            "/photos/coast",
            expectedChapter,
            Vector(expectedPhotos)
          )
        val expectedAlbum = album.copy(chapters = Vector(chapter))
        assertEquals(found, GetResult.Found(expectedAlbum))
        assertEquals(unchanged, ChangeYearResult.Changed(expectedAlbum))
        assertEquals(rejected, ChangeYearResult.HasChapters)
        assertEquals(deleted, DeleteResult.Deleted)
        assertEquals(owned, 0L)
        assertEquals(shared, (1L, 1L, 6L, 1L))

  private def buildTransactor =
    for
      path <- Resource.make(IO.blocking(Files.createTempFile("album-", ".sqlite")))(path => IO.blocking(Files.deleteIfExists(path)).void)
      _    <- FlywayMigrator.make[IO](path).evalMap(_.migrate)
      xa   <- SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 2, maxSessions = 2)).transactor
    yield xa

  private def buildAlbum(index: Int) =
    val albumId  = AlbumId(buildUuid(index))
    val spreadId = SpreadId(buildUuid(index + 100))
    val boxes    = Vector(
      SlotBox(0.293982, 0.914609, 24.695519, 48.758744),
      SlotBox(25.199487, 0.914609, 24.695519, 48.758744),
      SlotBox(0.293982, 50.326646, 24.695519, 48.758744),
      SlotBox(25.199487, 50.326646, 24.695519, 48.758744)
    )
    val photos = boxes.zipWithIndex.map { case (box, position) =>
      SpreadSlot.Photo(PhotoSpreadSlotId(buildUuid(index * 1000 + position)), box, bleed = Set.empty[SpreadEdge])
    }
    val text           = SpreadSlot.Text(TextSpreadSlotId(buildUuid(index * 1000 + 4)), SlotBox(50.104993, 0.914609, 49.601025, 98.170781))
    val start          = Spread.AlbumStart(spreadId, SpreadTemplateId(buildUuid(1)), photos, text, placements = Vector.empty[SpreadPlacement])
    val albumCreatedAt = if index === 1 then createdAt.plusSeconds(1) else createdAt
    Album(albumId, (1900 + index).refineUnsafe[AlbumYearConstraint], synopsis = "", albumCreatedAt, start, chapters = Vector.empty[Chapter])

  private def buildCover(album: Album) =
    val index = album.id.value.getLeastSignificantBits.toInt
    Cover(album.id, SpineOwner.Front, CoverSlot(CoverSlotId(buildUuid(index + 200))), CoverSlot(CoverSlotId(buildUuid(index + 300))))

  private def buildUuid(index: Int) = new UUID(0L, index.toLong)
