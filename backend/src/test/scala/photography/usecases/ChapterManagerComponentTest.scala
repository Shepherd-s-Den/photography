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
import photography.usecases.ChapterPhotoImport.{Request, Scheduling, Status}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.nio.file.Path
import java.time.{Instant, LocalDate}
import java.util.UUID

final class ChapterManagerComponentTest extends CatsEffectSuite:
  private given Logger[IO]              = Slf4jLogger.getLogger[IO]
  private val homeName: DestinationName = "Home"
  private val countries                 = NonEmptyList.of(
    Country(CountryId(buildUuid(index = 21)), code = "JP", name = "Japan", AbsoluteLocation(Path.of("/flags/jp.svg"))),
    Country(CountryId(buildUuid(index = 22)), code = "KR", name = "South Korea", AbsoluteLocation(Path.of("/flags/kr.svg"))),
    Country(CountryId(buildUuid(index = 23)), code = "TW", name = "Taiwan", AbsoluteLocation(Path.of("/flags/tw.svg"))),
    Country(CountryId(buildUuid(index = 24)), code = "SG", name = "Singapore", AbsoluteLocation(Path.of("/flags/sg.svg")))
  )
  private val box      = SlotBox(x = 0.0, y = 0.0, width = 100.0, height = 100.0)
  private val template =
    SpreadTemplate.ChapterStart(SpreadTemplateId(buildUuid(index = 4)), name = "Default", photoSlots = Vector(box), textSlot = box)
  private val album =
    val textSlot        = SpreadSlot.Text(TextSpreadSlotId(buildUuid(index = 5)), box)
    val emptyPhotoSlots = Vector.empty[SpreadSlot.Photo]
    val emptyPlacements = Vector.empty[SpreadPlacement]
    val opening         = Spread.AlbumStart(SpreadId(buildUuid(index = 3)), template.id, emptyPhotoSlots, textSlot, emptyPlacements)
    val emptyChapters   = Vector.empty[Chapter]
    Album(AlbumId(buildUuid(index = 1)), year = 2026, title = "A year abroad", synopsis = "", Instant.EPOCH, opening, emptyChapters)
  private val chapter =
    val destination     = Destination(DestinationId(buildUuid(index = 30)), name = "Asian capitals", countries)
    val period          = VisitPeriod(firstDay = LocalDate.of(2026, 5, 1), days = 2)
    val textSlot        = SpreadSlot.Text(TextSpreadSlotId(buildUuid(index = 41)), box)
    val emptyPhotoSlots = Vector.empty[SpreadSlot.Photo]
    val emptyPlacements = Vector.empty[SpreadPlacement]
    val opening         = Spread.ChapterStart(SpreadId(buildUuid(index = 40)), template.id, emptyPhotoSlots, textSlot, emptyPlacements)
    val emptySpreads    = Vector.empty[Spread.PhotosOnly]
    Chapter(ChapterId(buildUuid(index = 20)), destination, period, description = "", sourceFolder = "/photos/capitals", opening, emptySpreads)
  private val home =
    val destination = chapter.destination.copy(name = homeName)
    val fullYear    = VisitPeriod(firstDay = LocalDate.of(2026, 1, 1), days = 365)
    chapter.copy(id = ChapterId(buildUuid(index = 50)), destination = destination, period = fullYear, sourceFolder = "/photos/Home")
  private val atCapacity =
    val photoSlot       = SpreadSlot.Photo(PhotoSpreadSlotId(buildUuid(index = 61)), box, bleed = Set.empty)
    val emptyPlacements = Vector.empty[SpreadPlacement]
    val photo           = Spread.PhotosOnly(SpreadId(buildUuid(index = 60)), template.id, NonEmptyList.one(photoSlot), emptyPlacements)
    val spreads         = Vector.tabulate(63)(index => photo.copy(id = SpreadId(buildUuid(index = 100 + index.toLong))))
    album.copy(chapters = Vector(chapter.copy(spreads = spreads)))

  test("should create a multi-country chapter and its empty opening"):
    val refs = Refs()

    for
      manager  <- buildManager(refs, save = _ => refs.preparations.get.map(actual => assertEquals(actual, Vector.empty)))
      result   <- manager.create(album.id, chapter.destination.name, countries.map(_.id), Period.Dates(chapter.period), chapter.sourceFolder)
      writes   <- refs.creations.get
      prepared <- refs.preparations.get
    yield
      val photoSlot       = SpreadSlot.Photo(PhotoSpreadSlotId(buildUuid(index = 12)), box, bleed = Set.empty)
      val textSlot        = SpreadSlot.Text(TextSpreadSlotId(buildUuid(index = 13)), box)
      val emptyPlacements = Vector.empty[SpreadPlacement]
      val opening         = Spread.ChapterStart(SpreadId(buildUuid(index = 11)), template.id, Vector(photoSlot), textSlot, emptyPlacements)
      val chapterId       = ChapterId(buildUuid(index = 10))
      val destinationId   = DestinationId(buildUuid(index = 14))
      val countryIds      = countries.map(_.id)
      val destinationName = chapter.destination.name
      val sourceFolder    = chapter.sourceFolder
      val expected        =
        ChapterRepository.NewChapter(chapterId, destinationId, destinationName, countryIds, chapter.period, description = "", sourceFolder, opening)
      assertEquals(result, CreateResult.Created(expected.id, Scheduling.Queued))
      assertEquals(writes, Vector((album.id, expected, NonEmptyList.one(expected.id))))
      assertEquals(prepared, Vector((expected.id, sourceFolder)))

  test(s"should resolve the full year for $homeName including leap days"):
    Vector((2024: AlbumYear, 366: VisitDays), (2026: AlbumYear, 365: VisitDays)).traverse_ { case (year, days) =>
      val refs    = Refs()
      val current = album.copy(year = year, chapters = Vector(home.copy(period = VisitPeriod(firstDay = LocalDate.of(year, 1, 1), days = days))))

      for
        manager  <- buildManager(refs, current = current)
        updated  <- manager.update(album.id, home.id, homeName, countries.map(_.id), Period.AlbumYear, home.sourceFolder)
        updates  <- refs.updates.get
        prepared <- refs.preparations.get
      yield
        assertEquals(updated, UpdateResult.Updated(Scheduling.NotRequested))
        assertEquals(updates.map(_.period), Vector(VisitPeriod(firstDay = LocalDate.of(year, 1, 1), days = days)))
        assertEquals(prepared, Vector.empty)
    }

  test(s"should reject duplicate $homeName chapters, wrong full-year periods and reused folders before writing"):
    val scenarios = Vector(
      (album.copy(chapters = Vector(home)), homeName, Period.AlbumYear, "/photos/new": PhotoSource, ChapterConflict.HomeExists),
      (album, homeName, Period.Dates(chapter.period), "/photos/new": PhotoSource, ChapterConflict.HomePeriodMismatch),
      (
        album,
        homeName,
        Period.Dates(VisitPeriod(firstDay = LocalDate.of(2026, 1, 1), days = 366)),
        "/photos/new": PhotoSource,
        ChapterConflict.HomePeriodMismatch
      ),
      (
        album.copy(chapters = Vector(chapter)),
        chapter.destination.name,
        Period.Dates(chapter.period),
        chapter.sourceFolder,
        ChapterConflict.FolderAssigned
      )
    )
    scenarios.traverse_ { case (current, name, period, folder, expected) =>
      val refs        = Refs()
      val destination = chapter.destination.copy(name = name)
      val target      = chapter.copy(id = ChapterId(buildUuid(index = 70)), destination = destination, sourceFolder = "/photos/target")

      for
        creator   <- buildManager(refs, current = current)
        editor    <- buildManager(refs, current = current.copy(chapters = current.chapters :+ target))
        created   <- creator.create(album.id, name = name, countries.map(_.id), period, sourceFolder = folder)
        updated   <- editor.update(album.id, target.id, name = name, countries.map(_.id), period, sourceFolder = folder)
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
        result  <- manager.update(album.id, current.id, name = requestedName, countries.map(_.id), Period.AlbumYear, sourceFolder = "/photos/changed")
        writes  <- refs.updates.get
      yield
        assertEquals(result, UpdateResult.Rejected(ChapterConflict.TypeChange))
        assertEquals(writes, Vector.empty)
    }

  test("should admit the last opening, reject further creation and allow metadata changes at capacity"):
    val refs             = Refs()
    val current          = atCapacity
    val period           = Period.Dates(chapter.period)
    val updatedCountries = NonEmptyList.one(countries.head)
    val beforeLast       = current.copy(chapters = current.chapters.map(value => value.copy(spreads = value.spreads.drop(1))))

    for
      creator  <- buildManager(refs, current = beforeLast)
      full     <- buildManager(refs, current = current)
      admitted <- creator.create(album.id, name = "Kyoto", countries.map(_.id), period, sourceFolder = "/photos/new")
      rejected <- full.create(album.id, name = "Kyoto", countries.map(_.id), period, sourceFolder = "/photos/new")
      updated  <- full.update(album.id, chapter.id, name = "Kyoto", updatedCountries.map(_.id), period, sourceFolder = "/photos/new")
      writes   <- refs.updates.get
    yield
      assertEquals(beforeLast.spreads.length, 64)
      assertEquals(current.spreads.length, 65)
      assertEquals(admitted, CreateResult.Created(ChapterId(buildUuid(index = 10)), Scheduling.Queued))
      assertEquals(rejected, CreateResult.Rejected(ChapterConflict.AtCapacity))
      assertEquals(updated, UpdateResult.Updated(Scheduling.Queued))
      val destinationName: DestinationName = "Kyoto"
      val sourceFolder: PhotoSource        = "/photos/new"
      val chapterOrder                     = NonEmptyList.one(chapter.id)
      val expectedWrites = Vector((album.id, chapter.id, destinationName, updatedCountries.map(_.id), chapter.period, sourceFolder, chapterOrder))
      assertEquals(writes, expectedWrites)

  test(s"should order chapters by dates and identity with $homeName last"):
    val refs        = Refs()
    val earlier     = chapter.copy(id = ChapterId(buildUuid(index = 70)), period = chapter.period.copy(days = 1), sourceFolder = "/photos/earlier")
    val tied        = earlier.copy(id = ChapterId(buildUuid(index = 71)), sourceFolder = "/photos/tied")
    val laterPeriod = VisitPeriod(firstDay = LocalDate.of(2026, 5, 2), days = 1)
    val later       = chapter.copy(id = ChapterId(buildUuid(index = 72)), period = laterPeriod, sourceFolder = "/photos/later")
    val current     = album.copy(chapters = Vector(earlier, tied, chapter, later, home))

    for
      manager   <- buildManager(refs, current = current)
      created   <- manager.create(album.id, name = "Kyoto", countries.map(_.id), Period.Dates(chapter.period), sourceFolder = "/photos/new")
      updated   <- manager.update(album.id, later.id, name = "Kyoto", countries.map(_.id), Period.Dates(earlier.period), later.sourceFolder)
      creations <- refs.creations.get
      updates   <- refs.updates.get
    yield
      val expectedCreations = Vector(NonEmptyList.of(earlier.id, tied.id, ChapterId(buildUuid(index = 10)), chapter.id, later.id, home.id))
      assertEquals(created, CreateResult.Created(ChapterId(buildUuid(index = 10)), Scheduling.Queued))
      assertEquals(updated, UpdateResult.Updated(Scheduling.NotRequested))
      assertEquals(creations.map(_.order), expectedCreations)
      assertEquals(updates.map(_.order), Vector(NonEmptyList.of(earlier.id, tied.id, later.id, chapter.id, home.id)))

  test("should decide concurrent creations from the latest saved composition"):
    val refs       = Refs()
    val beforeLast = atCapacity.copy(chapters = atCapacity.chapters.map(value => value.copy(spreads = value.spreads.drop(1))))
    val state      = Ref.unsafe[IO, Album](beforeLast)
    val read       = state.get.flatMap(value => IO.cede.as(AlbumRepository.GetResult.Found(value)))
    val save       = (value: ChapterRepository.NewChapter) =>
      val destination  = Destination(value.destinationId, value.name, countries)
      val emptySpreads = Vector.empty[Spread.PhotosOnly]
      val saved        = Chapter(value.id, destination, value.period, value.description, value.sourceFolder, value.opening, emptySpreads)
      state.update(valueBefore => valueBefore.copy(chapters = valueBefore.chapters :+ saved))

    for
      manager <- buildManager(refs, maybeRead = read.some, save = save)
      createChapter = manager.create(album.id, name = "Kyoto", countries.map(_.id), Period.Dates(chapter.period), sourceFolder = "/photos/new")
      results <- Vector.fill(2)(()).parTraverse(_ => createChapter)
      saved   <- state.get
    yield
      assertEquals(results.collect { case CreateResult.Created(_, _) => () }.size, 1)
      assertEquals(results.collect { case CreateResult.Rejected(ChapterConflict.AtCapacity) => () }.size, 1)
      assertEquals(saved.spreads.length, 65)

  test("should report missing metadata and rejected writes without preparing or launching imports"):
    val refs    = Refs()
    val current = album.copy(chapters = Vector(chapter))
    val period  = Period.Dates(chapter.period)
    val reads   = Vector(
      (AlbumRepository.GetResult.NotFound, (CreateResult.AlbumNotFound, UpdateResult.AlbumNotFound, ChapterManager.RefreshResult.AlbumNotFound)),
      (
        AlbumRepository.GetResult.Failed(AlbumRepository.Failure.DatabaseFailure),
        (CreateResult.Failed, UpdateResult.Failed, ChapterManager.RefreshResult.Failed)
      )
    )
    val writes = Vector(
      (
        ChapterRepository.CreateResult.AlbumNotFound,
        ChapterRepository.UpdateResult.AlbumNotFound,
        (CreateResult.AlbumNotFound, UpdateResult.AlbumNotFound)
      ),
      (ChapterRepository.CreateResult.Failed, ChapterRepository.UpdateResult.Failed, (CreateResult.Failed, UpdateResult.Failed)),
      (
        ChapterRepository.CreateResult.AlbumNotFound,
        ChapterRepository.UpdateResult.ChapterNotFound,
        (CreateResult.AlbumNotFound, UpdateResult.ChapterNotFound)
      ),
      (
        ChapterRepository.CreateResult.UnknownCountries,
        ChapterRepository.UpdateResult.UnknownCountries,
        (CreateResult.Rejected(ChapterConflict.UnknownCountries), UpdateResult.Rejected(ChapterConflict.UnknownCountries))
      )
    )
    val unavailableReads = reads.traverse_ { case (read, expected) =>
      val observations = Refs()

      for
        manager   <- buildManager(observations, maybeRead = read.pure[IO].some)
        created   <- manager.create(album.id, name = "Kyoto", countries.map(_.id), period, sourceFolder = "/photos/new")
        updated   <- manager.update(album.id, chapter.id, name = "Kyoto", countries.map(_.id), period, sourceFolder = "/photos/new")
        refreshed <- manager.refresh(album.id, chapter.id)
        prepared  <- observations.preparations.get
        launched  <- observations.launches.get
      yield
        assertEquals((created, updated, refreshed), expected)
        assertEquals(prepared, Vector.empty)
        assertEquals(launched, Vector.empty)
    }
    val unavailableWrites = writes.traverse_ { case (creation, update, expected) =>
      val observations = Refs()

      for
        manager  <- buildManager(observations, current = current, createWrite = creation, updateWrite = update)
        created  <- manager.create(album.id, name = "Kyoto", countries.map(_.id), period, sourceFolder = "/photos/new")
        updated  <- manager.update(album.id, chapter.id, name = "Kyoto", countries.map(_.id), period, sourceFolder = "/photos/new")
        prepared <- observations.preparations.get
        launched <- observations.launches.get
      yield
        assertEquals((created, updated), expected)
        assertEquals(prepared, Vector.empty)
        assertEquals(launched, Vector.empty)
    }

    for
      missing     <- buildManager(refs)
      noTemplates <- buildManager(refs, templates = ListChapterStartTemplatesResult.Failed)
      _           <- unavailableReads
      _           <- unavailableWrites
      absent      <- missing.update(album.id, chapter.id, name = "Kyoto", countries.map(_.id), period, sourceFolder = "/photos/new")
      refreshed   <- missing.refresh(album.id, chapter.id)
      failed      <- noTemplates.create(album.id, name = "Kyoto", countries.map(_.id), period, sourceFolder = "/photos/new")
      prepared    <- refs.preparations.get
      launched    <- refs.launches.get
    yield
      assertEquals(absent, UpdateResult.ChapterNotFound)
      assertEquals(failed, CreateResult.Failed)
      assertEquals(refreshed, ChapterManager.RefreshResult.ChapterNotFound)
      assertEquals(prepared, Vector.empty)
      assertEquals(launched, Vector.empty)

  test("should preserve saved creation when launch fails and resolve Refresh from the saved source"):
    val error      = new RuntimeException("scheduling failed")
    val countryIds = countries.map(_.id)
    val name       = chapter.destination.name
    val period     = Period.Dates(chapter.period)
    val failures   = Vector(
      (IO.unit, Scheduling.Failed.pure[IO]),
      (IO.unit, IO.raiseError[Scheduling](error)),
      (IO.raiseError[Unit](error), Scheduling.Queued.pure[IO])
    )

    failures.traverse_ { case (preparation, scheduling) =>
      val refs = Refs()

      for
        manager   <- buildManager(refs, current = album.copy(chapters = Vector(chapter)), preparation = preparation, scheduling = scheduling)
        created   <- manager.create(album.id, name = "Kyoto", countries.map(_.id), Period.Dates(chapter.period), sourceFolder = "/photos/new")
        refreshed <- manager.refresh(album.id, chapter.id)
        updated   <- manager.update(album.id, chapter.id, name, countryIds, period, sourceFolder = "/photos/changed")
        prepared  <- refs.preparations.get
      yield
        assertEquals(created, CreateResult.Created(ChapterId(buildUuid(index = 10)), Scheduling.Failed))
        assertEquals(refreshed, ChapterManager.RefreshResult.Scheduled(Scheduling.Failed))
        assertEquals(updated, UpdateResult.Updated(Scheduling.Failed))
        val expectedSources = Vector(
          (ChapterId(buildUuid(index = 10)), "/photos/new": PhotoSource),
          (chapter.id, chapter.sourceFolder),
          (chapter.id, "/photos/changed": PhotoSource)
        )
        assertEquals(prepared, expectedSources)
    }

  final private case class Refs():
    val preparations: Ref[IO, Vector[(ChapterId, PhotoSource)]]                                                             = Ref.unsafe(Vector.empty)
    val launches: Ref[IO, Vector[Request]]                                                                                  = Ref.unsafe(Vector.empty)
    val creations: Ref[IO, Vector[(album: AlbumId, chapter: ChapterRepository.NewChapter, order: NonEmptyList[ChapterId])]] = Ref.unsafe(Vector.empty)

    val updates: Ref[
      IO,
      Vector[(
          album: AlbumId,
          chapter: ChapterId,
          name: DestinationName,
          countries: NonEmptyList[CountryId],
          period: VisitPeriod,
          folder: PhotoSource,
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
      preparation: IO[Unit] = IO.unit,
      scheduling: IO[Scheduling] = Scheduling.Queued.pure[IO],
      save: ChapterRepository.NewChapter => IO[Unit] = _ => IO.unit
  ) =
    given IdGenerator[IO] with
      private val next                  = Ref.unsafe[IO, Long](10L)
      override def generateId: IO[UUID] = next.getAndUpdate(_ + 1L).map(buildUuid)
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
      override def create(
          parent: AlbumId,
          chapter: ChapterRepository.NewChapter,
          order: NonEmptyList[ChapterId]
      ): IO[ChapterRepository.CreateResult] =
        refs.creations.update(_ :+ (parent, chapter, order)) >> save(chapter).as(createWrite)
      override def update(
          parent: AlbumId,
          chapter: ChapterId,
          name: DestinationName,
          countries: NonEmptyList[CountryId],
          period: VisitPeriod,
          sourceFolder: PhotoSource,
          order: NonEmptyList[ChapterId]
      ): IO[ChapterRepository.UpdateResult] =
        refs.updates.update(_ :+ (parent, chapter, name, countries, period, sourceFolder, order)).as(updateWrite)
    val catalog = new SpreadTemplateManager[IO]:
      override def listAlbumStartTemplates: IO[SpreadTemplateManager.ListAlbumStartTemplatesResult] =
        IO.raiseError[SpreadTemplateManager.ListAlbumStartTemplatesResult](new AssertionError("Unexpected album templates"))
      override def listChapterStartTemplates: IO[ListChapterStartTemplatesResult] = templates.pure[IO]
    Mutex[IO].map: mutex =>
      given Mutex[IO] = mutex
      val imports     = new ChapterPhotoImport[IO]:
        override def prepare(id: ChapterId, source: PhotoSource): IO[Request] =
          refs.preparations.update(_ :+ (id, source)) >> preparation.as(Request(id, source, generation = BigInt(0)))
        override def launch(request: Request): IO[Scheduling] = mutex.lock.surround(refs.launches.update(_ :+ request) >> scheduling)
        override def status(id: ChapterId): IO[Status]        = Status.Idle.pure[IO]
      ChapterManager.make(albums, repository, catalog, imports)
  // scalafix:on DisableSyntax.defaultArgs

  private def buildUuid(index: Long) = new UUID(0L, index)
