package photography.usecases

import cats.data.NonEmptyList
import cats.effect.{IO, Ref}
import cats.effect.std.Mutex
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import photography.capabilities.IdGenerator
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.{AlbumRepository, ChapterRepository}
import photography.usecases.ChapterManager.{CreateResult, Period, UpdateResult}
import photography.usecases.SpreadTemplateManager.ListChapterStartTemplatesResult

import java.nio.file.Path
import java.time.{Instant, LocalDate}
import java.util.UUID

final class ChapterManagerComponentTest extends CatsEffectSuite:
  private val homeName: DestinationName = "Home"
  private def countries                 = NonEmptyList.of(
    Country(CountryId(id(21)), "JP", "Japan", AbsoluteLocation(Path.of("/flags/jp.svg"))),
    Country(CountryId(id(22)), "KR", "South Korea", AbsoluteLocation(Path.of("/flags/kr.svg"))),
    Country(CountryId(id(23)), "TW", "Taiwan", AbsoluteLocation(Path.of("/flags/tw.svg"))),
    Country(CountryId(id(24)), "SG", "Singapore", AbsoluteLocation(Path.of("/flags/sg.svg")))
  )
  private def box      = SlotBox(0.0, 0.0, 100.0, 100.0)
  private def template = SpreadTemplate.ChapterStart(SpreadTemplateId(id(4)), "Default", Vector(box), box)
  private def album    = Album(
    AlbumId(id(1)),
    2026,
    "A year abroad",
    "",
    Instant.EPOCH,
    Spread.AlbumStart(
      SpreadId(id(3)),
      template.id,
      photoSlots = Vector.empty[SpreadSlot.Photo],
      SpreadSlot.Text(TextSpreadSlotId(id(5)), box),
      placements = Vector.empty[SpreadPlacement]
    ),
    chapters = Vector.empty[Chapter]
  )
  private def chapter = Chapter(
    ChapterId(id(20)),
    Destination(DestinationId(id(30)), "Asian capitals", countries),
    VisitPeriod(LocalDate.of(2026, 5, 1), 2),
    "",
    "/photos/capitals",
    Spread.ChapterStart(
      SpreadId(id(40)),
      template.id,
      photoSlots = Vector.empty[SpreadSlot.Photo],
      SpreadSlot.Text(TextSpreadSlotId(id(41)), box),
      placements = Vector.empty[SpreadPlacement]
    ),
    spreads = Vector.empty[Spread.PhotosOnly]
  )
  private def home = chapter.copy(
    id = ChapterId(id(50)),
    destination = chapter.destination.copy(name = homeName),
    period = VisitPeriod(LocalDate.of(2026, 1, 1), 365),
    sourceFolder = "/photos/Home"
  )
  private def atCapacity =
    val photo = Spread.PhotosOnly(
      SpreadId(id(60)),
      template.id,
      NonEmptyList.one(SpreadSlot.Photo(PhotoSpreadSlotId(id(61)), box, bleed = Set.empty)),
      placements = Vector.empty[SpreadPlacement]
    )
    album.copy(chapters = Vector(chapter.copy(spreads = Vector.tabulate(63)(index => photo.copy(id = SpreadId(id(100 + index.toLong)))))))

  test("should create a multi-country chapter and its empty opening"):
    val refs = Refs()

    for
      manager <- buildManager(refs)
      result  <- manager.create(album.id, chapter.destination.name, countries, Period.Dates(chapter.period), chapter.sourceFolder)
      writes  <- refs.creations.get
    yield
      val opening = Spread.ChapterStart(
        SpreadId(id(11)),
        template.id,
        Vector(SpreadSlot.Photo(PhotoSpreadSlotId(id(12)), box, bleed = Set.empty)),
        SpreadSlot.Text(TextSpreadSlotId(id(13)), box),
        placements = Vector.empty[SpreadPlacement]
      )
      val expected = chapter.copy(id = ChapterId(id(10)), destination = chapter.destination.copy(id = DestinationId(id(14))), opening = opening)
      assertEquals(result, CreateResult.Created(expected.id))
      assertEquals(writes, Vector((album.id, expected, NonEmptyList.one(expected.id))))
      assertEquals(album.copy(chapters = Vector(expected)).countries, countries.toList.toVector)

  test(s"should resolve the full year for $homeName including leap days"):
    Vector((2024: AlbumYear, 366: VisitDays), (2026: AlbumYear, 365: VisitDays)).traverse_ { case (year, days) =>
      val refs    = Refs()
      val current = album.copy(year = year, chapters = Vector(home.copy(period = VisitPeriod(LocalDate.of(year, 1, 1), days))))

      for
        manager <- buildManager(refs, current = current)
        updated <- manager.update(album.id, home.id, homeName, countries, Period.AlbumYear, home.sourceFolder)
        updates <- refs.updates.get
      yield
        assertEquals(updated, UpdateResult.Updated)
        assertEquals(updates.map(_.period), Vector(VisitPeriod(LocalDate.of(year, 1, 1), days)))
    }

  test(s"should reject duplicate $homeName chapters, wrong full-year periods and reused folders before writing"):
    val scenarios = Vector(
      (album.copy(chapters = Vector(home)), homeName, Period.AlbumYear, "/photos/new": SourceFolder, ChapterConflict.HomeExists),
      (album, homeName, Period.Dates(chapter.period), "/photos/new": SourceFolder, ChapterConflict.HomePeriodMismatch),
      (album, homeName, Period.Dates(VisitPeriod(LocalDate.of(2026, 1, 1), 366)), "/photos/new": SourceFolder, ChapterConflict.HomePeriodMismatch),
      (
        album.copy(chapters = Vector(chapter)),
        chapter.destination.name,
        Period.Dates(chapter.period),
        chapter.sourceFolder,
        ChapterConflict.FolderAssigned
      )
    )
    scenarios.traverse_ { case (current, name, period, folder, expected) =>
      val refs   = Refs()
      val target = chapter.copy(id = ChapterId(id(70)), destination = chapter.destination.copy(name = name), sourceFolder = "/photos/target")

      for
        creator   <- buildManager(refs, current = current)
        editor    <- buildManager(refs, current = current.copy(chapters = current.chapters :+ target))
        created   <- creator.create(album.id, name, countries, period, folder)
        updated   <- editor.update(album.id, target.id, name, countries, period, folder)
        creations <- refs.creations.get
        updates   <- refs.updates.get
      yield
        assertEquals(created, CreateResult.Rejected(expected))
        assertEquals(updated, UpdateResult.Rejected(expected))
        assertEquals(creations, Vector.empty)
        assertEquals(updates, Vector.empty)
    }

  test(s"should preserve chapter type when editing $homeName or an ordinary chapter"):
    Vector((home, chapter.destination.name), (chapter, homeName)).traverse_ { case (current, requestedName) =>
      val refs = Refs()

      for
        manager <- buildManager(refs, current = album.copy(chapters = Vector(current)))
        result  <- manager.update(album.id, current.id, requestedName, countries, Period.AlbumYear, "/photos/changed")
        writes  <- refs.updates.get
      yield
        assertEquals(result, UpdateResult.Rejected(ChapterConflict.TypeChange))
        assertEquals(writes, Vector.empty)
    }

  test("should admit the last opening, reject further creation and allow metadata changes at capacity"):
    val refs             = Refs()
    val current          = atCapacity
    val updatedCountries = NonEmptyList.one(countries.head)
    val beforeLast       = current.copy(chapters = current.chapters.map(value => value.copy(spreads = value.spreads.drop(1))))

    for
      creator  <- buildManager(refs, current = beforeLast)
      full     <- buildManager(refs, current = current)
      admitted <- creator.create(album.id, "Kyoto", countries, Period.Dates(chapter.period), "/photos/new")
      rejected <- full.create(album.id, "Kyoto", countries, Period.Dates(chapter.period), "/photos/new")
      updated  <- full.update(album.id, chapter.id, "Kyoto", updatedCountries, Period.Dates(chapter.period), "/photos/new")
      writes   <- refs.updates.get
    yield
      assertEquals(beforeLast.spreads.length, 64)
      assertEquals(current.spreads.length, 65)
      assertEquals(admitted, CreateResult.Created(ChapterId(id(10))))
      assertEquals(rejected, CreateResult.Rejected(ChapterConflict.AtCapacity))
      assertEquals(updated, UpdateResult.Updated)
      val expectedWrites =
        Vector((
          album.id,
          chapter.id,
          "Kyoto": DestinationName,
          updatedCountries,
          chapter.period,
          "/photos/new": SourceFolder,
          NonEmptyList.one(chapter.id)
        ))
      assertEquals(writes, expectedWrites)

  test(s"should order chapters by dates and identity with $homeName last"):
    val refs    = Refs()
    val earlier = chapter.copy(id = ChapterId(id(70)), period = chapter.period.copy(days = 1), sourceFolder = "/photos/earlier")
    val tied    = earlier.copy(id = ChapterId(id(71)), sourceFolder = "/photos/tied")
    val later   = chapter.copy(id = ChapterId(id(72)), period = VisitPeriod(LocalDate.of(2026, 5, 2), 1), sourceFolder = "/photos/later")
    val current = album.copy(chapters = Vector(earlier, tied, chapter, later, home))

    for
      manager   <- buildManager(refs, current = current)
      created   <- manager.create(album.id, "Kyoto", countries, Period.Dates(chapter.period), "/photos/new")
      updated   <- manager.update(album.id, later.id, "Kyoto", countries, Period.Dates(earlier.period), later.sourceFolder)
      creations <- refs.creations.get
      updates   <- refs.updates.get
    yield
      assertEquals(created, CreateResult.Created(ChapterId(id(10))))
      assertEquals(updated, UpdateResult.Updated)
      assertEquals(creations.map(_.order), Vector(NonEmptyList.of(earlier.id, tied.id, ChapterId(id(10)), chapter.id, later.id, home.id)))
      assertEquals(updates.map(_.order), Vector(NonEmptyList.of(earlier.id, tied.id, later.id, chapter.id, home.id)))

  test("should decide concurrent creations from the latest saved composition"):
    val refs       = Refs()
    val beforeLast = atCapacity.copy(chapters = atCapacity.chapters.map(value => value.copy(spreads = value.spreads.drop(1))))
    val state      = Ref.unsafe[IO, Album](beforeLast)
    val read       = state.get.flatMap(value => IO.cede.as(AlbumRepository.GetResult.Found(value)))
    val save       = (value: Chapter) => state.update(valueBefore => valueBefore.copy(chapters = valueBefore.chapters :+ value))

    for
      manager <- buildManager(refs, maybeRead = read.some, save = save)
      results <- Vector.fill(2)(()).parTraverse(_ => manager.create(album.id, "Kyoto", countries, Period.Dates(chapter.period), "/photos/new"))
      saved   <- state.get
    yield
      assertEquals(results.collect { case CreateResult.Created(_) => () }.size, 1)
      assertEquals(results.collect { case CreateResult.Rejected(ChapterConflict.AtCapacity) => () }.size, 1)
      assertEquals(saved.spreads.length, 65)

  test("should report missing albums chapters templates and failed writes explicitly"):
    val refs    = Refs()
    val current = album.copy(chapters = Vector(chapter))
    val period  = Period.Dates(chapter.period)
    val reads   = Vector(
      (AlbumRepository.GetResult.NotFound, (CreateResult.AlbumNotFound, UpdateResult.AlbumNotFound)),
      (AlbumRepository.GetResult.Failed(AlbumRepository.Failure.DatabaseFailure), (CreateResult.Failed, UpdateResult.Failed))
    )
    val writes = Vector(
      (
        ChapterRepository.CreateResult.AlbumNotFound,
        ChapterRepository.UpdateResult.AlbumNotFound,
        (CreateResult.AlbumNotFound, UpdateResult.AlbumNotFound)
      ),
      (ChapterRepository.CreateResult.Failed, ChapterRepository.UpdateResult.Failed, (CreateResult.Failed, UpdateResult.Failed)),
      (
        ChapterRepository.CreateResult.Saved,
        ChapterRepository.UpdateResult.ChapterNotFound,
        (CreateResult.Created(ChapterId(id(10))), UpdateResult.ChapterNotFound)
      )
    )
    val unavailableReads = reads.traverse { case (read, expected) =>
      buildManager(refs, maybeRead = read.pure[IO].some).flatMap { manager =>
        (
          manager.create(album.id, "Kyoto", countries, period, "/photos/new"),
          manager.update(album.id, chapter.id, "Kyoto", countries, period, "/photos/new")
        ).tupled.map(result => assertEquals(result, expected))
      }
    }
    val unavailableWrites = writes.traverse { case (creation, update, expected) =>
      buildManager(refs, current = current, createWrite = creation, updateWrite = update).flatMap { manager =>
        (
          manager.create(album.id, "Kyoto", countries, period, "/photos/new"),
          manager.update(album.id, chapter.id, "Kyoto", countries, period, "/photos/new")
        ).tupled.map(result => assertEquals(result, expected))
      }
    }

    for
      _           <- unavailableReads
      _           <- unavailableWrites
      missing     <- buildManager(refs)
      absent      <- missing.update(album.id, chapter.id, "Kyoto", countries, period, "/photos/new")
      noTemplates <- buildManager(refs, templates = ListChapterStartTemplatesResult.Failed)
      failed      <- noTemplates.create(album.id, "Kyoto", countries, period, "/photos/new")
    yield
      assertEquals(absent, UpdateResult.ChapterNotFound)
      assertEquals(failed, CreateResult.Failed)

  final private case class Refs():
    val creations: Ref[IO, Vector[(album: AlbumId, chapter: Chapter, order: NonEmptyList[ChapterId])]] = Ref.unsafe(Vector.empty)

    val updates: Ref[
      IO,
      Vector[(
          album: AlbumId,
          chapter: ChapterId,
          name: DestinationName,
          countries: NonEmptyList[Country],
          period: VisitPeriod,
          folder: SourceFolder,
          order: NonEmptyList[ChapterId]
      )]
    ] = Ref.unsafe(Vector.empty)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildManager(
      refs: Refs,
      current: Album = album,
      maybeRead: Option[IO[AlbumRepository.GetResult]] = None,
      createWrite: ChapterRepository.CreateResult = ChapterRepository.CreateResult.Saved,
      updateWrite: ChapterRepository.UpdateResult = ChapterRepository.UpdateResult.Saved,
      templates: ListChapterStartTemplatesResult = ListChapterStartTemplatesResult.Listed(NonEmptyList.one(template)),
      save: Chapter => IO[Unit] = _ => IO.unit
  ) =
    given IdGenerator[IO] with
      private val next              = Ref.unsafe[IO, Long](10L)
      override def nextId: IO[UUID] = next.getAndUpdate(_ + 1L).map(id)
    val albums = new AlbumRepository[IO]:
      override def get(value: AlbumId): IO[AlbumRepository.GetResult] =
        maybeRead.getOrElse((AlbumRepository.GetResult.Found(current): AlbumRepository.GetResult).pure[IO])
      override def list(offset: Int, limit: Int): IO[AlbumRepository.ListResult] =
        IO.raiseError[AlbumRepository.ListResult](new AssertionError("Unexpected list"))
      override def create(value: Album, cover: Cover): IO[AlbumRepository.CreateResult] =
        IO.raiseError[AlbumRepository.CreateResult](new AssertionError("Unexpected album creation"))
      override def rename(value: AlbumId, title: AlbumTitle): IO[AlbumRepository.RenameResult] =
        IO.raiseError[AlbumRepository.RenameResult](new AssertionError("Unexpected album rename"))
      override def delete(value: AlbumId): IO[AlbumRepository.DeleteResult] =
        IO.raiseError[AlbumRepository.DeleteResult](new AssertionError("Unexpected deletion"))
    val repository = new ChapterRepository[IO]:
      override def create(parent: AlbumId, chapter: Chapter, order: NonEmptyList[ChapterId]): IO[ChapterRepository.CreateResult] =
        refs.creations.update(_ :+ (parent, chapter, order)) >> save(chapter).as(createWrite)
      override def update(
          parent: AlbumId,
          chapter: ChapterId,
          name: DestinationName,
          countries: NonEmptyList[Country],
          period: VisitPeriod,
          sourceFolder: SourceFolder,
          order: NonEmptyList[ChapterId]
      ): IO[ChapterRepository.UpdateResult] =
        refs.updates.update(_ :+ (parent, chapter, name, countries, period, sourceFolder, order)).as(updateWrite)
    val catalog = new SpreadTemplateManager[IO]:
      override def listAlbumStartTemplates: IO[SpreadTemplateManager.ListAlbumStartTemplatesResult] =
        IO.raiseError[SpreadTemplateManager.ListAlbumStartTemplatesResult](new AssertionError("Unexpected album templates"))
      override def listChapterStartTemplates: IO[ListChapterStartTemplatesResult] = templates.pure[IO]
    Mutex[IO].map: mutex =>
      given Mutex[IO] = mutex
      ChapterManager.make(albums, repository, catalog)
  // scalafix:on DisableSyntax.defaultArgs

  private def id(value: Long) = new UUID(0L, value)
