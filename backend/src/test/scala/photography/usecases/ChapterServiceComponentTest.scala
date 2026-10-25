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
import photography.usecases.ChapterService.{CreateResult, Period, UpdateResult}
import photography.usecases.SpreadTemplateService.ListChapterStartTemplatesResult

import java.nio.file.Path
import java.time.{Instant, LocalDate}
import java.util.UUID

final class ChapterServiceComponentTest extends CatsEffectSuite:
  test("should create a chapter and its empty opening"):
    val refs    = Refs()
    val service = buildService(refs)
    val period  = VisitPeriod(LocalDate.of(2026, 5, 1), 2)

    for
      result    <- service.create(album.id, "Kyoto", country, Period.Dates(period), "/photos/custom/Kyoto")
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
        Destination(DestinationId(id(14)), "Kyoto", country),
        period,
        "",
        "/photos/custom/Kyoto",
        expectedOpening,
        spreads = Vector.empty[Spread.PhotosOnly]
      )
      assertEquals(result, CreateResult.Created(expected.id))
      assertEquals(requested, Vector(album.id -> expected))
      assertEquals(album.copy(chapters = Vector(expected)).countries, Vector(country))

  test("should resolve the requested album-year period for creation and updates"):
    val refs       = Refs()
    val leapPeriod = VisitPeriod(LocalDate.of(2024, 1, 1), 366)
    val service    =
      buildService(refs, read = AlbumRepository.GetResult.Found(album.copy(year = 2024)), expectedYear = 2024, albumYearPeriod = leapPeriod)
    val chapter = ChapterId(id(20))

    for
      created   <- service.create(album.id, "Home", country, Period.AlbumYear, "/photos/Home")
      updated   <- service.update(album.id, chapter, "Home", country, Period.AlbumYear, "/photos/Home")
      creations <- refs.creations.get
      updates   <- refs.updates.get
    yield
      assertEquals(created, CreateResult.Created(ChapterId(id(10))))
      assertEquals(updated, UpdateResult.Updated)
      assertEquals(creations.map(_.chapter.period), Vector(leapPeriod))
      assertEquals(updates, Vector((album.id, chapter, "Home": DestinationName, country, leapPeriod, "/photos/Home": SourceFolder)))

  test("should send only editable metadata to the current-state writer"):
    val refs     = Refs()
    val service  = buildService(refs)
    val chapter  = ChapterId(id(20))
    val period   = VisitPeriod(LocalDate.of(2027, 1, 1), 3)
    val expected = (album.id, chapter, "Osaka": DestinationName, country, period, "/photos/override": SourceFolder)

    for
      result  <- service.update(album.id, chapter, "Osaka", country, Period.Dates(period), "/photos/override")
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
        service.create(album.id, "Kyoto", country, Period.Dates(period), "/photos/Kyoto"),
        service.update(album.id, ChapterId(id(20)), "Kyoto", country, Period.Dates(period), "/photos/Kyoto")
      ).tupled
    }
    val missing = buildService(refs, updateWrite = ChapterRepository.UpdateResult.ChapterNotFound)

    for
      results <- rejected
      absent  <- missing.update(album.id, ChapterId(id(99)), "Kyoto", country, Period.Dates(period), "/photos/Kyoto")
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
        service.create(album.id, "Kyoto", country, period, "/photos/Kyoto"),
        service.update(album.id, ChapterId(id(20)), "Kyoto", country, period, "/photos/Kyoto")
      ).tupled
    }
    val unavailableWrites = writes.traverse { scenario =>
      val service = buildService(refs, createWrite = scenario.create, updateWrite = scenario.update)
      (
        service.create(album.id, "Kyoto", country, period, "/photos/Kyoto"),
        service.update(album.id, ChapterId(id(20)), "Kyoto", country, period, "/photos/Kyoto")
      ).tupled
    }
    val unavailableTemplates = buildService(refs, templates = ListChapterStartTemplatesResult.Failed)

    for
      readResults  <- unavailableReads
      writeResults <- unavailableWrites
      templates    <- unavailableTemplates.create(album.id, "Kyoto", country, period, "/photos/Kyoto")
    yield
      assertEquals(readResults, reads.map(_.expected))
      assertEquals(writeResults, writes.map(_.expected))
      assertEquals(templates, CreateResult.Failed)

  private def country  = Country(CountryId(id(2)), "RO", "Romania", AbsoluteLocation(Path.of("/flags/ro.svg")))
  private def box      = SlotBox(0.0, 0.0, 100.0, 100.0)
  private def template = SpreadTemplate.ChapterStart(SpreadTemplateId(id(4)), "Default", Vector(box), box)
  private def album    = Album(
    AlbumId(id(1)),
    2026,
    "",
    Instant.EPOCH,
    Spread.AlbumStart(SpreadId(id(3)), SpreadTemplateId(id(1000)), Vector.empty, SpreadSlot.Text(TextSpreadSlotId(id(5)), box), Vector.empty),
    chapters = Vector.empty[Chapter]
  )

  final private case class Refs():
    val creations: Ref[IO, Vector[(album: AlbumId, chapter: Chapter)]] = Ref.unsafe(Vector.empty)

    val updates: Ref[IO, Vector[(AlbumId, ChapterId, DestinationName, Country, VisitPeriod, SourceFolder)]] = Ref.unsafe(Vector.empty)

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
      override def changeYear(value: AlbumId, year: AlbumYear): IO[AlbumRepository.ChangeYearResult] =
        IO.raiseError[AlbumRepository.ChangeYearResult](new AssertionError("Unexpected year change"))
      override def delete(value: AlbumId): IO[AlbumRepository.DeleteResult] =
        IO.raiseError[AlbumRepository.DeleteResult](new AssertionError("Unexpected deletion"))
    val repository = new ChapterRepository[IO]:
      override def create(parent: AlbumId, chapter: Chapter): IO[ChapterRepository.CreateResult] =
        refs.creations.update(_ :+ (parent -> chapter)).as(createWrite)
      override def update(
          parent: AlbumId,
          chapter: ChapterId,
          name: DestinationName,
          country: Country,
          period: VisitPeriod,
          sourceFolder: SourceFolder
      ): IO[ChapterRepository.UpdateResult] =
        refs.updates.update(_ :+ (parent, chapter, name, country, period, sourceFolder)).as(updateWrite)
    val catalog = new SpreadTemplateService[IO]:
      override def listAlbumStartTemplates: IO[SpreadTemplateService.ListAlbumStartTemplatesResult] =
        IO.raiseError[SpreadTemplateService.ListAlbumStartTemplatesResult](new AssertionError("Unexpected album templates"))
      override def listChapterStartTemplates: IO[ListChapterStartTemplatesResult] = templates.pure[IO]
    val policy = new ChapterPolicy:
      override def yearPeriod(year: AlbumYear): VisitPeriod =
        assertEquals(year, expectedYear)
        albumYearPeriod
      override def conflict(value: Album): Option[ChapterConflict] =
        fail("The current-state writer owns policy checks")
    ChapterService.make(albums, repository, catalog, policy)
  // scalafix:on DisableSyntax.defaultArgs

  private def id(value: Long) = new UUID(0L, value)
