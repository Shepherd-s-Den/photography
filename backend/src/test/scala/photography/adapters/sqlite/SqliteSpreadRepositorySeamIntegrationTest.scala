package photography.adapters.sqlite

import photography.adapters.sqlite.FlywayMigrator
import photography.adapters.sqlite.SqliteConnection
import photography.adapters.sqlite.SqliteAlbumRepository
import photography.adapters.sqlite.SqliteChapterRepository
import photography.adapters.sqlite.SqlitePhotoPlacementRepository
import photography.adapters.sqlite.SqlitePhotoRepository

import cats.data.NonEmptyList
import cats.effect.std.Mutex
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import org.typelevel.doobie.implicits.*
import org.typelevel.doobie.{ConnectionIO, Transactor}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.adapters.sqlite.SqliteCodecs.given
import photography.app.config.SqliteConfig
import photography.capabilities.{IdGenerator, Transaction}
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.AlbumRepository
import photography.ports.ChapterRepository
import photography.ports.SpreadTemplateRepository
import photography.usecases.{ChapterManager, ChapterPlanning}
import photography.usecases.{ChapterPhotoImport, PhotoInventory}
import photography.usecases.{SpreadManager, SpreadTemplateManager}
import photography.usecases.SpreadManager.*

import java.nio.file.{Files, Path}
import java.time.{Instant, LocalDate}
import java.util.UUID

final class SqliteSpreadRepositorySeamIntegrationTest extends CatsEffectSuite:
  private given logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  private val albumId       = AlbumId(new UUID(1L, 1L))
  private val chapterId     = ChapterId(new UUID(1L, 2L))
  private val photoId       = PhotoId(new UUID(77L, 1L))
  private val placementId   = SpreadPlacementId(new UUID(78L, 1L))
  private val defaultLayout = SpreadTemplateId(UUID.fromString("00000000-0000-0000-0000-000000000113"))
  private val singleLayout  = SpreadTemplateId(UUID.fromString("00000000-0000-0000-0000-000000000144"))
  private val identity      = PhotoTransform(Shift(x = 0.0, y = 0.0), mirrored = false, zoom = 1.0)
  private val country       = Country(CountryId(new UUID(9L, 1L)), code = "XX", name = "Test country", AbsoluteLocation(Path.of("/flags/xx.svg")))
  private val spreads       = SqliteSpreadRepository.make
  private val spreadRows    = sql"SELECT count(*) FROM spread".query[Long].unique
  private val photoRows     = sql"SELECT count(*) FROM photo".query[Long].unique
  private val placementRows = sql"SELECT count(*) FROM placement".query[Long].unique
  private val chapterOrder  = sql"SELECT position FROM spread WHERE chapter_id = ${chapterId.value} ORDER BY position".query[Int].to[List]

  test("should persist insert, reorder, template replacement and removal with placement cascades"):
    buildTransactor.use: xa =>
      for
        opening    <- seedChapter(xa)
        photo      <- buildPhotoSpread(xa, index = 5L, layout = defaultLayout)
        _          <- (spreads.insert(chapterId, photo) >> spreads.reorder(chapterId, order = NonEmptyList.of(opening.id, photo.id))).transact(xa)
        _          <- placePhoto(xa, photo.photoSlots.head.id, index = 1L)
        before     <- spreads.readChapter(chapterId).map { case (_, photos) => photos }.transact(xa)
        replaced   <- retemplate(xa, photo)
        _          <- spreads.replace(replaced).transact(xa)
        after      <- spreads.readChapter(chapterId).map { case (_, photos) => photos }.transact(xa)
        _          <- spreads.remove(photo.id).transact(xa)
        removed    <- spreads.readChapter(chapterId).map { case (_, photos) => photos }.transact(xa)
        photos     <- photoRows.transact(xa)
        placements <- placementRows.transact(xa)
      yield
        val beforeSpreads = before
        val afterSpreads  = after
        assertEquals(beforeSpreads.map(_.placements.map(placed => (placed.id, placed.photo))), Vector(Vector((placementId, photoId))))
        assertEquals(afterSpreads.map(_.template), Vector(singleLayout))
        assertEquals(afterSpreads.flatMap(_.placements).map(_.transform), Vector(identity))
        assertEquals(afterSpreads.flatMap(_.placements).map(_.slot), afterSpreads.flatMap(_.photoSlots.toList).map(_.id))
        assertEquals(removed, Vector.empty[Spread.PhotosOnly])
        assertEquals(photos, 1L)
        assertEquals(placements, 0L)

  test("should roll back every spread write of a failed action and reject incomplete chapter orders"):
    buildTransactor.use: xa =>
      val failure = new IllegalStateException("Abort composed action")
      for
        opening    <- seedChapter(xa)
        committed  <- buildPhotoSpread(xa, index = 6L, layout = defaultLayout)
        second     <- buildPhotoSpread(xa, index = 7L, layout = defaultLayout)
        _          <- spreads.insert(chapterId, committed).transact(xa)
        failed     <- (spreads.insert(chapterId, second) >> failure.raiseError[ConnectionIO, Unit]).transact(xa).attempt
        rows       <- spreadRows.transact(xa)
        incomplete <- spreads.reorder(chapterId, order = NonEmptyList.one(opening.id)).transact(xa).attempt
        positions  <- chapterOrder.transact(xa)
      yield
        assert(failed.isLeft)
        assertEquals(rows, 3L)
        assert(incomplete.isLeft)
        assertEquals(positions, List(0, 1))

  test("should keep one chapter's positions contiguous when concurrent additions commit through the real mutex"):
    buildTransactor.use: xa =>
      Mutex[IO].flatMap: mutex =>
        given coordination: Mutex[IO] = mutex
        val manager                   = buildManager(xa)

        for
          opening   <- seedChapter(xa)
          results   <- (1 to 5).toList.parTraverse(_ => manager.add(albumId, chapterId, after = opening.id))
          positions <- chapterOrder.transact(xa)
        yield
          assertEquals(results.collect { case AddResult.Added(id) => id }.size, 5)
          assertEquals(positions, List(0, 1, 2, 3, 4, 5))

  test("should move a valid photo spread without validating an unrelated incomplete cover"):
    buildTransactor.use: xa =>
      Mutex[IO].flatMap: mutex =>
        given coordination: Mutex[IO] = mutex
        val manager                   = buildManager(xa)

        for
          _       <- seedChapter(xa)
          first   <- buildPhotoSpread(xa, index = 15L, layout = defaultLayout)
          second  <- buildPhotoSpread(xa, index = 16L, layout = defaultLayout)
          _       <- (spreads.insert(chapterId, first) >> spreads.insert(chapterId, second)).transact(xa)
          _       <- sql"DELETE FROM cover_slot WHERE album_id = ${albumId.value} AND side = 'back'".update.run.transact(xa)
          result  <- manager.move(albumId, second.id, SpreadManager.Direction.Earlier)
          ordered <- sql"SELECT id FROM spread WHERE chapter_id = ${chapterId.value} ORDER BY position".query[UUID].to[List].transact(xa)
        yield
          assertEquals(result, SpreadManager.MoveResult.Moved(second.id))
          assertEquals(ordered.drop(1), List(second.id.value, first.id.value))

  test("should decide concurrent occupied confirmations from the state committed by the preceding mutation"):
    buildTransactor.use: xa =>
      Mutex[IO].flatMap: mutex =>
        given coordination: Mutex[IO] = mutex
        val manager                   = buildManager(xa)

        for
          _           <- seedChapter(xa)
          photo       <- buildPhotoSpread(xa, index = 20L, layout = defaultLayout)
          _           <- spreads.insert(chapterId, photo).transact(xa)
          _           <- placePhoto(xa, photo.photoSlots.head.id, index = 1L)
          maybeBefore <- spreads.find(albumId, photo.id).transact(xa)
          deleteRequest = manager.delete(albumId, photo.id, confirmPlacements = false)
          changeRequest = manager.changeTemplate(albumId, photo.id, singleLayout, confirmPlacements = false)
          warnings       <- (deleteRequest, changeRequest).parTupled
          maybeUnchanged <- spreads.find(albumId, photo.id).transact(xa)
          confirmed      <- List.fill(2)(()).parTraverse(_ => manager.delete(albumId, photo.id, confirmPlacements = true))
          remaining      <- placementRows.transact(xa)
        yield
          assertEquals(warnings, (DeleteResult.Occupied(1), ChangeTemplateResult.Occupied(1)))
          assertEquals(maybeUnchanged.map(_.spread), maybeBefore.map(_.spread))
          assertEquals(confirmed.collect { case DeleteResult.Deleted => () }.size, 1)
          assertEquals(confirmed.collect { case DeleteResult.SpreadNotFound => () }.size, 1)
          assertEquals(remaining, 0L)

  test("should roll back a destructive replacement when its final placement insert fails"):
    buildTransactor.use: xa =>
      for
        _           <- seedChapter(xa)
        photo       <- buildPhotoSpread(xa, index = 21L, layout = defaultLayout)
        _           <- spreads.insert(chapterId, photo).transact(xa)
        _           <- placePhoto(xa, photo.photoSlots.head.id, index = 1L)
        maybeBefore <- spreads.find(albumId, photo.id).transact(xa)
        replacement <- retemplate(xa, photo)
        broken = replacement.copy(placements = replacement.placements.map(_.copy(photo = PhotoId(new UUID(99L, 99L)))))
        failed     <- spreads.replace(broken).transact(xa).attempt
        maybeAfter <- spreads.find(albumId, photo.id).transact(xa)
      yield
        assert(failed.isLeft)
        assertEquals(maybeAfter.map(_.spread), maybeBefore.map(_.spread))

  test("should distinguish absent chapter ownership from malformed persisted chapter ordering"):
    buildTransactor.use: xa =>
      val foreignAlbum = AlbumId(new UUID(12L, 1L))

      for
        opening       <- seedChapter(xa)
        maybeForeign  <- spreads.findOrder(foreignAlbum, chapterId).transact(xa)
        _             <- sql"DELETE FROM spread WHERE id = ${opening.id.value}".update.run.transact(xa)
        malformed     <- spreads.findOrder(albumId, chapterId).transact(xa).attempt
        malformedRead <- spreads.readChapter(chapterId).transact(xa).attempt
      yield
        assertEquals(maybeForeign, none[NonEmptyList[SpreadId]])
        assert(malformed.isLeft)
        assert(malformedRead.isLeft)

  test("should reject stale and foreign album targets without changing either album"):
    buildTransactor.use: xa =>
      Mutex[IO].flatMap: mutex =>
        given coordination: Mutex[IO] = mutex
        val manager                   = buildManager(xa)
        val otherAlbum                = AlbumId(new UUID(11L, 1L))
        val insertOther = sql"INSERT INTO album (id, year, title, synopsis, created_at_ms) VALUES (${otherAlbum.value}, 2025, 'Other', '', 0)"

        for
          opening         <- seedChapter(xa)
          _               <- insertOther.update.run.transact(xa)
          photo           <- buildPhotoSpread(xa, index = 22L, layout = defaultLayout)
          _               <- spreads.insert(chapterId, photo).transact(xa)
          foreignMove     <- manager.move(otherAlbum, photo.id, Direction.Earlier)
          foreignTemplate <- manager.changeTemplate(otherAlbum, photo.id, singleLayout, confirmPlacements = true)
          foreignDelete   <- manager.delete(otherAlbum, photo.id, confirmPlacements = true)
          foreignAdd      <- manager.add(otherAlbum, chapterId, opening.id)
          removed         <- manager.delete(albumId, photo.id, confirmPlacements = true)
          staleMove       <- manager.move(albumId, photo.id, Direction.Earlier)
          staleTemplate   <- manager.changeTemplate(albumId, photo.id, singleLayout, confirmPlacements = true)
          staleDelete     <- manager.delete(albumId, photo.id, confirmPlacements = true)
          staleAdd        <- manager.add(albumId, chapterId, photo.id)
          rows            <- spreadRows.transact(xa)
        yield
          assertEquals(foreignMove, MoveResult.SpreadNotFound)
          assertEquals(foreignTemplate, ChangeTemplateResult.SpreadNotFound)
          assertEquals(foreignDelete, DeleteResult.SpreadNotFound)
          assertEquals(foreignAdd, AddResult.ChapterNotFound)
          assertEquals(removed, DeleteResult.Deleted)
          assertEquals(staleMove, MoveResult.SpreadNotFound)
          assertEquals(staleTemplate, ChangeTemplateResult.SpreadNotFound)
          assertEquals(staleDelete, DeleteResult.SpreadNotFound)
          assertEquals(staleAdd, AddResult.SpreadNotFound)
          assertEquals(rows, 2L)

  test("should retain placements by template slot order despite gaps and reversed placement identities, then expose released inventory"):
    buildTransactor.use: xa =>
      Mutex[IO].flatMap: mutex =>
        given coordination: Mutex[IO] = mutex
        val manager                   = buildManager(xa)
        val inventory                 = buildInventory(xa)

        for
          _            <- seedChapter(xa)
          photo        <- buildPhotoSpread(xa, index = 23L, layout = defaultLayout)
          _            <- spreads.insert(chapterId, photo).transact(xa)
          _            <- placeGappedPhotos(xa, slots = photo.photoSlots)
          three        <- findPhotoTemplate(xa, count = 3)
          before       <- inventory.listChapter(chapterId)
          changed      <- manager.changeTemplate(albumId, photo.id, three.id, confirmPlacements = true)
          maybeCurrent <- spreads.find(albumId, photo.id).transact(xa)
          after        <- inventory.listChapter(chapterId)
          deleted      <- manager.delete(albumId, photo.id, confirmPlacements = true)
          released     <- inventory.listChapter(chapterId)
        yield
          assertEquals(changed, ChangeTemplateResult.Changed(photo.id, three.id))
          maybeCurrent.map(_.spread) match
            case Some(value: Spread.PhotosOnly) =>
              val retained = value.placements.map(placed => (placed.id, placed.photo, placed.captionCorner, placed.transform))
              val expected = Vector(
                (buildPlacementId(index = 2L), buildPhotoId(index = 2L), CaptionCorner.TopRight, identity),
                (buildPlacementId(index = 3L), buildPhotoId(index = 3L), CaptionCorner.TopRight, identity)
              )
              assertEquals(retained, expected)
              val thirdSlot = value.photoSlots.toList.lift(2).getOrElse(fail("Missing third slot")).id
              assertEquals(value.placements.map(_.slot), Vector(thirdSlot, value.photoSlots.head.id))
            case other => fail(s"Expected photo spread, obtained $other")
          assertEquals(describeInventory(before), (3, 0))
          assertEquals(describeInventory(after), (3, 1))
          assertEquals(deleted, DeleteResult.Deleted)
          assertEquals(describeInventory(released), (3, 3))

  test("should confirm occupied chapter opening template changes while retaining required text and ordered photos"):
    buildTransactor.use: xa =>
      Mutex[IO].flatMap: mutex =>
        given coordination: Mutex[IO] = mutex
        val manager                   = buildManager(xa)

        for
          opening <- seedChapter(xa)
          _       <- placePhoto(xa, opening.photoSlots.lift(1).getOrElse(fail("Missing opening slot")).id, index = 1L)
          layout  <- loadChapterTemplate(xa)
          warning <- manager.changeTemplate(albumId, opening.id, layout.id, confirmPlacements = false)
          changed <- manager.changeTemplate(albumId, opening.id, layout.id, confirmPlacements = true)
          current <- spreads.readChapter(chapterId).transact(xa)
        yield
          val (retained, photos) = current
          assertEquals(warning, ChangeTemplateResult.Occupied(1))
          assertEquals(changed, ChangeTemplateResult.Changed(opening.id, layout.id))
          assertEquals(retained.textSlot.box, layout.textSlot)
          val secondSlot = retained.photoSlots.lift(1).getOrElse(fail("Missing second slot")).id
          assertEquals(retained.placements.map(_.slot), Vector(secondSlot))
          assertEquals(retained.placements.map(_.transform), Vector(identity))
          assertEquals(photos, Vector.empty[Spread.PhotosOnly])

  test(s"should coordinate chapter creation and spread addition at the shared ${InteriorSpreadCapacity.maximum} spread capacity"):
    buildTransactor.use: xa =>
      Mutex[IO].flatMap: mutex =>
        given coordination: Mutex[IO] = mutex
        val manager                   = buildManager(xa)
        val chapters                  = buildChapterManager(xa)
        val period                    = ChapterManager.Period.Dates(VisitPeriod(LocalDate.of(2026, 8, 1), days = 2L))
        val createChapter             =
          chapters.create(albumId, name = "Later trip", countries = NonEmptyList.one(country.id), period, sourceFolder = "/photos/later")

        for
          opening <- seedChapter(xa)
          photos  <- (1 to 62).toVector.traverse(index => buildPhotoSpread(xa, index = 100L + index.toLong, layout = defaultLayout))
          _       <- photos.traverse_(spreads.insert(chapterId, _)).transact(xa)
          results <- (manager.add(albumId, chapterId, opening.id), createChapter).parTupled
          count   <- spreads.countInterior(albumId).transact(xa)
        yield
          val (spread, chapter) = results
          val spreadWon         = spread match
            case _: AddResult.Added                      => true
            case AddResult.Rejected(Conflict.AtCapacity) => false
            case other                                   => fail(s"Unexpected spread result: $other")
          val chapterWon = chapter match
            case _: ChapterManager.CreateResult.Created                           => true
            case ChapterManager.CreateResult.Rejected(ChapterConflict.AtCapacity) => false
            case other                                                            => fail(s"Unexpected chapter result: $other")
          assertEquals(spreadWon, !chapterWon)
          assertEquals(count, InteriorSpreadCapacity.maximum)

  private def seedChapter(xa: Transactor[IO]) =
    val insertCountry = sql"INSERT INTO country VALUES (${country.id.value}, 'XX', 'Test country', '/flags/xx.svg')".update.run.transact(xa)
    val repository    = SqliteAlbumRepository.make[IO](xa, spreads)
    val cover         = Cover(albumId, SpineOwner.Front, CoverSlot(CoverSlotId(new UUID(1L, 12L))), CoverSlot(CoverSlotId(new UUID(1L, 13L))))
    for
      _            <- insertCountry
      albumStart   <- loadAlbumTemplate(xa)
      chapterStart <- loadChapterTemplate(xa)
      created      <- repository.create(buildAlbum(albumStart), cover)
      _            <- IO(assertEquals(created, AlbumRepository.CreateResult.Created))
      opening    = buildOpening(chapterStart)
      newChapter = ChapterRepository.NewChapter(
        chapterId,
        DestinationId(new UUID(3L, 1L)),
        name = "Trip",
        countries = NonEmptyList.one(country.id),
        VisitPeriod(firstDay = LocalDate.of(2026, 6, 1), days = 3L),
        description = "Retained description",
        sourceFolder = "/photos/trip",
        opening
      )
      _ <- SqliteChapterRepository.make.create(albumId, newChapter, order = NonEmptyList.one(chapterId)).transact(xa)
    yield opening

  private def loadAlbumTemplate(xa: Transactor[IO]) =
    SqliteSpreadTemplateRepository.make[IO](xa).listAlbumStartTemplates.flatMap:
      case SpreadTemplateRepository.ListAlbumStartTemplatesResult.Listed(values) => values.head.pure[IO]
      case other => IO.raiseError[SpreadTemplate.AlbumStart](new IllegalStateException(s"Missing album templates: $other"))

  private def loadChapterTemplate(xa: Transactor[IO]) =
    SqliteSpreadTemplateRepository.make[IO](xa).listChapterStartTemplates.flatMap:
      case SpreadTemplateRepository.ListChapterStartTemplatesResult.Listed(values) => values.head.pure[IO]
      case other => IO.raiseError[SpreadTemplate.ChapterStart](new IllegalStateException(s"Missing chapter templates: $other"))

  private def buildAlbum(template: SpreadTemplate.AlbumStart) =
    val photos = template.photoSlots.zipWithIndex.map { case (box, index) =>
      SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(1L, 100L + index.toLong)), box, bleed = Set.empty[SpreadEdge])
    }
    val opening = Spread.AlbumStart(
      SpreadId(new UUID(1L, 10L)),
      template.id,
      photoSlots = photos,
      textSlot = SpreadSlot.Text(TextSpreadSlotId(new UUID(1L, 11L)), template.textSlot),
      placements = Vector.empty[SpreadPlacement]
    )
    Album(albumId, year = 2026, title = "Album", synopsis = "", Instant.EPOCH, opening, chapters = Vector.empty[Chapter])

  private def buildOpening(template: SpreadTemplate.ChapterStart) =
    val photos = template.photoSlots.zipWithIndex.map { case (box, index) =>
      SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(2L, 100L + index.toLong)), box, bleed = Set.empty[SpreadEdge])
    }
    Spread.ChapterStart(
      SpreadId(new UUID(2L, 10L)),
      template.id,
      photoSlots = photos,
      textSlot = SpreadSlot.Text(TextSpreadSlotId(new UUID(2L, 11L)), template.textSlot),
      placements = Vector.empty[SpreadPlacement]
    )

  private def buildPhotoSpread(xa: Transactor[IO], index: Long, layout: SpreadTemplateId) =
    SqliteSpreadTemplateRepository.make[IO](xa).listPhotosOnlyTemplates.flatMap:
      case SpreadTemplateRepository.ListPhotosOnlyTemplatesResult.Listed(values) =>
        values.find(_.id.value === layout.value) match
          case Some(template) =>
            val slots = template.photoSlots.zipWithIndex.map { case (box, position) =>
              SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(index, position.toLong)), box, bleed = Set.empty[SpreadEdge])
            }
            Spread.PhotosOnly(SpreadId(new UUID(index, 100L)), template.id, photoSlots = slots, placements = Vector.empty[SpreadPlacement]).pure[IO]
          case None => IO.raiseError[Spread.PhotosOnly](new IllegalStateException(s"Missing photo layout $layout"))
      case other => IO.raiseError[Spread.PhotosOnly](new IllegalStateException(s"Missing photo layouts: $other"))

  private def retemplate(xa: Transactor[IO], photo: Spread.PhotosOnly) =
    SqliteSpreadTemplateRepository.make[IO](xa).listPhotosOnlyTemplates.flatMap:
      case SpreadTemplateRepository.ListPhotosOnlyTemplatesResult.Listed(values) =>
        values.find(_.id.value === singleLayout.value) match
          case Some(template) =>
            val slot = SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(5L, 900L)), template.photoSlots.head, bleed = Set.empty[SpreadEdge])
            val kept = SpreadPlacement(placementId, slot.id, photoId, identity, CaptionCorner.TopRight)
            Spread.PhotosOnly(photo.id, singleLayout, photoSlots = NonEmptyList.one(slot), placements = Vector(kept)).pure[IO]
          case None => IO.raiseError[Spread.PhotosOnly](new IllegalStateException("Missing single layout"))
      case other => IO.raiseError[Spread.PhotosOnly](new IllegalStateException(s"Missing photo layouts: $other"))

  private def placePhoto(xa: Transactor[IO], slot: PhotoSpreadSlotId, index: Long) =
    val photo       = buildPhotoId(index).value
    val placement   = buildPlacementId(index).value
    val source      = s"$index.jpg"
    val thumbnail   = new UUID(88L, index).toString
    val insertPhoto = sql"""INSERT INTO photo (id, chapter_id, source, source_hash, thumbnail, thumbnail_version, thumbnail_width, thumbnail_height,
                           thumbnail_quality, thumbnail_bytes, width, height)
                           VALUES ($photo, ${chapterId.value}, $source, 'hash', $thumbnail, 'v1', 10, 10, 'standard', 10, 10, 10)"""
    val insertPlace =
      sql"""INSERT INTO placement (id, photo_id, kind, spread_photo_slot_id, cover_slot_id, shift_x, shift_y, mirrored, zoom, caption_corner)
                           VALUES ($placement, $photo, 'spread', ${slot.value}, NULL, 3.0, 4.0, 1, 2.0, 'top_right')"""
    (insertPhoto.update.run >> insertPlace.update.run).transact(xa).void

  private def placeGappedPhotos(xa: Transactor[IO], slots: NonEmptyList[SpreadSlot.Photo]) =
    Vector((0, 3L), (2, 2L), (7, 1L)).traverse_ { case (position, index) =>
      val slot = slots.toList.lift(position).getOrElse(fail("Missing slot"))
      placePhoto(xa, slot.id, index = index)
    }

  private def buildPhotoId(index: Long)     = PhotoId(new UUID(77L, index))
  private def buildPlacementId(index: Long) = SpreadPlacementId(new UUID(78L, index))

  private def findPhotoTemplate(xa: Transactor[IO], count: Int) =
    SqliteSpreadTemplateRepository.make[IO](xa).listPhotosOnlyTemplates.flatMap:
      case SpreadTemplateRepository.ListPhotosOnlyTemplatesResult.Listed(values) =>
        values.find(_.photoSlots.length === count).liftTo[IO](new IllegalStateException(s"Missing $count photo layout"))
      case other => IO.raiseError[SpreadTemplate.PhotosOnly](new IllegalStateException(s"Missing catalog: $other"))

  private def describeInventory(result: PhotoInventory.ListResult) = result match
    case PhotoInventory.ListResult.Listed(value) => (value.total, value.countUnplaced)
    case other                                   => fail(s"Expected inventory, obtained $other")

  private def buildManager(xa: Transactor[IO])(using Mutex[IO]) =
    given ids: IdGenerator[IO] = IdGenerator.make[IO]
    given transaction: Transaction[ConnectionIO, IO] with
      override def run[A](program: ConnectionIO[A]): IO[A]     = program.transact(xa)
      override def resolve[A](program: ConnectionIO[A]): IO[A] = program.transact(xa)
    val templates = SpreadTemplateManager.make(SqliteSpreadTemplateRepository.make[IO](xa))
    SpreadManager.make(templates, spreads, defaultLayout)

  private def buildInventory(xa: Transactor[IO]) =
    given transaction: Transaction[ConnectionIO, IO] with
      override def run[A](program: ConnectionIO[A]): IO[A]     = program.transact(xa)
      override def resolve[A](program: ConnectionIO[A]): IO[A] = program.transact(xa)
    PhotoInventory.make[IO, ConnectionIO](SqlitePhotoRepository.make, SqlitePhotoPlacementRepository.make, SqliteChapterRepository.make)

  private def buildChapterManager(xa: Transactor[IO])(using Mutex[IO]) =
    given ids: IdGenerator[IO] = IdGenerator.make[IO]
    given transaction: Transaction[ConnectionIO, IO] with
      override def run[A](program: ConnectionIO[A]): IO[A]     = program.transact(xa)
      override def resolve[A](program: ConnectionIO[A]): IO[A] = program.transact(xa)
    val templates = SpreadTemplateManager.make(SqliteSpreadTemplateRepository.make[IO](xa))
    val albums    = SqliteAlbumRepository.make[IO](xa, spreads)
    val imports   = new ChapterPhotoImport[IO]:
      override def prepare(album: AlbumId, chapter: ChapterId, source: PhotoSource): IO[ChapterPhotoImport.Request] =
        ChapterPhotoImport.Request(album, chapter, source, generation = BigInt(0)).pure[IO]
      override def launch(request: ChapterPhotoImport.Request): IO[ChapterPhotoImport.Scheduling]     = ChapterPhotoImport.Scheduling.Queued.pure[IO]
      override def retire(album: AlbumId, retirements: Vector[PhotoWriteIntent.Retirement]): IO[Unit] = IO.unit
      override def suspend(chapter: ChapterId): IO[Unit]                                              = IO.unit
      override def status(chapter: ChapterId): IO[ChapterPhotoImport.ExecutionOutcome]                =
        IO.raiseError[ChapterPhotoImport.ExecutionOutcome](new AssertionError("Unexpected import status"))
    ChapterManager.make(albums, SqliteChapterRepository.make, ChapterPlanning.make(templates), imports, SqlitePhotoRepository.make)

  private def buildTransactor =
    val acquireDatabase = IO.blocking(Files.createTempFile(Path.of("target"), "spread-", ".sqlite"))
    for
      path <- Resource.make(acquireDatabase)(path => IO.blocking(Files.deleteIfExists(path)).void)
      _    <- FlywayMigrator.make[IO](path).evalMap(_.migrate)
      xa   <- SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 2, maxSessions = 2)).buildTransactor
    yield xa
