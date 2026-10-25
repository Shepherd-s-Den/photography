package photography.usecases

import cats.data.NonEmptyList
import cats.effect.{IO, Ref}
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
  private def country                   = Country(CountryId(id(2)), "RO", "Romania", AbsoluteLocation(Path.of("/flags/ro.svg")))
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
      SpreadTemplateId(id(1000)),
      photoSlots = Vector.empty[SpreadSlot.Photo],
      SpreadSlot.Text(TextSpreadSlotId(id(5)), box),
      placements = Vector.empty[SpreadPlacement]
    ),
    chapters = Vector.empty[Chapter]
  )

  test("should create a chapter and its empty opening"):
    val refs    = Refs()
    val service = buildService(refs)
    val period  = VisitPeriod(LocalDate.of(2026, 5, 1), 2)

    for
      result    <- service.create(album.id, "Asian capitals", countries, Period.Dates(period), "/photos/custom/Kyoto")
      requested <- refs.creations.get
    yield
      val expectedOpening = Spread.ChapterStart(
        SpreadId(id(11)),
        template.id,
        Vector(SpreadSlot.Photo(PhotoSpreadSlotId(id(12)), box, bleed = Set.empty)),
        SpreadSlot.Text(TextSpreadSlotId(id(13)), box),
        placements = Vector.empty[SpreadPlacement]
      )
      val expected = Chapter(
        ChapterId(id(10)),
        Destination(DestinationId(id(14)), "Asian capitals", countries),
        period,
        "",
        "/photos/custom/Kyoto",
        expectedOpening,
        spreads = Vector.empty[Spread.PhotosOnly]
      )
      assertEquals(result, CreateResult.Created(expected.id))
      assertEquals(requested, Vector(album.id -> expected))
      assertEquals(album.copy(chapters = Vector(expected)).countries, countries.toList.toVector)

  test("should resolve the requested album-year period for creation and updates"):
    val refs       = Refs()
    val leapPeriod = VisitPeriod(LocalDate.of(2024, 1, 1), 366)
    val service    =
      buildService(refs, read = AlbumRepository.GetResult.Found(album.copy(year = 2024)), expectedYear = 2024, albumYearPeriod = leapPeriod)
    val chapter = ChapterId(id(20))

    for
      created   <- service.create(album.id, "Home", NonEmptyList.one(country), Period.AlbumYear, "/photos/Home")
      updated   <- service.update(album.id, chapter, "Home", NonEmptyList.one(country), Period.AlbumYear, "/photos/Home")
      creations <- refs.creations.get
      updates   <- refs.updates.get
    yield
      assertEquals(created, CreateResult.Created(ChapterId(id(10))))
      assertEquals(updated, UpdateResult.Updated)
      assertEquals(creations.map(_.chapter.period), Vector(leapPeriod))
      assertEquals(updates, Vector((album.id, chapter, "Home": DestinationName, NonEmptyList.one(country), leapPeriod, "/photos/Home": SourceFolder)))

  test("should send only editable metadata to the current-state writer"):
    val refs     = Refs()
    val service  = buildService(refs)
    val chapter  = ChapterId(id(20))
    val period   = VisitPeriod(LocalDate.of(2027, 1, 1), 3)
    val expected = (album.id, chapter, "Asian capitals": DestinationName, countries, period, "/photos/override": SourceFolder)

    for
      result  <- service.update(album.id, chapter, "Asian capitals", countries, Period.Dates(period), "/photos/override")
      updates <- refs.updates.get
    yield
      assertEquals(result, UpdateResult.Updated)
      assertEquals(updates, Vector(expected))

  test("should preserve business conflicts and missing chapter outcomes"):
    val refs      = Refs()
    val period    = VisitPeriod(LocalDate.of(2026, 5, 1), 2)
    val conflicts = Vector(ChapterConflict.AtCapacity, ChapterConflict.HomeExists, ChapterConflict.HomePeriodMismatch, ChapterConflict.FolderAssigned)
    val rejected  = conflicts.traverse { conflict =>
      val service = buildService(
        refs,
        createWrite = ChapterRepository.CreateResult.Rejected(conflict),
        updateWrite = ChapterRepository.UpdateResult.Rejected(conflict)
      )
      (
        service.create(album.id, "Kyoto", NonEmptyList.one(country), Period.Dates(period), "/photos/Kyoto"),
        service.update(album.id, ChapterId(id(20)), "Kyoto", NonEmptyList.one(country), Period.Dates(period), "/photos/Kyoto")
      ).tupled
    }
    val missing = buildService(refs, updateWrite = ChapterRepository.UpdateResult.ChapterNotFound)

    for
      results <- rejected
      absent  <- missing.update(album.id, ChapterId(id(99)), "Kyoto", NonEmptyList.one(country), Period.Dates(period), "/photos/Kyoto")
    yield
      val expected = conflicts.map(conflict => (CreateResult.Rejected(conflict), UpdateResult.Rejected(conflict)))
      assertEquals(results, expected)
      assertEquals(absent, UpdateResult.ChapterNotFound)

  test("should report unavailable albums templates and writes explicitly"):
    val refs   = Refs()
    val period = Period.Dates(VisitPeriod(LocalDate.of(2026, 5, 1), 2))
    val reads  = Vector(
      (response = AlbumRepository.GetResult.NotFound, expected = (CreateResult.AlbumNotFound, UpdateResult.AlbumNotFound)),
      (response = AlbumRepository.GetResult.Failed(AlbumRepository.Failure.DatabaseFailure), expected = (CreateResult.Failed, UpdateResult.Failed))
    )
    val writes = Vector(
      (
        create = ChapterRepository.CreateResult.AlbumNotFound,
        update = ChapterRepository.UpdateResult.AlbumNotFound,
        expected = (CreateResult.AlbumNotFound, UpdateResult.AlbumNotFound)
      ),
      (
        create = ChapterRepository.CreateResult.Failed,
        update = ChapterRepository.UpdateResult.Failed,
        expected = (CreateResult.Failed, UpdateResult.Failed)
      )
    )
    val unavailableReads = reads.traverse { scenario =>
      val service = buildService(refs, read = scenario.response)
      (
        service.create(album.id, "Kyoto", NonEmptyList.one(country), period, "/photos/Kyoto"),
        service.update(album.id, ChapterId(id(20)), "Kyoto", NonEmptyList.one(country), period, "/photos/Kyoto")
      ).tupled
    }
    val unavailableWrites = writes.traverse { scenario =>
      val service = buildService(refs, createWrite = scenario.create, updateWrite = scenario.update)
      (
        service.create(album.id, "Kyoto", NonEmptyList.one(country), period, "/photos/Kyoto"),
        service.update(album.id, ChapterId(id(20)), "Kyoto", NonEmptyList.one(country), period, "/photos/Kyoto")
      ).tupled
    }
    val unavailableTemplates = buildService(refs, templates = ListChapterStartTemplatesResult.Failed)

    for
      readResults  <- unavailableReads
      writeResults <- unavailableWrites
      templates    <- unavailableTemplates.create(album.id, "Kyoto", NonEmptyList.one(country), period, "/photos/Kyoto")
    yield
      assertEquals(readResults, reads.map(_.expected))
      assertEquals(writeResults, writes.map(_.expected))
      assertEquals(templates, CreateResult.Failed)

  test("should derive the entire album year including leap days"):
    val rules = ChapterManager.rules

    val periods = Vector(rules.yearPeriod(2024), rules.yearPeriod(2026))

    assertEquals(periods, Vector(VisitPeriod(LocalDate.of(2024, 1, 1), 366), VisitPeriod(LocalDate.of(2026, 1, 1), 365)))

  test(s"should enforce interior capacity and $homeName and folder invariants"):
    val opening = Spread.ChapterStart(
      SpreadId(id(30)),
      template.id,
      photoSlots = Vector.empty[SpreadSlot.Photo],
      SpreadSlot.Text(TextSpreadSlotId(id(31)), box),
      placements = Vector.empty[SpreadPlacement]
    )
    val period  = VisitPeriod(LocalDate.of(2026, 5, 1), 2)
    val chapter = Chapter(
      ChapterId(id(32)),
      Destination(DestinationId(id(33)), "Asian capitals", countries),
      period,
      "",
      "/photos/capitals",
      opening,
      spreads = Vector.empty[Spread.PhotosOnly]
    )
    val home = chapter.copy(
      id = ChapterId(id(40)),
      destination = chapter.destination.copy(name = homeName),
      period = VisitPeriod(LocalDate.of(2026, 1, 1), 365)
    )
    val second = chapter.copy(id = ChapterId(id(34)), sourceFolder = "/photos/second")
    val photo  = Spread.PhotosOnly(
      SpreadId(id(35)),
      SpreadTemplateId(id(36)),
      NonEmptyList.one(SpreadSlot.Photo(PhotoSpreadSlotId(id(37)), box, bleed = Set.empty)),
      placements = Vector.empty[SpreadPlacement]
    )
    val photos = Vector.tabulate(63): index =>
      photo.copy(id = SpreadId(id(100 + index.toLong)), photoSlots = photo.photoSlots.map(_.copy(id = PhotoSpreadSlotId(id(200 + index.toLong)))))
    val atCapacity = album.copy(chapters = Vector(chapter.copy(spreads = photos)))
    val scenarios  = Vector(
      (album, Option.empty[ChapterConflict]),
      (album.copy(chapters = Vector(home)), Option.empty[ChapterConflict]),
      (atCapacity, Option.empty[ChapterConflict]),
      (atCapacity.copy(chapters = atCapacity.chapters :+ second), ChapterConflict.AtCapacity.some),
      (album.copy(chapters = Vector(home, home.copy(id = second.id))), ChapterConflict.HomeExists.some),
      (album.copy(chapters = Vector(home.copy(period = period))), ChapterConflict.HomePeriodMismatch.some),
      (album.copy(chapters = Vector(home.copy(period = VisitPeriod(LocalDate.of(2026, 1, 1), 366)))), ChapterConflict.HomePeriodMismatch.some),
      (album.copy(chapters = Vector(chapter, second.copy(sourceFolder = chapter.sourceFolder))), ChapterConflict.FolderAssigned.some)
    )
    val rules = ChapterManager.rules

    val results  = scenarios.map { case (candidate, _) => rules.conflict(candidate) }
    val expected = scenarios.map { case (_, conflict) => conflict }

    assertEquals(results, expected)
    assertEquals(atCapacity.spreads.length, 65)

  test(s"should order chapter blocks by dates and identity with $homeName last"):
    val opening = Spread.ChapterStart(
      SpreadId(id(60)),
      template.id,
      photoSlots = Vector.empty[SpreadSlot.Photo],
      SpreadSlot.Text(TextSpreadSlotId(id(61)), box),
      placements = Vector.empty[SpreadPlacement]
    )
    val chapter = Chapter(
      ChapterId(id(62)),
      Destination(DestinationId(id(63)), "Asian capitals", countries),
      VisitPeriod(LocalDate.of(2026, 5, 1), 2),
      "",
      "/photos/capitals",
      opening,
      spreads = Vector.empty[Spread.PhotosOnly]
    )
    val home = chapter.copy(
      id = ChapterId(id(64)),
      destination = chapter.destination.copy(name = homeName),
      period = VisitPeriod(LocalDate.of(2026, 1, 1), 365),
      sourceFolder = "/photos/home"
    )
    val earlierEnd = chapter.copy(id = ChapterId(id(50)), period = chapter.period.copy(days = 1), sourceFolder = "/photos/earlier")
    val tied       = earlierEnd.copy(id = ChapterId(id(51)), sourceFolder = "/photos/tied")
    val laterStart = chapter.copy(id = ChapterId(id(52)), period = VisitPeriod(LocalDate.of(2026, 5, 2), 1), sourceFolder = "/photos/later")

    val ordered = ChapterManager.rules.ordered(Vector(home, laterStart, chapter, tied, earlierEnd))

    assertEquals(ordered.map(_.id), Vector(earlierEnd.id, tied.id, chapter.id, laterStart.id, home.id))

  final private case class Refs():
    val creations: Ref[IO, Vector[(album: AlbumId, chapter: Chapter)]] = Ref.unsafe(Vector.empty)

    val updates: Ref[IO, Vector[(AlbumId, ChapterId, DestinationName, NonEmptyList[Country], VisitPeriod, SourceFolder)]] = Ref.unsafe(Vector.empty)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildService(
      refs: Refs,
      read: AlbumRepository.GetResult = AlbumRepository.GetResult.Found(album),
      createWrite: ChapterRepository.CreateResult = ChapterRepository.CreateResult.Saved,
      updateWrite: ChapterRepository.UpdateResult = ChapterRepository.UpdateResult.Saved,
      templates: ListChapterStartTemplatesResult = ListChapterStartTemplatesResult.Listed(NonEmptyList.one(template)),
      expectedYear: AlbumYear = 2026,
      albumYearPeriod: VisitPeriod = VisitPeriod(LocalDate.of(2026, 1, 1), 365)
  ) =
    given IdGenerator[IO] with
      private val next              = Ref.unsafe[IO, Long](10L)
      override def nextId: IO[UUID] = next.getAndUpdate(_ + 1L).map(id)
    val albums = new AlbumRepository[IO]:
      override def get(value: AlbumId): IO[AlbumRepository.GetResult]            = read.pure[IO]
      override def list(offset: Int, limit: Int): IO[AlbumRepository.ListResult] =
        IO.raiseError[AlbumRepository.ListResult](new AssertionError("Unexpected list"))
      override def create(value: Album, cover: Cover): IO[AlbumRepository.CreateResult] =
        IO.raiseError[AlbumRepository.CreateResult](new AssertionError("Unexpected album creation"))
      override def rename(value: AlbumId, title: AlbumTitle): IO[AlbumRepository.RenameResult] =
        IO.raiseError[AlbumRepository.RenameResult](new AssertionError("Unexpected album rename"))
      override def delete(value: AlbumId): IO[AlbumRepository.DeleteResult] =
        IO.raiseError[AlbumRepository.DeleteResult](new AssertionError("Unexpected deletion"))
    val repository = new ChapterRepository[IO]:
      override def create(parent: AlbumId, chapter: Chapter): IO[ChapterRepository.CreateResult] =
        refs.creations.update(_ :+ (parent -> chapter)).as(createWrite)
      override def update(
          parent: AlbumId,
          chapter: ChapterId,
          name: DestinationName,
          countries: NonEmptyList[Country],
          period: VisitPeriod,
          sourceFolder: SourceFolder
      ): IO[ChapterRepository.UpdateResult] =
        refs.updates.update(_ :+ (parent, chapter, name, countries, period, sourceFolder)).as(updateWrite)
    val catalog = new SpreadTemplateManager[IO]:
      override def listAlbumStartTemplates: IO[SpreadTemplateManager.ListAlbumStartTemplatesResult] =
        IO.raiseError[SpreadTemplateManager.ListAlbumStartTemplatesResult](new AssertionError("Unexpected album templates"))
      override def listChapterStartTemplates: IO[ListChapterStartTemplatesResult] = templates.pure[IO]
    val policy = new ChapterManager.Rules:
      override def ordered(value: Vector[Chapter]): Vector[Chapter] =
        fail("The current-state writer owns ordering")
      override def yearPeriod(year: AlbumYear): VisitPeriod =
        assertEquals(year, expectedYear)
        albumYearPeriod
      override def conflict(value: Album): Option[ChapterConflict] =
        fail("The current-state writer owns policy checks")
    ChapterManager.make(albums, repository, catalog, policy)
  // scalafix:on DisableSyntax.defaultArgs

  private def id(value: Long) = new UUID(0L, value)
