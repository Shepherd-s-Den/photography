package photography.adapters.sqlite

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import munit.CatsEffectSuite
import org.typelevel.doobie.*
import org.typelevel.doobie.implicits.*
import photography.app.SqliteConfig
import photography.app.album.{AlbumService, UtcClock, UUIDGenerator}
import photography.domain.album.{Album, AlbumComposition, AlbumId, AlbumName}
import photography.domain.layout.*

import java.nio.file.Files
import java.time.Instant
import java.util.UUID

final class SqliteAlbumRepositorySeamIntegrationTest extends CatsEffectSuite:

  private val createdAt = Instant.parse("2026-10-07T12:00:00Z")

  test("should transactionally persist and read the complete initial composition"):
    migratedDatabase.use: transactor =>
      val repository  = SqliteAlbumRepository.make[IO](transactor)
      val composition = newComposition(albumNumber = 1)

      for
        created <- repository.create(composition)
        loaded  <- repository.getComposition(composition.album.id)
        listed  <- repository.list(offset = 0, limit = 6)
      yield
        assertEquals(created, ().asRight[photography.app.album.AlbumRepositoryFailure])
        assertEquals(loaded, composition.some.asRight[photography.app.album.AlbumRepositoryFailure])
        assertEquals(
          listed.map(_.albums.map(_.id)),
          Vector(composition.album.id).asRight[photography.app.album.AlbumRepositoryFailure]
        )

  test("should read persisted chapters, photos, spreads, and cover and spread placements"):
    migratedDatabase.use: transactor =>
      val repository             = SqliteAlbumRepository.make[IO](transactor)
      val composition            = newComposition(albumNumber = 4)
      val chapterId              = photography.domain.album.ChapterId(uuid(50))
      val destinationId          = photography.domain.album.DestinationId(uuid(51))
      val chapterStartTemplateId = SpreadTemplateId(uuid(52))
      val photosTemplateId       = SpreadTemplateId(uuid(53))
      val chapterStartId         = SpreadId(uuid(54))
      val photosSpreadId         = SpreadId(uuid(55))
      val textSlotId             = TextSpreadSlotId(uuid(56))
      val photoSlotId            = PhotoSpreadSlotId(uuid(57))
      val chapterPhotoId         = photography.domain.album.PhotoId(uuid(58))
      val coverPhotoId           = photography.domain.album.PhotoId(uuid(59))
      val chapterPhoto           = photography.domain.album.Photo(
        chapterPhotoId,
        chapterId,
        photography.domain.album.RelativeLocation(java.nio.file.Path.of("chapter/photo.jpg")),
        java.nio.file.Path.of("thumb/photo.jpg").some.map(photography.domain.album.RelativeLocation.apply),
        Instant.parse("2026-10-06T12:00:00Z").some,
        photography.domain.album.PhotoDimensions(
          120.refineUnsafe[Positive],
          80.refineUnsafe[Positive]
        ),
        "A caption".some
      )
      val coverPhoto = photography.domain.album.Photo(
        coverPhotoId,
        chapterId,
        photography.domain.album.RelativeLocation(java.nio.file.Path.of("chapter/cover.jpg")),
        Option.empty[photography.domain.album.RelativeLocation],
        Option.empty[Instant],
        photography.domain.album.PhotoDimensions(
          60.refineUnsafe[Positive],
          40.refineUnsafe[Positive]
        ),
        Option.empty[String]
      )
      val chapter = photography.domain.album.Chapter(
        chapterId,
        composition.album.id,
        destinationId,
        "Chapter description",
        photography.domain.album.VisitPeriod(
          Instant.parse("2026-10-06T00:00:00Z"),
          Instant.parse("2026-10-07T00:00:00Z")
        ),
        0.refineUnsafe[GreaterEqual[0] & Less[20]],
        photography.domain.album.RelativeLocation(java.nio.file.Path.of("chapter"))
      )
      val transform = PhotoTransform(
        Shift(
          0.25.refineUnsafe[Greater[-1.7976931348623157e308] & Less[1.7976931348623157e308]],
          0.5.refineUnsafe[Greater[-1.7976931348623157e308] & Less[1.7976931348623157e308]]
        ),
        mirrored = true,
        zoom = 2.0.refineUnsafe[Positive & Less[1.7976931348623157e308]]
      )
      val chapterStart = SpreadComposition(
        chapterStartId,
        chapterStartTemplateId,
        Vector(SpreadSlot.Text(textSlotId, chapterStartId, fullSpreadBox)),
        Vector.empty[SpreadPlacement]
      )
      val photoPlacement = SpreadPlacement(
        SpreadPlacementId(uuid(60)),
        photoSlotId,
        chapterPhotoId,
        transform,
        CaptionCorner.TopRight
      )
      val photosSpread = SpreadComposition(
        photosSpreadId,
        photosTemplateId,
        Vector(SpreadSlot.Photo(photoSlotId, photosSpreadId, fullSpreadBox, Set(SpreadEdge.Bottom, SpreadEdge.Left))),
        Vector(photoPlacement)
      )
      val coverPlacement = CoverPlacement(
        CoverPlacementId(uuid(61)),
        composition.cover.front,
        coverPhotoId,
        transform
      )
      val expected = composition.copy(
        cover = composition.cover.copy(frontPlacement = coverPlacement.some),
        chapters = Vector(
          photography.domain.album.ChapterComposition(
            chapter,
            chapterStart,
            Vector(chapterPhoto, coverPhoto),
            Vector(photosSpread)
          )
        )
      )

      for
        _ <- repository.create(composition)
        _ <- sql"INSERT INTO country (id, code, name, flag) VALUES (${uuid(62).toString}, 'XX', 'Example', 'X')"
          .update.run.transact(transactor)
        _ <- sql"INSERT INTO destination (id, name, country_id) VALUES (${destinationId.value.toString}, 'Place', ${uuid(62).toString})"
          .update.run.transact(transactor)
        _ <- sql"""
            INSERT INTO spread_template (id, name, kind)
            VALUES (${chapterStartTemplateId.value.toString}, 'Chapter start', 'chapter_start'),
                   (${photosTemplateId.value.toString}, 'Photos only', 'photos_only')
          """.update.run.transact(transactor)
        _ <- sql"""
            INSERT INTO spread_template_slot (id, template_id, kind, x, y, width, height)
            VALUES (100, ${chapterStartTemplateId.value.toString}, 'text', 0, 0, 1, 1),
                   (101, ${photosTemplateId.value.toString}, 'photo', 0, 0, 1, 1)
          """.update.run.transact(transactor)
        _ <- sql"""
            INSERT INTO chapter (id, album_id, destination_id, description, start_ms, end_ms, position, source_folder)
            VALUES (
              ${chapterId.value.toString},
              ${composition.album.id.value.toString},
              ${destinationId.value.toString},
              'Chapter description',
              ${Instant.parse("2026-10-06T00:00:00Z").toEpochMilli},
              ${Instant.parse("2026-10-07T00:00:00Z").toEpochMilli},
              0,
              'chapter'
            )
          """.update.run.transact(transactor)
        _ <- sql"""
            INSERT INTO photo (id, chapter_id, source, thumbnail, captured_at_ms, width, height, caption)
            VALUES
              (${chapterPhotoId.value.toString}, ${chapterId.value.toString}, 'chapter/photo.jpg', 'thumb/photo.jpg',
                ${Instant.parse("2026-10-06T12:00:00Z").toEpochMilli}, 120, 80, 'A caption'),
              (${coverPhotoId.value.toString}, ${chapterId.value.toString}, 'chapter/cover.jpg', NULL, NULL, 60, 40, NULL)
          """.update.run.transact(transactor)
        _ <- sql"""
            INSERT INTO spread (id, template_id, kind, album_id, chapter_id, position)
            VALUES
              (${chapterStartId.value.toString}, ${chapterStartTemplateId.value.toString}, 'chapter_start', NULL,
                ${chapterId.value.toString}, 0),
              (${photosSpreadId.value.toString}, ${photosTemplateId.value.toString}, 'photos_only', NULL,
                ${chapterId.value.toString}, 1)
          """.update.run.transact(transactor)
        _ <- sql"""
            INSERT INTO spread_text_slot (id, spread_id, x, y, width, height)
            VALUES (${textSlotId.value.toString}, ${chapterStartId.value.toString}, 0, 0, 1, 1)
          """.update.run.transact(transactor)
        _ <- sql"""
            INSERT INTO spread_photo_slot (id, spread_id, x, y, width, height)
            VALUES (${photoSlotId.value.toString}, ${photosSpreadId.value.toString}, 0, 0, 1, 1)
          """.update.run.transact(transactor)
        _ <- sql"""
            INSERT INTO spread_photo_slot_bleed (slot_id, edge)
            VALUES (${photoSlotId.value.toString}, 'bottom'), (${photoSlotId.value.toString}, 'left')
          """.update.run.transact(transactor)
        _ <- sql"""
            INSERT INTO placement (
              id, photo_id, kind, spread_photo_slot_id, cover_slot_id, shift_x, shift_y, mirrored, zoom, caption_corner
            )
            VALUES (${uuid(60).toString}, ${chapterPhotoId.value.toString}, 'spread', ${photoSlotId.value.toString},
              NULL, 0.25, 0.5, 1, 2, 'top_right')
          """.update.run.transact(transactor)
        _ <- sql"""
            INSERT INTO placement (
              id, photo_id, kind, spread_photo_slot_id, cover_slot_id, shift_x, shift_y, mirrored, zoom, caption_corner
            )
            VALUES (${uuid(61).toString}, ${coverPhotoId.value.toString}, 'cover', NULL,
              ${composition.cover.front.id.value.toString}, 0.25, 0.5, 1, 2, NULL)
          """.update.run.transact(transactor)
        loaded <- repository.getComposition(composition.album.id)
      yield assertEquals(loaded, expected.some.asRight[photography.app.album.AlbumRepositoryFailure])

  test("should create and open an album using the seeded template and SQLite"):
    migratedDatabase.use: transactor =>
      val repository = SqliteAlbumRepository.make[IO](transactor)

      for
        nextId <- cats.effect.Ref.of[IO, Int](100)
        identifiers = new UUIDGenerator[IO]:
          override def nextUUID: IO[UUID] =
            nextId.getAndUpdate(_ + 1).map(uuid)
        clock = new UtcClock[IO]:
          override def now: IO[Instant] = IO.pure(createdAt)
        service = AlbumService.make(repository, identifiers, clock)
        created <- service.create("  Real persistence  ")
        albumSummary = created.fold(failure => fail(s"expected album creation, got $failure"), identity)
        opened <- service.get(albumSummary.id)
      yield
        assertEquals(albumSummary.title, "Real persistence".refineUnsafe[Not[Empty]])
        assertEquals(opened.map(_.album.id), albumSummary.id.asRight[photography.app.album.AlbumServiceFailure])
        assert(opened.toOption.exists(_.chapters.isEmpty))
        assert(opened.toOption.exists(_.start.slots.size === 1))

  test("should update only the album title and delete all owned composition rows"):
    migratedDatabase.use: transactor =>
      val repository   = SqliteAlbumRepository.make[IO](transactor)
      val composition  = newComposition(albumNumber = 2)
      val renamedTitle = "Retitled".refineUnsafe[Not[Empty]]

      for
        _          <- repository.create(composition)
        renamed    <- repository.rename(composition.album.id, renamedTitle)
        deleted    <- repository.delete(composition.album.id)
        albumCount <- sql"SELECT COUNT(*) FROM album WHERE id = ${composition.album.id.value.toString}"
          .query[Int]
          .unique
          .transact(transactor)
        spreadCount <- sql"SELECT COUNT(*) FROM spread WHERE album_id = ${composition.album.id.value.toString}"
          .query[Int]
          .unique
          .transact(transactor)
        coverCount <- sql"SELECT COUNT(*) FROM cover WHERE album_id = ${composition.album.id.value.toString}"
          .query[Int]
          .unique
          .transact(transactor)
      yield
        assertEquals(
          renamed.map(_.map(_.title)),
          renamedTitle.some.asRight[photography.app.album.AlbumRepositoryFailure]
        )
        assertEquals(deleted, true.asRight[photography.app.album.AlbumRepositoryFailure])
        assertEquals(albumCount, 0)
        assertEquals(spreadCount, 0)
        assertEquals(coverCount, 0)

  test("should roll back all composition rows when initialization violates a constraint"):
    migratedDatabase.use: transactor =>
      val repository   = SqliteAlbumRepository.make[IO](transactor)
      val valid        = newComposition(albumNumber = 3)
      val repeatedSlot = valid.start.slots.headOption.fold(fail("expected a text slot"))(identity)
      val invalid      = valid.copy(
        start = valid.start.copy(slots = Vector(repeatedSlot, repeatedSlot))
      )

      for
        result     <- repository.create(invalid)
        albumCount <- sql"SELECT COUNT(*) FROM album WHERE id = ${valid.album.id.value.toString}"
          .query[Int]
          .unique
          .transact(transactor)
        spreadCount <- sql"SELECT COUNT(*) FROM spread WHERE album_id = ${valid.album.id.value.toString}"
          .query[Int]
          .unique
          .transact(transactor)
      yield
        assert(result.isLeft)
        assertEquals(albumCount, 0)
        assertEquals(spreadCount, 0)

  private def newComposition(albumNumber: Int): AlbumComposition =
    val albumId          = AlbumId(uuid(albumNumber))
    val spreadId         = SpreadId(uuid(albumNumber + 10))
    val slotId           = TextSpreadSlotId(uuid(albumNumber + 20))
    val frontSlot        = CoverSlot(CoverSlotId(uuid(albumNumber + 30)))
    val backSlot         = CoverSlot(CoverSlotId(uuid(albumNumber + 40)))
    val title: AlbumName = s"Album $albumNumber".refineUnsafe[Not[Empty]]
    val box              = fullSpreadBox
    AlbumComposition(
      album = Album(albumId, title, "", createdAt),
      start = SpreadComposition(
        spreadId,
        SpreadTemplateId(uuid(1)),
        Vector(SpreadSlot.Text(slotId, spreadId, box)),
        Vector.empty[SpreadPlacement]
      ),
      cover = CoverComposition(
        SpineOwner.Front,
        frontSlot,
        Option.empty[CoverPlacement],
        backSlot,
        Option.empty[CoverPlacement]
      ),
      chapters = Vector.empty[photography.domain.album.ChapterComposition]
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

  private def migratedDatabase: Resource[IO, Transactor[IO]] =
    for
      path <- Resource.make(
        IO.blocking(Files.createTempFile("photography-album-repository", ".sqlite"))
      )(path => IO.blocking(Files.deleteIfExists(path)).void)
      transactor <- SqliteConnection.make[IO](SqliteConfig(path, 1, 1)).transactor
      flyway     <- FlywayMigrator.make[IO](path)
      _          <- Resource.eval(flyway.migrate)
    yield transactor
