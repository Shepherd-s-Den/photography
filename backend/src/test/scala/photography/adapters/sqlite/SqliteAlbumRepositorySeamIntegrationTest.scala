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
  private given Logger[IO]             = Slf4jLogger.getLogger[IO]
  private val createdAt                = Instant.parse("2026-10-07T12:00:00.123Z")
  private val changedTitle: AlbumTitle = "Asian capitals"
  private val countInitialRows         = sql"""SELECT (SELECT count(*) FROM album) + (SELECT count(*) FROM spread) +
                                      (SELECT count(*) FROM spread_photo_slot) + (SELECT count(*) FROM spread_text_slot) +
                                      (SELECT count(*) FROM spread_photo_slot_bleed) +
                                      (SELECT count(*) FROM cover) + (SELECT count(*) FROM cover_slot)""".query[Long].unique

  test("should persist the complete initial album and preserve year and composition when renamed"):
    Vector(SpineOwner.Front -> "front", SpineOwner.Back -> "back").traverse_ { case (spineOwner, expectedSpine) =>
      buildTransactor.use: xa =>
        val repository = SqliteAlbumRepository.make[IO](xa)
        val album      = buildAlbum(index = 1)
        val cover      = buildCover(album).copy(spineOwner = spineOwner)

        for
          created  <- repository.create(album, cover)
          found    <- repository.get(album.id)
          spine    <- sql"SELECT spine_owner FROM cover".query[String].unique.transact(xa)
          sides    <- sql"SELECT side FROM cover_slot ORDER BY side".query[String].to[Vector].transact(xa)
          renamed  <- repository.rename(album.id, title = changedTitle)
          reloaded <- repository.get(album.id)
        yield
          assertEquals(created, CreateResult.Created)
          assertEquals(found, GetResult.Found(album))
          assertEquals(spine, expectedSpine)
          assertEquals(sides, Vector("back", "front"))
          assertEquals(renamed, RenameResult.Renamed(album.copy(title = changedTitle)))
          assertEquals(reloaded, GetResult.Found(album.copy(title = changedTitle)))
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
        assertEquals(empty, ListResult.Listed(AlbumPageSlice(totalAlbums = 0, albums = Vector.empty[Album])))
        assertEquals(first, ListResult.Listed(AlbumPageSlice(totalAlbums = 3, newestFirst.take(2))))
        assertEquals(last, ListResult.Listed(AlbumPageSlice(totalAlbums = 3, newestFirst.drop(2))))
        assertEquals(beyond, ListResult.Listed(AlbumPageSlice(totalAlbums = 3, albums = Vector.empty[Album])))

  test("should enforce unique required years atomically under concurrent creation"):
    buildTransactor.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa)
      val first      = buildAlbum(index = 1)
      val second     = buildAlbum(index = 2).copy(year = first.year)
      val later      = buildAlbum(index = 3)

      for
        results <- Vector(first, second).parTraverse(album => repository.create(album, buildCover(album)))
        before  <- countInitialRows.transact(xa)
        _       <- repository.create(later, buildCover(later))
        loaded  <- repository.get(later.id)
        after   <- countInitialRows.transact(xa)
      yield
        assertEquals(results.collect { case CreateResult.Created => () }.size, 1)
        assertEquals(results.collect { case CreateResult.Failed(Failure.YearAlreadyUsed) => () }.size, 1)
        assertEquals(loaded, GetResult.Found(later))
        assertEquals(after, before * 2L)

  test("should report unknown albums consistently and delete all initial owned rows without deleting shared templates"):
    buildTransactor.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa)
      val unknownId  = AlbumId(buildUuid(index = 999))
      val album      = buildAlbum(index = 1)

      for
        _             <- repository.create(album, buildCover(album))
        missing       <- repository.get(unknownId)
        missingRename <- repository.rename(unknownId, title = changedTitle)
        missingDelete <- repository.delete(unknownId)
        deleted       <- repository.delete(album.id)
        removed       <- repository.get(album.id)
        owned         <- countInitialRows.transact(xa)
        templates     <- sql"SELECT count(*) FROM spread_template".query[Long].unique.transact(xa)
      yield
        assertEquals(missing, GetResult.NotFound)
        assertEquals(missingRename, RenameResult.NotFound)
        assertEquals(missingDelete, DeleteResult.NotFound)
        assertEquals(deleted, DeleteResult.Deleted)
        assertEquals(removed, GetResult.NotFound)
        assertEquals(owned, 0L)
        assertEquals(templates, 31L)

  test("should roll back every album-owned row when a late cover write fails"):
    buildTransactor.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa)
      val failCover  = sql"""CREATE TRIGGER fail_cover BEFORE INSERT ON cover_slot WHEN NEW.side = 'back'
                           BEGIN SELECT RAISE(ABORT, 'injected cover failure'); END"""
      val album = buildAlbum(index = 1)

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
      val album          = buildAlbum(index = 1)
      val chapterOpening = Spread.ChapterStart(
        SpreadId(buildUuid(index = 10)),
        SpreadTemplateId(buildUuid(index = 1)),
        photoSlots = Vector.empty[SpreadSlot.Photo],
        textSlot = SpreadSlot.Text(TextSpreadSlotId(buildUuid(index = 11)), SlotBox(x = 0.0, y = 0.0, width = 100.0, height = 100.0)),
        placements = Vector.empty[SpreadPlacement]
      )
      val country = Country(CountryId(buildUuid(index = 12)), code = "RO", name = "Romania", AbsoluteLocation(Path.of("/flags/ro.svg")))
      val home    = Chapter(
        ChapterId(buildUuid(index = 13)),
        Destination(DestinationId(buildUuid(index = 14)), name = "Home", NonEmptyList.one(country)),
        VisitPeriod(firstDay = LocalDate.of(1901, 1, 1), days = 365),
        description = "",
        sourceFolder = "/photos/Home",
        chapterOpening,
        spreads = Vector.empty[Spread.PhotosOnly]
      )

      Vector(
        album.copy(chapters = Vector(home)) -> buildCover(album),
        album                               -> buildCover(album).copy(album = AlbumId(buildUuid(index = 999)))
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
      "UPDATE album SET title = ''",
      "UPDATE spread_photo_slot SET x = x + 1",
      "UPDATE spread_template SET kind = 'photos_only' WHERE id = '00000000-0000-0000-0000-000000000001'",
      "DELETE FROM spread",
      "UPDATE spread_photo_slot SET width = -1",
      "UPDATE spread_text_slot SET id = '0-0-0-0-1'"
    ).traverse_ { corruption =>
      buildTransactor.use: xa =>
        val repository = SqliteAlbumRepository.make[IO](xa)
        val album      = buildAlbum(index = 1)

        for
          _       <- repository.create(album, buildCover(album))
          _       <- (sql"PRAGMA ignore_check_constraints=ON".update.run >> Fragment.const(corruption).update.run).transact(xa)
          found   <- repository.get(album.id)
          listed  <- repository.list(offset = 0, limit = 6)
          before  <- sql"SELECT title FROM album".query[String].unique.transact(xa)
          changed <- repository.rename(album.id, title = changedTitle)
          after   <- sql"SELECT title FROM album".query[String].unique.transact(xa)
          deleted <- repository.delete(album.id)
          removed <- repository.get(album.id)
        yield
          assertEquals(found, GetResult.Failed(Failure.DatabaseFailure))
          assertEquals(listed, ListResult.Failed(Failure.DatabaseFailure))
          assertEquals(changed, RenameResult.Failed(Failure.DatabaseFailure))
          assertEquals(after, before)
          assertEquals(deleted, DeleteResult.Deleted)
          assertEquals(removed, GetResult.NotFound)
    }

  test("should report database failures explicitly for every operation"):
    buildTransactor.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa)
      val album      = buildAlbum(index = 1)

      for
        _       <- sql"DROP TABLE album".update.run.transact(xa)
        listed  <- repository.list(offset = 0, limit = 6)
        created <- repository.create(album, buildCover(album))
        found   <- repository.get(album.id)
        renamed <- repository.rename(album.id, title = changedTitle)
        deleted <- repository.delete(album.id)
      yield
        assertEquals(listed, ListResult.Failed(Failure.DatabaseFailure))
        assertEquals(created, CreateResult.Failed(Failure.DatabaseFailure))
        assertEquals(found, GetResult.Failed(Failure.DatabaseFailure))
        assertEquals(renamed, RenameResult.Failed(Failure.DatabaseFailure))
        assertEquals(deleted, DeleteResult.Failed(Failure.DatabaseFailure))

  test("should preserve persisted photo-slot bleed when reading an album"):
    buildTransactor.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa)
      val album      = buildAlbum(index = 1)

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
      val countryId          = buildUuid(index = 400).toString
      val destinationId      = buildUuid(index = 401).toString
      val chapterId          = buildUuid(index = 402).toString
      val photoId            = buildUuid(index = 403).toString
      val templateId         = buildUuid(index = 404).toString
      val spreadId           = buildUuid(index = 405).toString
      val slotId             = buildUuid(index = 406).toString
      val placementId        = buildUuid(index = 407).toString
      val sessionId          = buildUuid(index = 408).toString
      val albumId            = buildUuid(index = 1).toString
      val photosTemplateId   = buildUuid(index = 409).toString
      val photosSpreadId     = buildUuid(index = 410).toString
      val photosSlotId       = buildUuid(index = 411).toString
      val secondChapterId    = buildUuid(index = 420).toString
      val secondOpeningId    = buildUuid(index = 421).toString
      val secondPhotoSlotId  = buildUuid(index = 422).toString
      val secondTextSlotId   = buildUuid(index = 423).toString
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
      val insertSecondChapter =
        sql"""INSERT INTO chapter (id, album_id, destination_id, description, start_ms, end_ms, position, source_folder) VALUES
                                      ($secondChapterId, $albumId, $destinationId, '', 86400000, 172800000, 1, '/photos/second')"""
      val insertSecondOpening = sql"""INSERT INTO spread (id, template_id, kind, chapter_id, position)
                                      VALUES ($secondOpeningId, $templateId, 'chapter_start', $secondChapterId, 0)"""
      val insertSecondPhoto  = sql"""INSERT INTO spread_photo_slot VALUES ($secondPhotoSlotId, $secondOpeningId, 0, 0, 100, 100)"""
      val insertSecondText   = sql"""INSERT INTO spread_text_slot VALUES ($secondTextSlotId, $secondOpeningId, 0, 0, 100, 100)"""
      val insertFirstChapter = sql"""INSERT INTO chapter (id, album_id, destination_id, description, start_ms, end_ms, position, source_folder)
        VALUES ($chapterId, $albumId, $destinationId, '', 0, 86400000, 0, '/photos/coast')"""
      val initialiseChapter =
        for
          _ <- sql"DELETE FROM country WHERE code IN ('IS', 'JP')".update.run
          _ <- sql"INSERT INTO country VALUES ($countryId, 'IS', 'Iceland', '/flags/is.svg')".update.run
          _ <- sql"INSERT INTO destination VALUES ($destinationId, 'Coast')".update.run
          _ <- sql"INSERT INTO destination_country VALUES ($destinationId, $countryId, 0)".update.run
          _ <- sql"INSERT INTO country VALUES (${buildUuid(index = 430).toString}, 'JP', 'Japan', '/flags/jp.svg')".update.run
          _ <- sql"INSERT INTO destination_country VALUES ($destinationId, ${buildUuid(index = 430).toString}, 1)".update.run
          _ <- insertFirstChapter.update.run
          _ <- sql"INSERT INTO photo VALUES ($photoId, $chapterId, 'photo.jpg', NULL, NULL, 100, 100, NULL)".update.run
          _ <- sql"INSERT INTO spread_template VALUES ($templateId, 'Chapter', 'chapter_start')".update.run
          _ <- insertTemplateSlot.update.run
          _ <- sql"INSERT INTO spread_template_slot (template_id, kind, x, y, width, height) VALUES ($templateId, 'text', 0, 0, 100, 100)".update.run
          _ <- insertChapterStart.update.run
          _ <- sql"INSERT INTO spread_photo_slot VALUES ($slotId, $spreadId, 0, 0, 100, 100)".update.run
          _ <- sql"INSERT INTO spread_text_slot VALUES (${buildUuid(index = 412).toString}, $spreadId, 0, 0, 100, 100)".update.run
          _ <- insertPlacement.update.run
          _ <- sql"INSERT INTO spread_template VALUES ($photosTemplateId, 'Photos', 'photos_only')".update.run
          _ <- insertPhotosTemplateSlot.update.run
          _ <- insertPhotosSpread.update.run
          _ <- sql"INSERT INTO spread_photo_slot VALUES ($photosSlotId, $photosSpreadId, 0, 0, 100, 100)".update.run
          _ <- sql"INSERT INTO editor_session VALUES ($sessionId, 'owner', 0)".update.run
          _ <- sql"INSERT INTO spread_editing_lock VALUES ($spreadId, $sessionId)".update.run
          _ <- sql"INSERT INTO cover_editing_lock VALUES ($albumId, $sessionId)".update.run
          _ <- insertSecondChapter.update.run
          _ <- insertSecondOpening.update.run
          _ <- insertSecondPhoto.update.run
          _ <- insertSecondText.update.run
        yield ()
      val corruptions = Vector(
        (s"UPDATE chapter SET destination_id = '${buildUuid(index = 499)}'", s"UPDATE chapter SET destination_id = '$destinationId'"),
        (
          s"UPDATE destination_country SET country_id = '${buildUuid(index = 499)}' WHERE position = 0",
          s"UPDATE destination_country SET country_id = '$countryId' WHERE position = 0"
        ),
        (
          "DELETE FROM destination_country",
          s"INSERT INTO destination_country VALUES ('$destinationId', '$countryId', 0), ('$destinationId', '${buildUuid(index = 430)}', 1)"
        ),
        (
          s"UPDATE chapter SET source_folder = 'relative' WHERE id = '$chapterId'",
          s"UPDATE chapter SET source_folder = '/photos/coast' WHERE id = '$chapterId'"
        ),
        (
          s"UPDATE chapter SET source_folder = '/photos/../coast' WHERE id = '$chapterId'",
          s"UPDATE chapter SET source_folder = '/photos/coast' WHERE id = '$chapterId'"
        ),
        ("UPDATE chapter SET position = -1 WHERE position = 0", "UPDATE chapter SET position = 0 WHERE position = -1"),
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
      val album = buildAlbum(index = 1)

      for
        _        <- repository.create(album, buildCover(album))
        _        <- initialiseChapter.transact(xa)
        found    <- repository.get(album.id)
        renamed  <- repository.rename(album.id, title = changedTitle)
        reloaded <- repository.get(album.id)
        _        <- rejectCorruptions(album)
        deleted  <- repository.delete(album.id)
        owned    <- countOwned.transact(xa)
        shared   <- countShared.transact(xa)
      yield
        val box               = SlotBox(x = 0.0.refineUnsafe, y = 0.0.refineUnsafe, width = 100.0.refineUnsafe, height = 100.0.refineUnsafe)
        val expectedSlot      = SpreadSlot.Photo(PhotoSpreadSlotId(buildUuid(index = 406)), box, bleed = Set.empty[SpreadEdge])
        val transform         = PhotoTransform(Shift(x = 0.0.refineUnsafe, y = 0.0.refineUnsafe), mirrored = false, zoom = 1.0.refineUnsafe)
        val expectedPlacement =
          SpreadPlacement(
            SpreadPlacementId(buildUuid(index = 407)),
            PhotoSpreadSlotId(buildUuid(index = 406)),
            PhotoId(buildUuid(index = 403)),
            transform,
            CaptionCorner.BottomLeft
          )
        val expectedChapter = Spread.ChapterStart(
          SpreadId(buildUuid(index = 405)),
          SpreadTemplateId(buildUuid(index = 404)),
          photoSlots = Vector(expectedSlot),
          textSlot = SpreadSlot.Text(TextSpreadSlotId(buildUuid(index = 412)), box),
          placements = Vector(expectedPlacement)
        )
        val expectedPhotosSlot =
          SpreadSlot.Photo(PhotoSpreadSlotId(buildUuid(index = 411)), box, bleed = Set.empty[SpreadEdge])
        val expectedPhotos = Spread.PhotosOnly(
          SpreadId(buildUuid(index = 410)),
          SpreadTemplateId(buildUuid(index = 409)),
          photoSlots = NonEmptyList.one(expectedPhotosSlot),
          placements = Vector.empty[SpreadPlacement]
        )
        val expectedCountry = Country(CountryId(buildUuid(index = 400)), code = "IS", name = "Iceland", AbsoluteLocation(Path.of("/flags/is.svg")))
        val japan           = Country(CountryId(buildUuid(index = 430)), code = "JP", name = "Japan", AbsoluteLocation(Path.of("/flags/jp.svg")))
        val destination     = Destination(DestinationId(buildUuid(index = 401)), name = "Coast", NonEmptyList.of(expectedCountry, japan))
        val chapter         =
          Chapter(
            ChapterId(buildUuid(index = 402)),
            destination,
            VisitPeriod(firstDay = LocalDate.of(1970, 1, 1), days = 1),
            description = "",
            sourceFolder = "/photos/coast",
            expectedChapter,
            spreads = Vector(expectedPhotos)
          )
        val secondOpening = expectedChapter.copy(
          id = SpreadId(buildUuid(index = 421)),
          photoSlots = Vector(expectedSlot.copy(id = PhotoSpreadSlotId(buildUuid(index = 422)))),
          textSlot = expectedChapter.textSlot.copy(id = TextSpreadSlotId(buildUuid(index = 423))),
          placements = Vector.empty[SpreadPlacement]
        )
        val secondChapter = chapter.copy(
          id = ChapterId(buildUuid(index = 420)),
          period = VisitPeriod(firstDay = LocalDate.of(1970, 1, 2), days = 1),
          sourceFolder = "/photos/second",
          opening = secondOpening,
          spreads = Vector.empty[Spread.PhotosOnly]
        )
        val expectedAlbum = album.copy(chapters = Vector(chapter, secondChapter))
        assertEquals(found, GetResult.Found(expectedAlbum))
        assertEquals(renamed, RenameResult.Renamed(expectedAlbum.copy(title = changedTitle)))
        assertEquals(reloaded, GetResult.Found(expectedAlbum.copy(title = changedTitle)))
        assertEquals(expectedAlbum.countries, Vector(expectedCountry, japan))
        assertEquals(deleted, DeleteResult.Deleted)
        assertEquals(owned, 0L)
        assertEquals(shared, (249L, 1L, 33L, 1L))

  private def buildTransactor =
    for
      path <- Resource.make(IO.blocking(Files.createTempFile("album-", ".sqlite")))(path => IO.blocking(Files.deleteIfExists(path)).void)
      _    <- FlywayMigrator.make[IO](path).evalMap(_.migrate)
      xa   <- SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 2, maxSessions = 2)).buildTransactor
    yield xa

  private def buildAlbum(index: Int) =
    val albumId  = AlbumId(buildUuid(index = index))
    val spreadId = SpreadId(buildUuid(index = index + 100))
    val boxes    = Vector(
      SlotBox(x = 0.293982, y = 0.914609, width = 24.695519, height = 48.758744),
      SlotBox(x = 25.199487, y = 0.914609, width = 24.695519, height = 48.758744),
      SlotBox(x = 0.293982, y = 50.326646, width = 24.695519, height = 48.758744),
      SlotBox(x = 25.199487, y = 50.326646, width = 24.695519, height = 48.758744)
    )
    val photos = boxes.zipWithIndex.map { case (box, position) =>
      SpreadSlot.Photo(PhotoSpreadSlotId(buildUuid(index = index * 1000 + position)), box, bleed = Set.empty[SpreadEdge])
    }
    val text = SpreadSlot.Text(
      TextSpreadSlotId(buildUuid(index = index * 1000 + 4)),
      SlotBox(x = 50.104993, y = 0.914609, width = 49.601025, height = 98.170781)
    )
    val start = Spread.AlbumStart(
      spreadId,
      SpreadTemplateId(buildUuid(index = 1)),
      photoSlots = photos,
      textSlot = text,
      placements = Vector.empty[SpreadPlacement]
    )
    val albumCreatedAt = if index === 1 then createdAt.plusSeconds(1) else createdAt
    Album(
      albumId,
      year = (1900 + index).refineUnsafe[AlbumYearConstraint],
      title = "My photographs",
      synopsis = "",
      createdAt = albumCreatedAt,
      start,
      chapters = Vector.empty[Chapter]
    )

  private def buildCover(album: Album) =
    val index = album.id.value.getLeastSignificantBits.toInt
    Cover(
      album.id,
      SpineOwner.Front,
      front = CoverSlot(CoverSlotId(buildUuid(index = index + 200))),
      back = CoverSlot(CoverSlotId(buildUuid(index = index + 300)))
    )

  private def buildUuid(index: Int) = new UUID(0L, index.toLong)
