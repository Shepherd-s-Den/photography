package photography.usecases

import cats.data.NonEmptyList
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.github.iltotore.iron.*
import munit.CatsEffectSuite
import photography.capabilities.IdGenerator
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.{AlbumRepository, ChapterRepository}
import photography.ports.ChapterRepository.SaveResult
import photography.usecases.ChapterService.{CreateResult, UpdateResult}
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
      result <- service.create(album.id, "Kyoto", country, period, "/photos/custom/Kyoto")
      saved  <- refs.chapters.get
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
      assertEquals(saved, Vector(expected))

  test("should give a destination named Home the entire album year"):
    val refs    = Refs()
    val service = buildService(refs, current = album.copy(year = 2024))

    for
      _     <- service.create(album.id, "Home", country, VisitPeriod(LocalDate.of(2024, 5, 1), 1), "/photos/2024/selection/Home")
      saved <- refs.chapters.get
    yield assertEquals(saved.map(_.period), Vector(VisitPeriod(LocalDate.of(2024, 1, 1), 366)))

  test("should enforce spread capacity and unique Home and folders"):
    val chapter     = buildChapter(20)
    val fullChapter = chapter.copy(spreads = Vector.tabulate(63)(index => buildPhotoSpread(index.toLong + 100L)))
    val home        = chapter.copy(destination = chapter.destination.copy(name = "Home"), period = VisitPeriod(LocalDate.of(2026, 1, 1), 365))
    val cases       = Vector(
      (
        current = album.copy(chapters = Vector(fullChapter)),
        name = "Kyoto": DestinationName,
        folder = "/photos/new": SourceFolder,
        conflict = ChapterConflict.AtCapacity
      ),
      (
        current = album.copy(chapters = Vector(home)),
        name = "Home": DestinationName,
        folder = "/photos/new": SourceFolder,
        conflict = ChapterConflict.HomeExists
      ),
      (
        current = album.copy(chapters = Vector(chapter)),
        name = "Kyoto": DestinationName,
        folder = chapter.sourceFolder,
        conflict = ChapterConflict.FolderAssigned
      )
    )
    val refs              = Refs()
    val available         = album.copy(chapters = Vector(fullChapter.copy(spreads = fullChapter.spreads.drop(1))))
    val rejectedCreations = cases.traverse: scenario =>
      buildService(refs, current = scenario.current).create(album.id, scenario.name, country, chapter.period, scenario.folder)

    for
      accepted <- buildService(refs, current = available).create(album.id, "Kyoto", country, chapter.period, "/photos/new")
      rejected <- rejectedCreations
      saved    <- refs.chapters.get
    yield
      val extendedAlbum = available.copy(chapters = available.chapters ++ saved)
      assertEquals(accepted, CreateResult.Created(ChapterId(id(10))))
      assertEquals(rejected, cases.map(scenario => CreateResult.Rejected(scenario.conflict)))
      assertEquals(saved.size, 1)
      assertEquals(available.spreads.length, 64)
      assertEquals(extendedAlbum.spreads.length, 65)
      assertEquals(extendedAlbum.countries, Vector(country))

  test("should update chapter metadata without replacing its content"):
    val photoSpreads = Vector.tabulate(63)(index => buildPhotoSpread(index.toLong + 100L))
    val chapter      = buildChapter(20).copy(description = "Keep this", spreads = photoSpreads)
    val refs         = Refs()
    val service      = buildService(refs, current = album.copy(chapters = Vector(chapter)))
    val period       = VisitPeriod(LocalDate.of(2027, 1, 1), 3)
    val expected     = chapter.copy(destination = chapter.destination.copy(name = "Osaka"), period = period, sourceFolder = "/photos/override")

    for
      result <- service.update(album.id, chapter.id, "Osaka", country, period, "/photos/override")
      saved  <- refs.chapters.get
    yield
      assertEquals(result, UpdateResult.Updated)
      assertEquals(saved, Vector(expected))
      assertEquals(album.copy(chapters = saved).spreads.length, 65)

  test("should reject missing chapters and conflicting metadata changes"):
    val chapter = buildChapter(20)
    val home    =
      buildChapter(30).copy(destination = Destination(DestinationId(id(31)), "Home", country), period = VisitPeriod(LocalDate.of(2026, 1, 1), 365))
    val refs    = Refs()
    val service = buildService(refs, current = album.copy(chapters = Vector(chapter, home)))

    for
      missing   <- service.update(album.id, ChapterId(id(99)), "Kyoto", country, chapter.period, chapter.sourceFolder)
      duplicate <- service.update(album.id, chapter.id, "Home", country, chapter.period, chapter.sourceFolder)
      folder    <- service.update(album.id, chapter.id, "Kyoto", country, chapter.period, home.sourceFolder)
      kept      <- service.update(album.id, home.id, "Home", country, chapter.period, home.sourceFolder)
      saved     <- refs.chapters.get
    yield
      assertEquals(missing, UpdateResult.Rejected(ChapterConflict.ChapterNotFound))
      assertEquals(duplicate, UpdateResult.Rejected(ChapterConflict.HomeExists))
      assertEquals(folder, UpdateResult.Rejected(ChapterConflict.FolderAssigned))
      assertEquals(kept, UpdateResult.Updated)
      assertEquals(saved, Vector(home))

  test("should report unavailable albums, templates and writes explicitly"):
    val chapter = buildChapter(20)
    val refs    = Refs()
    val reads   = Vector(
      (response = AlbumRepository.GetResult.NotFound, expected = (CreateResult.AlbumNotFound, UpdateResult.AlbumNotFound)),
      (response = AlbumRepository.GetResult.Failed(AlbumRepository.Failure.DatabaseFailure), expected = (CreateResult.Failed, UpdateResult.Failed))
    )
    val writes = Vector(
      (
        response = SaveResult.Rejected(ChapterConflict.AtCapacity),
        expected = (CreateResult.Rejected(ChapterConflict.AtCapacity), UpdateResult.Rejected(ChapterConflict.AtCapacity))
      ),
      (response = SaveResult.AlbumNotFound, expected = (CreateResult.AlbumNotFound, UpdateResult.AlbumNotFound)),
      (response = SaveResult.Failed, expected = (CreateResult.Failed, UpdateResult.Failed))
    )
    val readResponses = reads.traverse { scenario =>
      val service = buildService(refs, read = scenario.response)
      (
        service.create(album.id, "Kyoto", country, chapter.period, chapter.sourceFolder),
        service.update(album.id, chapter.id, "Kyoto", country, chapter.period, chapter.sourceFolder)
      ).tupled
    }
    val writeResponses = writes.traverse { scenario =>
      val service = buildService(refs, current = album.copy(chapters = Vector(chapter)), write = scenario.response)
      (
        service.create(album.id, "Osaka", country, chapter.period, "/photos/new"),
        service.update(album.id, chapter.id, "Kyoto", country, chapter.period, chapter.sourceFolder)
      ).tupled
    }

    val unavailableTemplates = buildService(refs, templates = ListChapterStartTemplatesResult.Failed)

    for
      unavailable <- readResponses
      failed      <- writeResponses
      templates   <- unavailableTemplates.create(album.id, "Kyoto", country, chapter.period, chapter.sourceFolder)
    yield
      assertEquals(unavailable, reads.map(_.expected))
      assertEquals(failed, writes.map(_.expected))
      assertEquals(templates, CreateResult.Failed)

  private def country  = Country(CountryId(id(2)), "RO", "Romania", AbsoluteLocation(Path.of("/flags/ro.svg")))
  private def box      = SlotBox(0.0, 0.0, 100.0, 100.0)
  private def template = SpreadTemplate.ChapterStart(SpreadTemplateId(id(4)), "Default", Vector(box), box)
  private def album    = Album(
    AlbumId(id(1)),
    2026,
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

  final private case class Refs():
    val chapters: Ref[IO, Vector[Chapter]] = Ref.unsafe(Vector.empty)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildService(
      refs: Refs,
      current: Album = album,
      read: AlbumRepository.GetResult = AlbumRepository.GetResult.Found(album),
      write: SaveResult = SaveResult.Saved,
      templates: ListChapterStartTemplatesResult = ListChapterStartTemplatesResult.Listed(NonEmptyList.one(template))
  ) =
    given IdGenerator[IO] with
      private val next              = Ref.unsafe[IO, Long](10L)
      override def nextId: IO[UUID] = next.getAndUpdate(_ + 1L).map(id)
    val albums = new AlbumRepository[IO]:
      override def get(value: AlbumId): IO[AlbumRepository.GetResult] = (read match
        case AlbumRepository.GetResult.Found(_) => AlbumRepository.GetResult.Found(current)
        case result                             => result
      ).pure[IO]
      override def list(offset: Int, limit: Int): IO[AlbumRepository.ListResult] =
        IO.raiseError[AlbumRepository.ListResult](new AssertionError("Unexpected list"))
      override def create(value: Album, cover: Cover): IO[AlbumRepository.CreateResult] =
        IO.raiseError[AlbumRepository.CreateResult](new AssertionError("Unexpected album creation"))
      override def changeYear(value: AlbumId, year: AlbumYear): IO[AlbumRepository.ChangeYearResult] =
        IO.raiseError[AlbumRepository.ChangeYearResult](new AssertionError("Unexpected year change"))
      override def delete(value: AlbumId): IO[AlbumRepository.DeleteResult] =
        IO.raiseError[AlbumRepository.DeleteResult](new AssertionError("Unexpected deletion"))
    val repository = new ChapterRepository[IO]:
      override def create(parent: AlbumId, chapter: Chapter): IO[SaveResult] = refs.chapters.update(_ :+ chapter).as(write)
      override def update(parent: AlbumId, chapter: Chapter): IO[SaveResult] = refs.chapters.update(_ :+ chapter).as(write)
    val catalog = new SpreadTemplateService[IO]:
      override def listAlbumStartTemplates: IO[SpreadTemplateService.ListAlbumStartTemplatesResult] =
        IO.raiseError[SpreadTemplateService.ListAlbumStartTemplatesResult](new AssertionError("Unexpected album templates"))
      override def listChapterStartTemplates: IO[ListChapterStartTemplatesResult] = templates.pure[IO]
    ChapterService.make(albums, repository, catalog)
  // scalafix:on DisableSyntax.defaultArgs

  private def buildChapter(value: Long) =
    Chapter(
      ChapterId(id(value)),
      Destination(DestinationId(id(value + 1L)), "Kyoto", country),
      VisitPeriod(LocalDate.of(2026, 5, 1), 2),
      "",
      s"/photos/$value".refineUnsafe[SourceFolderConstraint],
      Spread.ChapterStart(
        SpreadId(id(value + 2L)),
        template.id,
        Vector(SpreadSlot.Photo(PhotoSpreadSlotId(id(value + 4L)), box, bleed = Set.empty)),
        SpreadSlot.Text(TextSpreadSlotId(id(value + 3L)), box),
        placements = Vector.empty[SpreadPlacement]
      ),
      spreads = Vector.empty[Spread.PhotosOnly]
    )

  private def buildPhotoSpread(value: Long) =
    val slot      = PhotoSpreadSlotId(new UUID(1L, value))
    val placement = SpreadPlacement(
      SpreadPlacementId(new UUID(2L, value)),
      slot,
      PhotoId(new UUID(3L, value)),
      PhotoTransform(Shift(0.0, 0.0), mirrored = false, zoom = 1.0),
      CaptionCorner.BottomLeft
    )
    Spread.PhotosOnly(
      SpreadId(id(value)),
      SpreadTemplateId(id(1001)),
      NonEmptyList.one(SpreadSlot.Photo(slot, box, bleed = Set.empty)),
      Vector(placement)
    )

  private def id(value: Long) = new UUID(0L, value)
