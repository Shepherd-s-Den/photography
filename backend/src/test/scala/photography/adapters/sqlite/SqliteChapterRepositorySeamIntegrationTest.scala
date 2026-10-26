package photography.adapters.sqlite

import cats.data.NonEmptyList
import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Mutex
import cats.syntax.all.*
import io.github.iltotore.iron.*
import munit.CatsEffectSuite
import org.typelevel.doobie.*
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.adapters.sqlite.SqliteCodecs.given
import photography.app.config.SqliteConfig
import photography.capabilities.IdGenerator
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.{AlbumRepository, ChapterRepository, SpreadTemplateRepository}
import photography.usecases.{ChapterManager, SpreadTemplateManager}

import java.nio.file.{Files, Path}
import java.time.{Instant, LocalDate}
import java.util.UUID

final class SqliteChapterRepositorySeamIntegrationTest extends CatsEffectSuite:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private given IdGenerator[IO] with
    private val next                  = Ref.unsafe[IO, Long](1L)
    override def generateId: IO[UUID] = next.getAndUpdate(_ + 1L).map(index => new UUID(99L, index))
  private val albumId      = AlbumId(new UUID(1L, 1L))
  private val period       = VisitPeriod(firstDay = LocalDate.of(2026, 6, 1), days = 3L)
  private val country      = Country(CountryId(new UUID(9L, 1L)), code = "XX", name = "Test country", AbsoluteLocation(Path.of("/flags/xx.svg")))
  private val otherCountry = country.copy(id = CountryId(new UUID(9L, 2L)), code = "XY", name = "Other country")
  private val countRows    = sql"""SELECT (SELECT count(*) FROM chapter) + (SELECT count(*) FROM destination) +
    (SELECT count(*) FROM destination_country) + (SELECT count(*) FROM spread) +
    (SELECT count(*) FROM spread_photo_slot) + (SELECT count(*) FROM spread_text_slot)""".query[Long].unique

  test("should persist complete openings and supplied order, isolate shared destinations and preserve description and composition on metadata edits"):
    buildTransactor.use: xa =>
      val albums                   = SqliteAlbumRepository.make[IO](xa)
      val chapters                 = SqliteChapterRepository.make[IO](xa)
      val newName: DestinationName = "Changed"
      val newCountries             = NonEmptyList.of(otherCountry, country)
      val newFolder: SourceFolder  = "/photos/changed"

      for
        _       <- seedAlbum(xa)
        first   <- buildChapter(xa, index = 1)
        second  <- buildChapter(xa, index = 2)
        created <- chapters.create(albumId, first, NonEmptyList.one(first.id))
        added   <- chapters.create(albumId, second, NonEmptyList.of(second.id, first.id))
        _       <- sql"UPDATE chapter SET destination_id = ${first.destination.id.value} WHERE id = ${second.id.value}".update.run.transact(xa)
        _       <- insertPhotoComposition(first).transact(xa)
        before  <- albums.get(albumId)
        order = NonEmptyList.of(first.id, second.id)

        updated <- chapters.update(albumId, first.id, newName, newCountries, period, newFolder, order)
        loaded  <- albums.get(albumId)
      yield
        val changed = first.copy(
          destination = first.destination.copy(name = newName, countries = newCountries),
          sourceFolder = newFolder
        )
        (before, loaded) match
          case (AlbumRepository.GetResult.Found(original), AlbumRepository.GetResult.Found(album)) =>
            val expected =
              original.chapters.find(_.id.value === first.id.value).map(_.copy(
                destination = changed.destination,
                sourceFolder = changed.sourceFolder
              ))
            val actual = album.chapters.headOption.map(chapter => chapter.copy(destination = chapter.destination.copy(id = changed.destination.id)))

            assertEquals(created, ChapterRepository.CreateResult.Saved)
            assertEquals(added, ChapterRepository.CreateResult.Saved)
            assertEquals(updated, ChapterRepository.UpdateResult.Saved)
            assertEquals(album.chapters.map(_.id), Vector(first.id, second.id))
            assertEquals(actual, expected)
            assertEquals(album.chapters.lastOption.map(_.destination), first.destination.some)
            assertEquals(album.chapters.lastOption.map(_.opening), second.opening.some)
          case result => fail(s"Expected complete persisted album, obtained $result")

  test("should leave all persisted rows unchanged after late opening, association or ordering failures"):
    Vector(
      "CREATE TRIGGER fail_write BEFORE INSERT ON spread_text_slot BEGIN SELECT RAISE(ABORT, 'opening failure'); END",
      "CREATE TRIGGER fail_write BEFORE INSERT ON destination_country WHEN NEW.position = 1 BEGIN SELECT RAISE(ABORT, 'association failure'); END",
      "CREATE TRIGGER fail_write BEFORE INSERT ON destination_country WHEN NEW.position = 1 BEGIN SELECT RAISE(IGNORE); END",
      "CREATE TRIGGER fail_write BEFORE INSERT ON spread_photo_slot_bleed BEGIN SELECT RAISE(IGNORE); END",
      "CREATE TRIGGER fail_write BEFORE UPDATE OF position ON chapter WHEN NEW.position = 0 BEGIN SELECT RAISE(ABORT, 'order failure'); END"
    ).traverse_ { trigger =>
      buildTransactor.use: xa =>
        val albums   = SqliteAlbumRepository.make[IO](xa)
        val chapters = SqliteChapterRepository.make[IO](xa)

        for
          _      <- seedAlbum(xa)
          first  <- buildChapter(xa, index = 1)
          _      <- chapters.create(albumId, first, NonEmptyList.one(first.id))
          before <- albums.get(albumId)
          rows   <- countRows.transact(xa)
          second <- buildChapter(xa, index = 2)
          _      <- Fragment.const(trigger).update.run.transact(xa)

          failed  <- chapters.create(albumId, second, NonEmptyList.of(second.id, first.id))
          after   <- albums.get(albumId)
          counted <- countRows.transact(xa)
        yield
          assertEquals(failed, ChapterRepository.CreateResult.Failed)
          assertEquals(after, before)
          assertEquals(counted, rows)
    }

  test("should reject incomplete openings, invalid orders, foreign countries and duplicate identities or folders without partial writes"):
    buildTransactor.use: xa =>
      val chapters = SqliteChapterRepository.make[IO](xa)

      (buildChapter(xa, index = 1), buildChapter(xa, index = 2)).tupled.flatMap { case (first, second) =>
        val cases = Vector(
          second.copy(opening = second.opening.copy(photoSlots = Vector.empty[SpreadSlot.Photo])) -> NonEmptyList.of(first.id, second.id),
          second                                                                                  -> NonEmptyList.one(second.id),
          second                                                                                  -> NonEmptyList.of(first.id, first.id, second.id),
          second.copy(destination = second.destination.copy(countries = NonEmptyList.one(country.copy(id = CountryId(new UUID(9L, 99L)))))) ->
            NonEmptyList.of(first.id, second.id),
          second.copy(sourceFolder = first.sourceFolder) -> NonEmptyList.of(first.id, second.id),
          first                                          -> NonEmptyList.one(first.id)
        )

        for
          _      <- seedAlbum(xa)
          _      <- chapters.create(albumId, first, NonEmptyList.one(first.id))
          before <- countRows.transact(xa)

          results <- cases.traverse { case (chapter, order) => chapters.create(albumId, chapter, order) }
          after   <- countRows.transact(xa)
        yield
          assertEquals(results, Vector.fill(cases.size)(ChapterRepository.CreateResult.Failed))
          assertEquals(after, before)
      }

  test("should report missing ownership and roll back metadata when the final ordering write fails"):
    buildTransactor.use: xa =>
      val albums                       = SqliteAlbumRepository.make[IO](xa)
      val chapters                     = SqliteChapterRepository.make[IO](xa)
      val missing                      = AlbumId(new UUID(2L, 2L))
      val revisedName: DestinationName = "Changed"
      val revisedFolder: SourceFolder  = "/photos/changed"
      val revisedCountries             = NonEmptyList.one(otherCountry)
      val trigger                      = sql"""CREATE TRIGGER fail_update BEFORE UPDATE OF position ON chapter WHEN NEW.position = 0
                           BEGIN SELECT RAISE(ABORT, 'order failure'); END"""

      for
        _           <- seedAlbum(xa)
        first       <- buildChapter(xa, index = 1)
        absentAlbum <- chapters.create(missing, first, NonEmptyList.one(first.id))
        _           <- chapters.create(albumId, first, NonEmptyList.one(first.id))
        before      <- albums.get(albumId)
        rows        <- countRows.transact(xa)
        name           = first.destination.name
        countries      = first.destination.countries
        folder         = first.sourceFolder
        order          = NonEmptyList.one(first.id)
        unknownChapter = ChapterId(new UUID(3L, 3L))
        absentEdit  <- chapters.update(missing, first.id, name, countries, period, folder, order)
        absentChild <- chapters.update(albumId, unknownChapter, name, countries, period, folder, order)
        _           <- trigger.update.run.transact(xa)

        failed  <- chapters.update(albumId, first.id, revisedName, revisedCountries, period, revisedFolder, order)
        after   <- albums.get(albumId)
        counted <- countRows.transact(xa)
      yield
        assertEquals(absentAlbum, ChapterRepository.CreateResult.AlbumNotFound)
        assertEquals(absentEdit, ChapterRepository.UpdateResult.AlbumNotFound)
        assertEquals(absentChild, ChapterRepository.UpdateResult.ChapterNotFound)
        assertEquals(failed, ChapterRepository.UpdateResult.Failed)
        assertEquals(after, before)
        assertEquals(counted, rows)

  test("should enforce Home and folder constraints independently of supplied-order validation"):
    Vector(true, false).traverse_ { homeConflict =>
      buildTransactor.use: xa =>
        val chapters = SqliteChapterRepository.make[IO](xa)
        val albums   = SqliteAlbumRepository.make[IO](xa)

        (buildChapter(xa, index = 1), buildChapter(xa, index = 2)).tupled.flatMap { case (first, second) =>
          val firstCandidate  = if homeConflict then first.copy(destination = first.destination.copy(name = "Home")) else first
          val secondCandidate =
            if homeConflict then second.copy(destination = second.destination.copy(name = "Home")) else second.copy(sourceFolder = first.sourceFolder)

          for
            _     <- seedAlbum(xa)
            saved <- chapters.create(albumId, firstCandidate, NonEmptyList.one(first.id))

            failed <- chapters.create(albumId, secondCandidate, NonEmptyList.of(first.id, second.id))
            found  <- albums.get(albumId)
            rows   <- sql"SELECT count(*) FROM chapter".query[Long].unique.transact(xa)
          yield
            assertEquals(saved, ChapterRepository.CreateResult.Saved)
            assertEquals(failed, ChapterRepository.CreateResult.Failed)
            assertEquals(rows, 1L)
            found match
              case AlbumRepository.GetResult.Found(album) => assertEquals(album.chapters.size, 1)
              case value                                  => fail(s"Expected the committed chapter, obtained $value")
        }
    }

  test("should reject writes against incomplete or malformed stored composition without changing metadata"):
    Vector(
      "DELETE FROM spread_text_slot WHERE spread_id IN (SELECT id FROM spread WHERE kind = 'chapter_start')",
      """DELETE FROM spread_template_slot WHERE kind = 'photo' AND template_id IN (SELECT id FROM spread_template WHERE kind = 'chapter_start');
         DELETE FROM spread_photo_slot WHERE spread_id IN (SELECT id FROM spread WHERE kind = 'chapter_start')""",
      """UPDATE spread_template_slot SET width = -width WHERE template_id IN (SELECT id FROM spread_template WHERE kind = 'chapter_start');
         UPDATE spread_photo_slot SET width = -width WHERE spread_id IN (SELECT id FROM spread WHERE kind = 'chapter_start');
         UPDATE spread_text_slot SET width = -width WHERE spread_id IN (SELECT id FROM spread WHERE kind = 'chapter_start')"""
    ).traverse_ { corruption =>
      buildTransactor.use: xa =>
        val chapters = SqliteChapterRepository.make[IO](xa)
        val metadata = sql"SELECT destination_id, description, start_ms, end_ms, position, source_folder FROM chapter"
          .query[(String, String, Long, Long, Int, String)].to[Vector]
        val corrupt                      = corruption.split(";").toList.traverse_(statement => Fragment.const(statement).update.run)
        val changedName: DestinationName = "Changed"
        val changedCountries             = NonEmptyList.one(otherCountry)
        val changedFolder: SourceFolder  = "/photos/changed"

        for
          _       <- seedAlbum(xa)
          chapter <- buildChapter(xa, index = 1)
          order = NonEmptyList.one(chapter.id)
          _      <- chapters.create(albumId, chapter, order)
          _      <- corrupt.transact(xa)
          before <- metadata.transact(xa)

          failed <- chapters.update(albumId, chapter.id, changedName, changedCountries, period, changedFolder, order)
          after  <- metadata.transact(xa)
        yield
          assertEquals(failed, ChapterRepository.UpdateResult.Failed)
          assertEquals(after, before)
    }

  test("should preserve database Home and folder integrity under concurrent adapter updates"):
    Vector(true, false).traverse_ { homeConflict =>
      buildTransactor.use: xa =>
        val chapters = SqliteChapterRepository.make[IO](xa)
        val albums   = SqliteAlbumRepository.make[IO](xa)

        (buildChapter(xa, index = 1), buildChapter(xa, index = 2)).tupled.flatMap { case (first, second) =>
          val order                 = NonEmptyList.of(first.id, second.id)
          val name: DestinationName = if homeConflict then "Home" else "Changed"
          val edits                 = Vector(first, second).parTraverse { chapter =>
            val folder: SourceFolder = if homeConflict then chapter.sourceFolder else "/photos/shared"
            chapters.update(albumId, chapter.id, name, chapter.destination.countries, period, folder, order)
          }

          for
            _ <- seedAlbum(xa)
            _ <- chapters.create(albumId, first, NonEmptyList.one(first.id))
            _ <- chapters.create(albumId, second, order)

            outcomes <- edits
            loaded   <- albums.get(albumId)
          yield loaded match
            case AlbumRepository.GetResult.Found(album) =>
              val changed = album.chapters.count(chapter => (chapter.destination.name: String) === name)

              assertEquals(outcomes.count { case ChapterRepository.UpdateResult.Saved => true; case _ => false }, 1)
              assertEquals(outcomes.count { case ChapterRepository.UpdateResult.Failed => true; case _ => false }, 1)
              assertEquals(album.chapters.size, 2)
              assertEquals(changed, 1)
            case result => fail(s"Expected the committed chapter update, obtained $result")
        }
    }

  test("should reject creating a text-only opening even when its stored template has lost its photo slots"):
    buildTransactor.use: xa =>
      val chapters = SqliteChapterRepository.make[IO](xa)
      val corrupt  = sql"""DELETE FROM spread_template_slot WHERE kind = 'photo'
        AND template_id IN (SELECT id FROM spread_template WHERE kind = 'chapter_start')"""

      for
        _       <- seedAlbum(xa)
        chapter <- buildChapter(xa, index = 1)
        _       <- corrupt.update.run.transact(xa)
        malformed = chapter.copy(opening = chapter.opening.copy(photoSlots = Vector.empty[SpreadSlot.Photo]))
        before <- countRows.transact(xa)

        failed <- chapters.create(albumId, malformed, NonEmptyList.one(chapter.id))
        after  <- countRows.transact(xa)
      yield
        assertEquals(failed, ChapterRepository.CreateResult.Failed)
        assertEquals(after, before)

  test("should coordinate two editors through one shared backend mutex at capacity and allow metadata edits at capacity"):
    buildTransactor.use: xa =>
      val albums = SqliteAlbumRepository.make[IO](xa)

      Mutex[IO].flatMap { mutex =>
        given Mutex[IO]        = mutex
        val templates          = SpreadTemplateManager.make(SqliteSpreadTemplateRepository.make[IO](xa))
        val manager            = ChapterManager.make(albums, SqliteChapterRepository.make[IO](xa), templates)
        def create(index: Int) = manager.create(
          albumId,
          name = s"Trip $index".refineUnsafe,
          NonEmptyList.one(country),
          ChapterManager.Period.Dates(period),
          sourceFolder = s"/photos/$index".refineUnsafe
        )
        def editFirst(loaded: AlbumRepository.GetResult) = loaded match
          case AlbumRepository.GetResult.Found(album) =>
            album.chapters.headOption.traverse { chapter =>
              manager.update(
                albumId,
                chapter.id,
                name = "Changed",
                NonEmptyList.one(otherCountry),
                ChapterManager.Period.Dates(period),
                chapter.sourceFolder
              )
            }
          case _ => IO.raiseError[Option[ChapterManager.UpdateResult]](new IllegalStateException("Missing persisted album"))
        for
          _       <- seedAlbum(xa)
          initial <- (1 to 63).toVector.traverse(create)

          racers <- Vector(64, 65).parTraverse(create)
          loaded <- albums.get(albumId)
          edited <- editFirst(loaded)
        yield
          assertEquals(initial.count { case ChapterManager.CreateResult.Created(_) => true; case _ => false }, 63)
          assertEquals(racers.count { case ChapterManager.CreateResult.Created(_) => true; case _ => false }, 1)
          assertEquals(racers.count { case ChapterManager.CreateResult.Rejected(ChapterConflict.AtCapacity) => true; case _ => false }, 1)
          assertEquals(edited, ChapterManager.UpdateResult.Updated.some)
          loaded match
            case AlbumRepository.GetResult.Found(album) =>
              assertEquals(album.chapters.size, 64)
              assertEquals(album.spreads.length, 65)
            case value => fail(s"Expected full persisted album, obtained $value")
      }

  private def insertPhotoComposition(chapter: Chapter) =
    val photo           = new UUID(7L, 1L).toString
    val placement       = new UUID(7L, 2L).toString
    val template        = new UUID(7L, 3L).toString
    val spread          = new UUID(7L, 4L).toString
    val slot            = new UUID(7L, 5L).toString
    val insertPlacement = sql"""INSERT INTO placement
      (id, photo_id, kind, spread_photo_slot_id, shift_x, shift_y, mirrored, zoom, caption_corner)
      SELECT $placement, $photo, 'spread', id, 0, 0, 0, 1, 'bottom_left'
      FROM spread_photo_slot WHERE spread_id = ${chapter.opening.id.value} ORDER BY id LIMIT 1"""
    val insertSpread = sql"""INSERT INTO spread (id, template_id, kind, chapter_id, position)
      VALUES ($spread, $template, 'photos_only', ${chapter.id.value}, 1)"""
    for
      _ <- sql"INSERT INTO photo (id, chapter_id, source, width, height) VALUES ($photo, ${chapter.id.value}, 'photo.jpg', 100, 100)".update.run
      _ <- insertPlacement.update.run
      _ <- sql"INSERT INTO spread_template (id, name, kind) VALUES ($template, 'Photo layout', 'photos_only')".update.run
      _ <- sql"INSERT INTO spread_template_slot (template_id, kind, x, y, width, height) VALUES ($template, 'photo', 0, 0, 100, 100)".update.run
      _ <- insertSpread.update.run
      _ <- sql"INSERT INTO spread_photo_slot (id, spread_id, x, y, width, height) VALUES ($slot, $spread, 0, 0, 100, 100)".update.run
    yield ()

  private def buildTransactor =
    for
      path <- Resource.make(IO.blocking(Files.createTempFile("chapter-", ".sqlite")))(path => IO.blocking(Files.deleteIfExists(path)).void)
      _    <- FlywayMigrator.make[IO](path).evalMap(_.migrate)
      xa   <- SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 2, maxSessions = 2)).buildTransactor
    yield xa

  private def seedAlbum(xa: Transactor[IO]) =
    val templates       = SqliteSpreadTemplateRepository.make[IO](xa)
    val insertCountries = sql"""INSERT INTO country VALUES
      (${country.id.value}, 'XX', 'Test country', '/flags/xx.svg'), (${otherCountry.id.value}, 'XY', 'Other country', '/flags/xx.svg')"""
    templates.listAlbumStartTemplates.flatMap:
      case SpreadTemplateRepository.ListAlbumStartTemplatesResult.Listed(values) =>
        val (album, cover) = buildAlbum(values.head)
        for
          saved <- SqliteAlbumRepository.make[IO](xa).create(album, cover)
          _     <- IO(assertEquals(saved, AlbumRepository.CreateResult.Created))
          _     <- insertCountries.update.run.transact(xa)
        yield ()
      case value => IO.raiseError[Unit](new IllegalStateException(s"Missing album templates: $value"))

  private def buildAlbum(template: SpreadTemplate.AlbumStart) =
    val photos = template.photoSlots.zipWithIndex.map { case (box, index) =>
      SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(4L, index.toLong)), box, bleed = Set.empty[SpreadEdge])
    }
    val opening = Spread.AlbumStart(
      SpreadId(new UUID(4L, 10L)),
      template.id,
      photos,
      SpreadSlot.Text(TextSpreadSlotId(new UUID(4L, 11L)), template.textSlot),
      placements = Vector.empty[SpreadPlacement]
    )
    val album = Album(albumId, year = 2026, title = "Album", synopsis = "", Instant.EPOCH, opening, chapters = Vector.empty[Chapter])
    val cover = Cover(albumId, SpineOwner.Front, CoverSlot(CoverSlotId(new UUID(4L, 12L))), CoverSlot(CoverSlotId(new UUID(4L, 13L))))
    (album, cover)

  private def buildChapter(xa: Transactor[IO], index: Int) =
    SqliteSpreadTemplateRepository.make[IO](xa).listChapterStartTemplates.flatMap:
      case SpreadTemplateRepository.ListChapterStartTemplatesResult.Listed(values) =>
        val template = values.head
        val photos   = template.photoSlots.zipWithIndex.map { case (box, position) =>
          SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(index.toLong, position.toLong)), box, bleed = Set(SpreadEdge.Left))
        }
        val opening = Spread.ChapterStart(
          SpreadId(new UUID(index.toLong, 100L)),
          template.id,
          photos,
          SpreadSlot.Text(TextSpreadSlotId(new UUID(index.toLong, 101L)), template.textSlot),
          placements = Vector.empty[SpreadPlacement]
        )
        Chapter(
          ChapterId(new UUID(index.toLong, 102L)),
          Destination(DestinationId(new UUID(index.toLong, 103L)), name = "Trip", NonEmptyList.of(country, otherCountry)),
          period,
          description = "Retained description",
          sourceFolder = s"/photos/$index".refineUnsafe,
          opening,
          spreads = Vector.empty[Spread.PhotosOnly]
        ).pure[IO]
      case value => IO.raiseError[Chapter](new IllegalStateException(s"Missing chapter templates: $value"))
