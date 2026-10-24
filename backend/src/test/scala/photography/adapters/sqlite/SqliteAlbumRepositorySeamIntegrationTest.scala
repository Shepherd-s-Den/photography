package photography.adapters.sqlite

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import munit.CatsEffectSuite
import org.typelevel.doobie.*
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.domain.album.{Album, AlbumId, ChapterId, PhotoId}
import photography.domain.layout.*
import photography.ports.AlbumRepository.*

import java.time.Instant
import java.util.UUID

final class SqliteAlbumRepositorySeamIntegrationTest extends CatsEffectSuite:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private val createdAt    = Instant.parse("2026-10-07T12:00:00.123Z")
  private val renamedTitle = "Renamed album".refineUnsafe[Not[Empty] & Not[Blank]]

  test("should persist the complete initial Album and cover atomically and preserve metadata when renamed"):
    SqliteTestDatabase.resource.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa)

      for
        album <- buildAlbum(xa, 1)
        cover = buildCover(album)
        created  <- repository.create(album, cover)
        found    <- repository.get(album.id)
        sides    <- sql"SELECT side FROM cover_slot ORDER BY side".query[String].to[Vector].transact(xa)
        renamed  <- repository.rename(album.id, renamedTitle)
        reloaded <- repository.get(album.id)
      yield
        assertEquals(created, CreateResult.Created)
        assertEquals(found, GetResult.Found(album))
        assertEquals(sides, Vector("back", "front"))
        assertEquals(renamed, RenameResult.Renamed(album.copy(title = renamedTitle)))
        assertEquals(reloaded, GetResult.Found(album.copy(title = renamedTitle)))

  test("should list newest-first slices with stable ordering for equal creation instants and an empty collection"):
    SqliteTestDatabase.resource.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa)

      for
        empty  <- repository.list(offset = 0, limit = 6)
        albums <- Vector(1, 2, 3).traverse(buildAlbum(xa, _))
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

  test("should report unknown albums consistently and delete all initial owned rows without deleting shared templates"):
    SqliteTestDatabase.resource.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa)
      val unknownId  = AlbumId(buildUuid(999))

      for
        album         <- buildAlbum(xa, 1)
        _             <- repository.create(album, buildCover(album))
        missing       <- repository.get(unknownId)
        missingRename <- repository.rename(unknownId, renamedTitle)
        missingDelete <- repository.delete(unknownId)
        deleted       <- repository.delete(album.id)
        removed       <- repository.get(album.id)
        owned         <- sql"""SELECT (SELECT count(*) FROM album) + (SELECT count(*) FROM spread) +
                            (SELECT count(*) FROM spread_photo_slot) + (SELECT count(*) FROM spread_text_slot) +
                            (SELECT count(*) FROM cover) + (SELECT count(*) FROM cover_slot)""".query[Long].unique.transact(xa)
        templates <- sql"SELECT count(*) FROM spread_template".query[Long].unique.transact(xa)
      yield
        assertEquals(missing, GetResult.NotFound)
        assertEquals(missingRename, RenameResult.NotFound)
        assertEquals(missingDelete, DeleteResult.NotFound)
        assertEquals(deleted, DeleteResult.Deleted)
        assertEquals(removed, GetResult.NotFound)
        assertEquals(owned, 0L)
        assertEquals(templates, 4L)

  test("should roll back every album-owned row when a late cover write fails"):
    SqliteTestDatabase.resource.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa)

      for
        album <- buildAlbum(xa, 1)
        _     <- sql"""CREATE TRIGGER fail_cover BEFORE INSERT ON cover_slot WHEN NEW.side = 'back'
                   BEGIN SELECT RAISE(ABORT, 'injected cover failure'); END""".update.run.transact(xa)
        result <- repository.create(album, buildCover(album))
        count  <- sql"""SELECT (SELECT count(*) FROM album) + (SELECT count(*) FROM spread) +
                            (SELECT count(*) FROM spread_photo_slot) + (SELECT count(*) FROM spread_text_slot) +
                            (SELECT count(*) FROM cover) + (SELECT count(*) FROM cover_slot)""".query[Long].unique.transact(xa)
      yield
        assertEquals(result, CreateResult.Failed(Failure.DatabaseFailure))
        assertEquals(count, 0L)

  test("should reject incomplete or invalid persisted albums rather than returning partial success"):
    Vector("DELETE FROM cover_slot WHERE side = 'back'", "DELETE FROM spread_text_slot", "UPDATE album SET title = ' '").traverse_ { corruption =>
      SqliteTestDatabase.resource.use: xa =>
        val repository = SqliteAlbumRepository.make[IO](xa)

        for
          album   <- buildAlbum(xa, 1)
          _       <- repository.create(album, buildCover(album))
          _       <- Fragment.const(corruption).update.run.transact(xa)
          found   <- repository.get(album.id)
          listed  <- repository.list(offset = 0, limit = 6)
          renamed <- repository.rename(album.id, renamedTitle)
        yield
          assertEquals(found, GetResult.Failed(Failure.InvalidAlbumData))
          assertEquals(listed, ListResult.Failed(Failure.InvalidAlbumData))
          assertEquals(renamed, RenameResult.Failed(Failure.InvalidAlbumData))
    }

  test("should report database failures explicitly for every operation"):
    SqliteTestDatabase.resource.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa)

      for
        album   <- buildAlbum(xa, 1)
        _       <- sql"DROP TABLE album".update.run.transact(xa)
        listed  <- repository.list(offset = 0, limit = 6)
        created <- repository.create(album, buildCover(album))
        found   <- repository.get(album.id)
        renamed <- repository.rename(album.id, renamedTitle)
        deleted <- repository.delete(album.id)
      yield
        assertEquals(listed, ListResult.Failed(Failure.DatabaseFailure))
        assertEquals(created, CreateResult.Failed(Failure.DatabaseFailure))
        assertEquals(found, GetResult.Failed(Failure.DatabaseFailure))
        assertEquals(renamed, RenameResult.Failed(Failure.DatabaseFailure))
        assertEquals(deleted, DeleteResult.Failed(Failure.DatabaseFailure))

  test("should preserve persisted photo-slot bleed when reading an album"):
    SqliteTestDatabase.resource.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa)

      for
        album  <- buildAlbum(xa, 1)
        _      <- repository.create(album, buildCover(album))
        slotId <- sql"SELECT id FROM spread_photo_slot ORDER BY id LIMIT 1".query[String].unique.transact(xa)
        _      <- sql"INSERT INTO spread_photo_slot_bleed (slot_id, edge) VALUES ($slotId, 'left')".update.run.transact(xa)
        found  <- repository.get(album.id)
      yield
        val expectedSlots = album.spreads.head.slots.map:
          case SpreadSlot.Photo(id, spread, box, bleed) if id.value.toString === slotId =>
            SpreadSlot.Photo(id, spread, box, bleed + SpreadEdge.Left)
          case slot => slot
        val expectedStart = album.spreads.head match
          case start: Spread.AlbumStart => start.copy(slots = expectedSlots)
          case other                    => fail(s"Expected AlbumStart fixture, obtained $other")
        val expectedAlbum = album.copy(spreads = NonEmptyList.one(expectedStart: Spread))
        assertEquals(found, GetResult.Found(expectedAlbum))

  test("should read chapter spreads and placements in domain order and cascade deletion through the owned graph"):
    SqliteTestDatabase.resource.use: xa =>
      val repository    = SqliteAlbumRepository.make[IO](xa)
      val countryId     = buildUuid(400).toString
      val destinationId = buildUuid(401).toString
      val chapterId     = buildUuid(402).toString
      val photoId       = buildUuid(403).toString
      val templateId    = buildUuid(404).toString
      val spreadId      = buildUuid(405).toString
      val slotId        = buildUuid(406).toString
      val placementId   = buildUuid(407).toString
      val sessionId     = buildUuid(408).toString

      for
        album <- buildAlbum(xa, 1)
        _     <- repository.create(album, buildCover(album))
        albumId = album.id.value.toString
        _ <- (for
          _ <- sql"INSERT INTO country VALUES ($countryId, 'IS', 'Iceland', 'IS')".update.run
          _ <- sql"INSERT INTO destination VALUES ($destinationId, 'Coast', $countryId)".update.run
          _ <- sql"""INSERT INTO chapter VALUES ($chapterId, $albumId, $destinationId, '', 0, 1, 0, 'coast')""".update.run
          _ <- sql"INSERT INTO photo VALUES ($photoId, $chapterId, 'photo.jpg', NULL, NULL, 100, 100, NULL)".update.run
          _ <- sql"INSERT INTO spread_template VALUES ($templateId, 'Chapter', 'chapter_start')".update.run
          _ <- sql"""INSERT INTO spread_template_slot (template_id, kind, x, y, width, height)
                     VALUES ($templateId, 'photo', 0, 0, 100, 100)""".update.run
          _ <- sql"""INSERT INTO spread (id, template_id, kind, chapter_id, position)
                     VALUES ($spreadId, $templateId, 'chapter_start', $chapterId, 0)""".update.run
          _ <- sql"INSERT INTO spread_photo_slot VALUES ($slotId, $spreadId, 0, 0, 100, 100)".update.run
          _ <- sql"""INSERT INTO placement (id, photo_id, kind, spread_photo_slot_id, shift_x, shift_y, mirrored, zoom, caption_corner)
                     VALUES ($placementId, $photoId, 'spread', $slotId, 0, 0, 0, 1, 'bottom_left')""".update.run
          _ <- sql"INSERT INTO editor_session VALUES ($sessionId, 'owner', 0)".update.run
          _ <- sql"INSERT INTO spread_editing_lock VALUES ($spreadId, $sessionId)".update.run
          _ <- sql"INSERT INTO cover_editing_lock VALUES ($albumId, $sessionId)".update.run
        yield ()).transact(xa)
        found   <- repository.get(album.id)
        deleted <- repository.delete(album.id)
        owned   <- sql"""SELECT (SELECT count(*) FROM album) + (SELECT count(*) FROM chapter) +
                              (SELECT count(*) FROM photo) + (SELECT count(*) FROM spread) +
                              (SELECT count(*) FROM placement) + (SELECT count(*) FROM spread_editing_lock) +
                              (SELECT count(*) FROM cover_editing_lock)""".query[Long].unique.transact(xa)
        shared <- sql"""SELECT (SELECT count(*) FROM country) + (SELECT count(*) FROM destination) +
                               (SELECT count(*) FROM spread_template) + (SELECT count(*) FROM editor_session)""".query[Long].unique.transact(xa)
      yield
        val box               = SlotBox(0.0.refineUnsafe, 0.0.refineUnsafe, 100.0.refineUnsafe, 100.0.refineUnsafe)
        val expectedSlot      = SpreadSlot.Photo(PhotoSpreadSlotId(buildUuid(406)), SpreadId(buildUuid(405)), box, Set.empty[SpreadEdge])
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
          ChapterId(buildUuid(402)),
          SpreadTemplateId(buildUuid(404)),
          Vector(expectedSlot),
          Vector(expectedPlacement)
        )
        val expectedAlbum = album.copy(spreads = album.spreads.append(expectedChapter: Spread))
        assertEquals(found, GetResult.Found(expectedAlbum))
        assertEquals(deleted, DeleteResult.Deleted)
        assertEquals(owned, 0L)
        assertEquals(shared, 8L)

  private def buildAlbum(xa: Transactor[IO], index: Int): IO[Album] =
    for
      templateId <- sql"SELECT id FROM spread_template ORDER BY id LIMIT 1".query[String].unique.transact(xa)
      slots      <- sql"SELECT kind, x, y, width, height FROM spread_template_slot WHERE template_id = $templateId ORDER BY id"
        .query[(String, Double, Double, Double, Double)].to[Vector].transact(xa)
    yield
      val albumId     = AlbumId(buildUuid(index))
      val spreadId    = SpreadId(buildUuid(index + 100))
      val spreadSlots = slots.zipWithIndex.map: (slot, position) =>
        val (kind, x, y, width, height) = slot
        val box                         = SlotBox(x.refineUnsafe, y.refineUnsafe, width.refineUnsafe, height.refineUnsafe)
        val id                          = buildUuid(index * 1000 + position)
        if kind === "photo" then SpreadSlot.Photo(PhotoSpreadSlotId(id), spreadId, box, Set.empty[SpreadEdge])
        else SpreadSlot.Text(TextSpreadSlotId(id), spreadId, box)
      val start = Spread.AlbumStart(spreadId, albumId, SpreadTemplateId(UUID.fromString(templateId)), spreadSlots, Vector.empty[SpreadPlacement])
      val albumCreatedAt = if index === 1 then createdAt.plusSeconds(1) else createdAt
      Album(albumId, s"Album $index".refineUnsafe, synopsis = "", albumCreatedAt, NonEmptyList.one(start: Spread))

  private def buildCover(album: Album) =
    val index = album.id.value.getLeastSignificantBits.toInt
    Cover(album.id, SpineOwner.Front, CoverSlot(CoverSlotId(buildUuid(index + 200))), CoverSlot(CoverSlotId(buildUuid(index + 300))))

  private def buildUuid(index: Int) = new UUID(0L, index.toLong)
