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
import photography.adapters.filesystem.FileThumbnailStorage
import photography.capabilities.{IdGenerator, Transaction}
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.{AlbumRepository, ChapterRepository, PhotoRepository, SpreadTemplateRepository}
import photography.syntax.SyncSyntax.*
import photography.usecases.{ChapterManager, ChapterPhotoImport, ChapterPlanning, PhotoWriteRecovery, SpreadTemplateManager}
import photography.usecases.ChapterPhotoImport.{ExecutionOutcome, Request, Scheduling, Status}

import java.nio.file.{Files, Path}
import java.time.{Instant, LocalDate}
import java.util.UUID

final class SqliteChapterRepositorySeamIntegrationTest extends CatsEffectSuite:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private val destination  = DestinationId(new UUID(99L, 1L))
  private val albumId      = AlbumId(new UUID(1L, 1L))
  private val period       = VisitPeriod(firstDay = LocalDate.of(2026, 6, 1), days = 3L)
  private val country      = Country(CountryId(new UUID(9L, 1L)), code = "XX", name = "Test country", AbsoluteLocation(Path.of("/flags/xx.svg")))
  private val otherCountry = country.copy(id = CountryId(new UUID(9L, 2L)), code = "XY", name = "Other country")
  private val countRows    = sql"""SELECT (SELECT count(*) FROM chapter) + (SELECT count(*) FROM destination) +
    (SELECT count(*) FROM destination_country) + (SELECT count(*) FROM spread) +
    (SELECT count(*) FROM spread_photo_slot) + (SELECT count(*) FROM spread_text_slot)""".query[Long].unique

  test("should expose chapter writes as database programs that roll back with their caller"):
    buildTransactor.use: xa =>
      val chapters = SqliteChapterRepository.make
      val error    = new IllegalStateException("Abort composed transaction")

      for
        _      <- seedAlbum(xa)
        first  <- buildChapter(xa, index = 1)
        failed <- (chapters.create(albumId, first, order = NonEmptyList.one(first.id)) >> error.raiseError[ConnectionIO, Unit]).transact(xa).attempt
        rows   <- sql"SELECT count(*) FROM chapter".query[Int].unique.transact(xa)
      yield
        assert(failed.isLeft)
        assertEquals(rows, 0)

  test("should reject unknown countries explicitly without changing the saved chapter or creating partial rows"):
    buildTransactor.use: xa =>
      val chapters = SqliteChapterRepository.make
      val unknown  = NonEmptyList.one(CountryId(new UUID(9L, 99L)))

      (buildChapter(xa, index = 1), buildChapter(xa, index = 2)).tupled.flatMap { case (first, second) =>
        val command = ChapterRepository.Update(
          albumId,
          first.id,
          destination,
          first.name,
          countries = unknown,
          period,
          first.sourceFolder,
          order = NonEmptyList.one(first.id)
        )

        for
          _       <- seedAlbum(xa)
          _       <- chapters.create(albumId, first, order = NonEmptyList.one(first.id)).transact(xa)
          before  <- countRows.transact(xa)
          created <- chapters.create(albumId, second.copy(countries = unknown), order = NonEmptyList.of(first.id, second.id)).transact(xa)
          updated <- chapters.update(command).transact(xa)
          after   <- countRows.transact(xa)
        yield
          assertEquals(created, ChapterRepository.CreateResult.UnknownCountries)
          assertEquals(updated, ChapterRepository.UpdateResult.UnknownCountries)
          assertEquals(after, before)
      }

  test("should persist complete openings and supplied order, isolate shared destinations and preserve description and composition on metadata edits"):
    buildTransactor.use: xa =>
      val albums                   = SqliteAlbumRepository.make[IO](xa)
      val chapters                 = SqliteChapterRepository.make
      val newName: DestinationName = "Changed"
      val newCountries             = NonEmptyList.of(otherCountry, country)
      val newFolder: PhotoSource   = "/photos/changed"
      val countryIds               = newCountries.map(_.id)

      (buildChapter(xa, index = 1), buildChapter(xa, index = 2)).tupled.flatMap { case (first, second) =>
        val command = ChapterRepository.Update(
          albumId,
          first.id,
          destination,
          newName,
          countries = countryIds,
          period,
          newFolder,
          order = NonEmptyList.of(first.id, second.id)
        )

        for
          _       <- seedAlbum(xa)
          created <- chapters.create(albumId, first, order = NonEmptyList.one(first.id)).transact(xa)
          added   <- chapters.create(albumId, second, order = NonEmptyList.of(second.id, first.id)).transact(xa)
          initial <- albums.get(albumId)
          _       <- sql"UPDATE chapter SET destination_id = ${first.destinationId.value} WHERE id = ${second.id.value}".update.run.transact(xa)
          _       <- insertPhotoComposition(first.id, first.opening.id).transact(xa)
          before  <- albums.get(albumId)
          updated <- chapters.update(command).transact(xa)
          loaded  <- albums.get(albumId)
        yield
          val originalDestination = Destination(first.destinationId, first.name, countries = NonEmptyList.of(country, otherCountry))
          val changedDestination  = originalDestination.copy(name = newName, countries = newCountries)
          val secondDestination   = Destination(second.destinationId, second.name, countries = NonEmptyList.of(country, otherCountry))
          val emptySpreads        = Vector.empty[Spread.PhotosOnly]
          val expectedFirst       =
            Chapter(first.id, originalDestination, first.period, first.description, first.sourceFolder, first.opening, spreads = emptySpreads)
          val expectedSecond =
            Chapter(second.id, secondDestination, second.period, second.description, second.sourceFolder, second.opening, spreads = emptySpreads)
          initial match
            case AlbumRepository.GetResult.Found(album) => assertEquals(album.chapters, Vector(expectedSecond, expectedFirst))
            case result                                 => fail(s"Expected complete created chapters, obtained $result")
          (before, loaded) match
            case (AlbumRepository.GetResult.Found(original), AlbumRepository.GetResult.Found(album)) =>
              val expected =
                original.chapters.find(_.id.value === first.id.value).map(_.copy(destination = changedDestination, sourceFolder = newFolder))
              val actual = album.chapters.headOption.map(chapter => chapter.copy(destination = chapter.destination.copy(id = first.destinationId)))

              assertEquals(created, ChapterRepository.CreateResult.Saved)
              assertEquals(added, ChapterRepository.CreateResult.Saved)
              assertEquals(updated, ChapterRepository.UpdateResult.Saved)
              assertEquals(album.chapters.map(_.id), Vector(first.id, second.id))
              assertEquals(actual, expected)
              assertEquals(album.chapters.lastOption.map(_.destination), originalDestination.some)
              assertEquals(album.chapters.lastOption.map(_.opening), second.opening.some)
            case result => fail(s"Expected complete persisted album, obtained $result")
      }

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
        val chapters = SqliteChapterRepository.make

        for
          _       <- seedAlbum(xa)
          first   <- buildChapter(xa, index = 1)
          _       <- chapters.create(albumId, first, order = NonEmptyList.one(first.id)).transact(xa)
          before  <- albums.get(albumId)
          rows    <- countRows.transact(xa)
          second  <- buildChapter(xa, index = 2)
          _       <- Fragment.const(trigger).update.run.transact(xa)
          failed  <- chapters.create(albumId, second, order = NonEmptyList.of(second.id, first.id)).transact(xa).attempt
          after   <- albums.get(albumId)
          counted <- countRows.transact(xa)
        yield
          assert(failed.isLeft)
          assertEquals(after, before)
          assertEquals(counted, rows)
    }

  test("should reject incomplete openings, invalid orders and duplicate identities without partial writes"):
    buildTransactor.use: xa =>
      val chapters = SqliteChapterRepository.make

      (buildChapter(xa, index = 1), buildChapter(xa, index = 2)).tupled.flatMap { case (first, second) =>
        val mismatchedText = second.opening.textSlot.copy(box = SlotBox(x = 0.0, y = 0.0, width = 100.0, height = 100.0))
        val cases          = Vector(
          second.copy(opening = second.opening.copy(photoSlots = Vector.empty[SpreadSlot.Photo])) -> NonEmptyList.of(first.id, second.id),
          second.copy(opening = second.opening.copy(textSlot = mismatchedText))                   -> NonEmptyList.of(first.id, second.id),
          second                                                                                  -> NonEmptyList.one(second.id),
          second                                                                                  -> NonEmptyList.of(first.id, first.id, second.id),
          first                                                                                   -> NonEmptyList.one(first.id)
        )

        for
          _       <- seedAlbum(xa)
          _       <- chapters.create(albumId, first, order = NonEmptyList.one(first.id)).transact(xa)
          before  <- countRows.transact(xa)
          results <- cases.traverse { case (chapter, order) => chapters.create(albumId, chapter, order = order).transact(xa).attempt }
          after   <- countRows.transact(xa)
        yield
          assert(results.forall(_.isLeft))
          assertEquals(after, before)
      }

  test("should report missing ownership and roll back metadata when the final ordering write fails"):
    buildTransactor.use: xa =>
      val albums                       = SqliteAlbumRepository.make[IO](xa)
      val chapters                     = SqliteChapterRepository.make
      val missing                      = AlbumId(new UUID(2L, 2L))
      val revisedName: DestinationName = "Changed"
      val revisedFolder: PhotoSource   = "/photos/changed"
      val revisedCountries             = NonEmptyList.one(otherCountry.id)
      val trigger                      = sql"""CREATE TRIGGER fail_update BEFORE UPDATE OF position ON chapter WHEN NEW.position = 0
                           BEGIN SELECT RAISE(IGNORE); END"""

      buildChapter(xa, index = 1).flatMap: first =>
        val unknownChapter = ChapterId(new UUID(3L, 3L))
        val command        = ChapterRepository.Update(
          albumId,
          first.id,
          destination,
          first.name,
          countries = first.countries,
          period,
          first.sourceFolder,
          order = NonEmptyList.one(first.id)
        )
        val revised = command.copy(name = revisedName, countries = revisedCountries, sourceFolder = revisedFolder)

        for
          _           <- seedAlbum(xa)
          absentAlbum <- chapters.create(missing, first, order = NonEmptyList.one(first.id)).transact(xa)
          _           <- chapters.create(albumId, first, order = NonEmptyList.one(first.id)).transact(xa)
          before      <- albums.get(albumId)
          rows        <- countRows.transact(xa)
          absentEdit  <- chapters.update(command.copy(album = missing)).transact(xa)
          absentChild <- chapters.update(command.copy(chapter = unknownChapter)).transact(xa)
          _           <- trigger.update.run.transact(xa)
          failed      <- chapters.update(revised).transact(xa).attempt
          after       <- albums.get(albumId)
          counted     <- countRows.transact(xa)
        yield
          assertEquals(absentAlbum, ChapterRepository.CreateResult.AlbumNotFound)
          assertEquals(absentEdit, ChapterRepository.UpdateResult.AlbumNotFound)
          assertEquals(absentChild, ChapterRepository.UpdateResult.ChapterNotFound)
          assert(failed.isLeft)
          assertEquals(after, before)
          assertEquals(counted, rows)

  test("should commit source clearing with metadata and evidence or preserve all state, then recover only retired files after reopening"):
    Vector("saved", "acknowledgement", "evidence", "removal", "order", "unknown-country", "unchanged").traverse_ { stage =>
      (buildDatabase, buildDirectory).tupled.use { case (path, root) =>
        val chapters                     = SqliteChapterRepository.make
        val photos                       = SqlitePhotoRepository.make
        val storage                      = FileThumbnailStorage.make[IO](root)
        val locations                    = Vector(ThumbnailLocation(new UUID(7L, 1L)), ThumbnailLocation(new UUID(7L, 6L)))
        val imageBytes                   = Vector(1.toByte).refineEither[ThumbnailBytesConstraint].fold(message => fail(message), identity)
        val image                        = EncodedThumbnail(imageBytes, ThumbnailDimensions(width = 1, height = 1), ThumbnailQuality.Low)
        val committed                    = stage === "saved" || stage === "acknowledgement"
        val requestedSource: PhotoSource = if stage === "unchanged" then "/photos/1" else "/photos/changed"
        val visit                        = ChapterManager.Period.Dates(period)
        val requestedCountries           =
          if stage === "unknown-country" then NonEmptyList.one(CountryId(new UUID(9L, 99L))) else NonEmptyList.one(otherCountry.id)
        val expectedPhotos = if committed then 0 else 2
        val expectedResult = if committed then ChapterManager.UpdateResult.Updated(Scheduling.Failed)
        else if stage === "unchanged" then ChapterManager.UpdateResult.Updated(Scheduling.NotRequested)
        else if stage === "unknown-country" then ChapterManager.UpdateResult.Rejected(ChapterConflict.UnknownCountries)
        else ChapterManager.UpdateResult.Failed
        val slots = sql"SELECT id, x, y, width, height FROM spread_photo_slot ORDER BY id".query[(String, Double, Double, Double, Double)].to[Vector]
        val coverSlots = sql"SELECT id, side FROM cover_slot ORDER BY id".query[(String, String)].to[Vector]
        val change     = buildConnection(path).use: xa =>
          val albums             = SqliteAlbumRepository.make[IO](xa)
          val templateRepository = SqliteSpreadTemplateRepository.make[IO](xa)
          val templates          = SpreadTemplateManager.make(templateRepository)
          val calls              = Ref.unsafe[IO, Int](0)
          given Transaction[ConnectionIO, IO] with
            override def run[A](program: ConnectionIO[A]): IO[A] =
              calls.updateAndGet(_ + 1).flatMap: count =>
                val execute = program.transact(xa)
                if stage === "acknowledgement" && count === 2 then execute >> new IllegalStateException("Lost acknowledgement").raiseError[IO, A]
                else execute
            override def resolve[A](program: ConnectionIO[A]): IO[A] = program.transact(xa)
          given IdGenerator[IO] with
            private val next                  = Ref.unsafe[IO, Long](200L)
            override def generateId: IO[UUID] = next.getAndUpdate(_ + 1).map(index => new UUID(8L, index))

          Mutex[IO].flatMap: mutex =>
            given Mutex[IO] = mutex
            val imports     = new ChapterPhotoImport[IO]:
              override def prepare(parent: AlbumId, chapter: ChapterId, source: PhotoSource): IO[Request] =
                Request(parent, chapter, source, generation = BigInt(1)).pure[IO]
              override def launch(request: Request): IO[Scheduling] =
                mutex.lock.surround(Scheduling.Failed.pure[IO])
              override def retire(parent: AlbumId, retirements: Vector[PhotoWriteIntent.Retirement]): IO[Unit] = IO.unit
              override def suspend(chapter: ChapterId): IO[Unit]                                               = IO.unit
              override def status(chapter: ChapterId): IO[ExecutionOutcome]                                    =
                ExecutionOutcome(Status.Idle, retirementIssues = Vector.empty[PhotoWriteRecovery.Issue]).pure[IO]
            val planning = ChapterPlanning.make(templates)
            val manager  = ChapterManager.make(albums, chapters, planning, imports, photos)
            val trigger  = stage match
              case "evidence" => "CREATE TRIGGER reject_write BEFORE INSERT ON photo_write_intent BEGIN SELECT RAISE(ABORT, 'evidence'); END"
              case "removal"  => "CREATE TRIGGER reject_write BEFORE DELETE ON photo BEGIN SELECT RAISE(ABORT, 'remove'); END"
              case "order"    =>
                "CREATE TRIGGER reject_write BEFORE UPDATE OF position ON chapter WHEN NEW.position = 0 BEGIN SELECT RAISE(ABORT, 'order'); END"
              case _ => ""

            for
              _           <- seedAlbum(xa)
              first       <- buildChapter(xa, index = 1)
              _           <- chapters.create(albumId, first, order = NonEmptyList.one(first.id)).transact(xa)
              _           <- (insertPhotoComposition(first.id, first.opening.id) >> insertCoverPhoto(first.id)).transact(xa)
              _           <- locations.traverse_(location => storage.storeThumbnail(location, image))
              before      <- albums.get(albumId)
              beforeSlots <- slots.transact(xa)
              beforeCover <- coverSlots.transact(xa)
              _           <- if trigger.isEmpty then IO.unit else Fragment.const(trigger).update.run.transact(xa).void
              result      <- manager.update(albumId, first.id, name = "Changed", countries = requestedCountries, visit, requestedSource)
              after       <- albums.get(albumId)
              afterSlots  <- slots.transact(xa)
              afterCover  <- coverSlots.transact(xa)
              photoCount  <- sql"SELECT count(*) FROM photo".query[Int].unique.transact(xa)
              placements  <- sql"SELECT count(*) FROM placement".query[Int].unique.transact(xa)
              pending     <- photos.listUnfinishedWrites.transact(xa)
            yield
              assertEquals(result, expectedResult)
              assertEquals(afterSlots, beforeSlots)
              assertEquals(afterCover, beforeCover)
              assertEquals(photoCount, expectedPhotos)
              assertEquals(placements, expectedPhotos)
              if !committed && stage =!= "unchanged" then assertEquals(after, before)
              after match
                case AlbumRepository.GetResult.Found(saved) =>
                  if committed || stage === "unchanged" then
                    assertEquals(saved.chapters.map(_.sourceFolder), Vector(requestedSource))
                    assertEquals(saved.chapters.map(_.destination.countries.map(_.id)), Vector(NonEmptyList.one(otherCountry.id)))
                case result => fail(s"Expected persisted album, obtained $result")
              pending match
                case listed: PhotoRepository.ListUnfinishedWritesResult.Listed =>
                  val expected = if !committed then Vector.empty[PhotoWriteIntent]
                  else
                    locations.zipWithIndex.map { case (location, index) =>
                      PhotoWriteIntent.Retirement(PhotoWriteIntentId(new UUID(8L, 200L + index)), first.id, PhotoId(location.value), location)
                    }
                  assertEquals(listed.intents, expected)
                case PhotoRepository.ListUnfinishedWritesResult.Failed => fail("Expected persisted evidence")

        change >> buildConnection(path).use: xa =>
          given Transaction[ConnectionIO, IO] with
            override def run[A](program: ConnectionIO[A]): IO[A]     = program.transact(xa)
            override def resolve[A](program: ConnectionIO[A]): IO[A] = program.transact(xa)
          val recovery = PhotoWriteRecovery.make(photos, storage)

          for
            recovered <- recovery.recover
            pending   <- sql"SELECT count(*) FROM photo_write_intent".query[Int].unique.transact(xa)
            remaining <- locations.traverse(location => Files.exists(root.resolve(s"${location.value}.jpg")).blocking[IO])
          yield
            assertEquals(recovered, PhotoWriteRecovery.RecoveryResult.Recovered)
            assertEquals(pending, 0)
            assertEquals(remaining, Vector.fill(2)(!committed))
      }
    }

  test("should resolve saved chapter sources without photo rows and reject malformed stored sources"):
    buildTransactor.use: xa =>
      val repository = SqliteChapterRepository.make
      val missing    = ChapterId(new UUID(99L, 99L))

      for
        _       <- seedAlbum(xa)
        chapter <- buildChapter(xa, index = 1)
        _       <- repository.create(albumId, chapter, order = NonEmptyList.one(chapter.id)).transact(xa)
        found   <- repository.findSource(chapter.id).transact(xa)
        absent  <- repository.findSource(missing).transact(xa)
        _       <- sql"UPDATE chapter SET source_folder = '../invalid'".update.run.transact(xa)
        invalid <- repository.findSource(chapter.id).transact(xa).attempt
      yield
        assertEquals(found, ChapterRepository.SourceResult.Found(albumId, chapter.sourceFolder))
        assertEquals(absent, ChapterRepository.SourceResult.ChapterNotFound)
        assert(invalid.isLeft)

  private def insertCoverPhoto(chapter: ChapterId) =
    val photo       = new UUID(7L, 6L).toString
    val placement   = new UUID(7L, 7L).toString
    val insertPhoto = sql"""
      INSERT INTO photo (
        id, chapter_id, source, source_hash, width, height, thumbnail,
        thumbnail_version, thumbnail_width, thumbnail_height, thumbnail_quality, thumbnail_bytes
      )
      VALUES (
        $photo, ${chapter.value}, 'cover.jpg', 'hash', 100, 100, $photo,
        'version', 100, 100, 'low', 1
      )
    """
    val insertPlacement = sql"""
      INSERT INTO placement (
        id, photo_id, kind, cover_slot_id, shift_x, shift_y, mirrored, zoom
      )
      SELECT $placement, $photo, 'cover', id, 0, 0, 0, 1
      FROM cover_slot
      WHERE side = 'front'
    """
    insertPhoto.update.run >> insertPlacement.update.run.void

  private def buildDirectory =
    Resource.make(Files.createTempDirectory(Path.of("target"), "source-thumbnails-").toAbsolutePath.blocking[IO]): root =>
      Vector(new UUID(7L, 1L), new UUID(7L, 6L)).traverse_(id => Files.deleteIfExists(root.resolve(s"$id.jpg")).blocking[IO].void) >>
        Files.deleteIfExists(root).blocking[IO].void

  private def insertPhotoComposition(chapter: ChapterId, opening: SpreadId) =
    val photo           = new UUID(7L, 1L).toString
    val placement       = new UUID(7L, 2L).toString
    val template        = new UUID(7L, 3L).toString
    val spread          = new UUID(7L, 4L).toString
    val slot            = new UUID(7L, 5L).toString
    val insertPlacement = sql"""INSERT INTO placement
      (id, photo_id, kind, spread_photo_slot_id, shift_x, shift_y, mirrored, zoom, caption_corner)
      SELECT $placement, $photo, 'spread', id, 0, 0, 0, 1, 'bottom_left'
      FROM spread_photo_slot WHERE spread_id = ${opening.value} ORDER BY id LIMIT 1"""
    val insertSpread = sql"""INSERT INTO spread (id, template_id, kind, chapter_id, position)
      VALUES ($spread, $template, 'photos_only', ${chapter.value}, 1)"""
    val insertPhoto = sql"""INSERT INTO photo (id, chapter_id, source, source_hash, width, height, thumbnail,
      thumbnail_version, thumbnail_width, thumbnail_height, thumbnail_quality, thumbnail_bytes)
      VALUES ($photo, ${chapter.value}, 'photo.jpg', 'hash', 100, 100, $photo, 'version', 100, 100, 'low', 1)"""
    for
      _ <- insertPhoto.update.run
      _ <- insertPlacement.update.run
      _ <- sql"INSERT INTO spread_template (id, name, kind) VALUES ($template, 'Photo layout', 'photos_only')".update.run
      _ <- sql"INSERT INTO spread_template_slot (template_id, kind, x, y, width, height) VALUES ($template, 'photo', 0, 0, 100, 100)".update.run
      _ <- insertSpread.update.run
      _ <- sql"INSERT INTO spread_photo_slot (id, spread_id, x, y, width, height) VALUES ($slot, $spread, 0, 0, 100, 100)".update.run
    yield ()

  private def buildTransactor =
    buildDatabase.flatMap(buildConnection)

  private def buildDatabase =
    val acquireDatabase = Files.createTempFile(Path.of("target"), "chapter-", ".sqlite").blocking[IO]
    Resource.make(acquireDatabase)(path => Files.deleteIfExists(path).blocking[IO].void)
      .evalTap(path => FlywayMigrator.make[IO](path).use(_.migrate).void)

  private def buildConnection(path: Path) =
    SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 2, maxSessions = 2)).buildTransactor

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
      case value => new IllegalStateException(s"Missing album templates: $value").raiseError[IO, Unit]

  private def buildAlbum(template: SpreadTemplate.AlbumStart) =
    val photos = template.photoSlots.zipWithIndex.map { case (box, index) =>
      SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(4L, index.toLong)), box, bleed = Set.empty[SpreadEdge])
    }
    val textSlot        = SpreadSlot.Text(TextSpreadSlotId(new UUID(4L, 11L)), template.textSlot)
    val emptyPlacements = Vector.empty[SpreadPlacement]
    val opening         = Spread.AlbumStart(SpreadId(new UUID(4L, 10L)), template.id, photoSlots = photos, textSlot, placements = emptyPlacements)
    val album           = Album(albumId, year = 2026, title = "Album", synopsis = "", Instant.EPOCH, opening, chapters = Vector.empty[Chapter])
    val cover           = Cover(albumId, SpineOwner.Front, CoverSlot(CoverSlotId(new UUID(4L, 12L))), CoverSlot(CoverSlotId(new UUID(4L, 13L))))
    (album, cover)

  private def buildChapter(xa: Transactor[IO], index: Int) =
    SqliteSpreadTemplateRepository.make[IO](xa).listChapterStartTemplates.flatMap:
      case SpreadTemplateRepository.ListChapterStartTemplatesResult.Listed(values) =>
        val template = values.head
        val photos   = template.photoSlots.zipWithIndex.map { case (box, position) =>
          SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(index.toLong, position.toLong)), box, bleed = Set(SpreadEdge.Left))
        }
        val textSlot        = SpreadSlot.Text(TextSpreadSlotId(new UUID(index.toLong, 101L)), template.textSlot)
        val emptyPlacements = Vector.empty[SpreadPlacement]
        val opening         =
          Spread.ChapterStart(SpreadId(new UUID(index.toLong, 100L)), template.id, photoSlots = photos, textSlot, placements = emptyPlacements)
        val chapterId                 = ChapterId(new UUID(index.toLong, 102L))
        val destinationId             = DestinationId(new UUID(index.toLong, 103L))
        val countryIds                = NonEmptyList.of(country.id, otherCountry.id)
        val sourceFolder: PhotoSource = s"/photos/$index".refineUnsafe
        val description               = "Retained description"
        ChapterRepository.NewChapter(
          chapterId,
          destinationId,
          name = "Trip",
          countries = countryIds,
          period,
          description = description,
          sourceFolder,
          opening
        ).pure[IO]
      case value => new IllegalStateException(s"Missing chapter templates: $value").raiseError[IO, ChapterRepository.NewChapter]
