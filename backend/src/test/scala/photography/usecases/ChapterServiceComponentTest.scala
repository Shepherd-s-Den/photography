package photography.usecases

import cats.data.NonEmptyList
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import io.github.iltotore.iron.*
import munit.CatsEffectSuite
import photography.capabilities.IdGenerator
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.ChapterRepository
import photography.ports.ChapterRepository.{Decision, SaveResult as WriteResult}
import photography.usecases.ChapterService.{CreateResult, Details, UpdateResult}
import photography.usecases.SpreadTemplateService.ListChapterStartTemplatesResult

import java.nio.file.Path
import java.time.{Instant, LocalDate}
import java.util.UUID

final class ChapterServiceComponentTest extends CatsEffectSuite:
  private val albumId      = AlbumId(new UUID(0L, 1L))
  private val country      = Country(CountryId(new UUID(0L, 2L)), "RO", "Romania", AbsoluteLocation(Path.of("/flags/ro.svg")))
  private val box          = SlotBox(1.0, 2.0, 20.0, 40.0)
  private val textBox      = box.copy(x = 51.0)
  private val template     = SpreadTemplate.ChapterStart(SpreadTemplateId(new UUID(0L, 4L)), "Chapter default", Vector(box), textBox)
  private val albumOpening = Spread.AlbumStart(
    SpreadId(new UUID(0L, 3L)),
    SpreadTemplateId(new UUID(0L, 5L)),
    Vector(SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(0L, 30L)), box, Set.empty)),
    SpreadSlot.Text(TextSpreadSlotId(new UUID(0L, 31L)), textBox),
    Vector.empty
  )
  private val album                    = Album(albumId, 2026, "Kept synopsis", Instant.EPOCH, albumOpening, Vector.empty, maybeHome = None)
  private val period                   = VisitPeriod(LocalDate.of(2025, 12, 28), 8)
  private val vacation                 = Details.Vacation("Kyoto", country, period)
  private val home                     = Details.Home(country)
  private val folder: RelativeLocation = "2026/selection/Kyoto"
  private val destination              = Destination(DestinationId(new UUID(0L, 7L)), "Kyoto", country)
  private val chapterOpening           = Spread.ChapterStart(
    SpreadId(new UUID(0L, 8L)),
    template.id,
    Vector(SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(0L, 80L)), box, Set(SpreadEdge.Top))),
    SpreadSlot.Text(TextSpreadSlotId(new UUID(0L, 81L)), textBox),
    Vector(SpreadPlacement(
      SpreadPlacementId(new UUID(0L, 82L)),
      PhotoSpreadSlotId(new UUID(0L, 80L)),
      PhotoId(new UUID(0L, 83L)),
      PhotoTransform(Shift(0.0, 0.0), mirrored = false, zoom = 1.0),
      CaptionCorner.BottomLeft
    ))
  )
  private val chapter =
    Chapter.Vacation(ChapterId(new UUID(0L, 6L)), destination, period, "Kept description", folder.some, chapterOpening, Vector.empty)
  private val homeOpening = buildOpening(9L).copy(placements =
    Vector(SpreadPlacement(
      SpreadPlacementId(new UUID(9L, 9L)),
      PhotoSpreadSlotId(new UUID(7L, 9L)),
      PhotoId(new UUID(10L, 9L)),
      PhotoTransform(Shift(0.0, 0.0), mirrored = false, zoom = 1.0),
      CaptionCorner.BottomRight
    ))
  )
  private val homePhotoSpread = Spread.PhotosOnly(
    SpreadId(new UUID(3L, 9L)),
    SpreadTemplateId(new UUID(0L, 40L)),
    NonEmptyList.one(SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(4L, 9L)), box, Set(SpreadEdge.Bottom))),
    Vector.empty
  )
  private val homeChapter =
    Chapter.Home(ChapterId(new UUID(0L, 9L)), country, "Home description", maybeSourceFolder = None, homeOpening, Vector(homePhotoSpread))

  test("should create a complete empty opening and place a late-added vacation before Home"):
    val refs    = Refs()
    val service = buildService(album, refs = refs)

    for
      createdHome     <- service.create(albumId, home, maybeFolder = None)
      createdVacation <- service.create(albumId, vacation, folder.some)
      saved           <- refs.commits.get
    yield
      val finalAlbum = saved.lastOption
      val createdIds = Vector(createdVacation, createdHome).collect { case CreateResult.Created(id) => id }
      val openings   = finalAlbum.toList.toVector.flatMap(_.chapters.map(_.opening))
      assertEquals(finalAlbum.map(_.chapters.map(_.id)), createdIds.some)
      assertEquals(openings.map(_.photoSlots.map(_.box)), Vector.fill(2)(Vector(box)))
      assertEquals(openings.map(_.textSlot.box), Vector.fill(2)(textBox))
      assertEquals(openings.map(_.template), Vector.fill(2)(template.id))
      assertEquals(openings.map(_.placements), Vector.fill(2)(Vector.empty[SpreadPlacement]))
      assertEquals(finalAlbum.map(_.countries), Vector(country).some)

  test("should accept the 64-to-65 transition and reject another creation without writing"):
    val refs    = Refs()
    val service = buildService(withSpreads(64), refs = refs)

    for
      last     <- service.create(albumId, vacation, maybeFolder = None)
      rejected <- service.create(albumId, vacation, maybeFolder = None)
      saved    <- refs.commits.get
    yield
      assertEquals(Vector(last).collect { case CreateResult.Created(id) => id }.size, 1)
      assertEquals(rejected, CreateResult.Rejected(ChapterConflict.AtCapacity))
      assertEquals(saved.map(_.spreads.length), Vector(65))

  test("should decide concurrent creations against current state so only one consumes the final spread"):
    val refs    = Refs()
    val service = buildService(withSpreads(64), refs = refs, concurrentCalls = 8)

    for
      results <- Vector.fill(8)(service.create(albumId, vacation, maybeFolder = None)).parSequence
      saved   <- refs.commits.get
    yield
      assertEquals(results.collect { case CreateResult.Created(id) => id }.size, 1)
      assertEquals(results.collect { case CreateResult.Rejected(reason) => reason }, Vector.fill(7)(ChapterConflict.AtCapacity))
      assertEquals(saved.map(_.spreads.length), Vector(65))

  test("should permit a 21st chapter while spread capacity remains"):
    val vacations = Vector.tabulate(20)(index => buildVacation(index.toLong + 100L))
    val refs      = Refs()
    val service   = buildService(album.copy(vacations = vacations), refs = refs)

    for
      result <- service.create(albumId, vacation, maybeFolder = None)
      saved  <- refs.commits.get
    yield
      assertEquals(Vector(result).collect { case CreateResult.Created(id) => id }.size, 1)
      assertEquals(saved.map(_.chapters.size), Vector(21))
      assertEquals(saved.map(_.spreads.length), Vector(22))

  test("should update metadata at capacity while preserving identity narrative and populated composition"):
    val refs            = Refs()
    val original        = withSpreads(65)
    val changed         = Details.Vacation("South Morocco", country, VisitPeriod(LocalDate.of(2027, 1, 1), 1))
    val expectedChapter = original.vacations.headOption.map(_.copy(
      destination = destination.copy(name = "South Morocco"),
      period = changed.period,
      maybeSourceFolder = None
    ))
    val service = buildService(original, refs = refs)

    for
      result <- service.updateVacation(albumId, chapter.id, changed, maybeFolder = None)
      saved  <- refs.commits.get
    yield
      assertEquals(result, UpdateResult.Updated)
      assertEquals(saved.flatMap(_.vacations), expectedChapter.toList.toVector)
      assertEquals(saved.map(_.spreads), Vector(original.spreads))
      assertEquals(saved.map(_.synopsis), Vector(original.synopsis))

  test("should reject duplicate Home and assigned folders without writing"):
    val existingHome = homeChapter.copy(maybeSourceFolder = folder.some)
    val initial      = album.copy(vacations = Vector(chapter.copy(maybeSourceFolder = None)), maybeHome = existingHome.some)
    val refs         = Refs()
    val service      = buildService(initial, refs = refs)

    for
      duplicateHome   <- service.create(albumId, home, maybeFolder = None)
      duplicateFolder <- service.create(albumId, vacation, folder.some)
      updateFolder    <- service.updateVacation(albumId, chapter.id, vacation, folder.some)
      saved           <- refs.commits.get
    yield
      assertEquals(duplicateHome, CreateResult.Rejected(ChapterConflict.HomeExists))
      assertEquals(duplicateFolder, CreateResult.Rejected(ChapterConflict.FolderAssigned))
      assertEquals(updateFolder, UpdateResult.Rejected(ChapterConflict.FolderAssigned))
      assertEquals(saved, Vector.empty)

  test("should reposition whole vacation blocks by first day last day and canonical identity with Home last"):
    val photoSpread = Spread.PhotosOnly(
      SpreadId(new UUID(3L, 13L)),
      SpreadTemplateId(new UUID(0L, 40L)),
      NonEmptyList.one(SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(4L, 13L)), box, Set.empty)),
      Vector.empty
    )
    val first    = buildVacation(11L).copy(maybeSourceFolder = folder.some)
    val second   = buildVacation(12L)
    val longer   = buildVacation(10L).copy(period = period.copy(days = 9))
    val later    = buildVacation(13L).copy(period = VisitPeriod(LocalDate.of(2026, 7, 1), 4), spreads = Vector(photoSpread))
    val initial  = album.copy(vacations = Vector(first, second, longer, later), maybeHome = homeChapter.some)
    val refs     = Refs()
    val service  = buildService(initial, refs = refs)
    val earliest = vacation.copy(period = VisitPeriod(LocalDate.of(2025, 1, 1), 2))

    for
      unchanged <- service.updateVacation(albumId, first.id, vacation, folder.some)
      moved     <- service.updateVacation(albumId, later.id, earliest, maybeFolder = None)
      saved     <- refs.commits.get
    yield
      val expectedOrders = Vector(
        Vector(first.id, second.id, longer.id, later.id, homeChapter.id),
        Vector(later.id, first.id, second.id, longer.id, homeChapter.id)
      )
      val expectedComposition = initial.chapters.map(chapter => chapter.id -> (chapter.opening, chapter.spreads)).toMap
      val actualComposition   = saved.map(_.chapters.map(chapter => chapter.id -> (chapter.opening, chapter.spreads)).toMap)
      assertEquals(Vector(unchanged, moved), Vector.fill(2)(UpdateResult.Updated))
      assertEquals(saved.map(_.chapters.map(_.id)), expectedOrders)
      assertEquals(saved.map(_.spreads.length), Vector.fill(2)(initial.spreads.length))
      assertEquals(actualComposition, Vector.fill(2)(expectedComposition))

  test("should update Home at capacity without treating its own folder as assigned or changing composition"):
    val beforeHome = withSpreads(63)
    val original   = beforeHome.copy(
      vacations = beforeHome.vacations.map(_.copy(maybeSourceFolder = None)),
      maybeHome = homeChapter.copy(maybeSourceFolder = folder.some).some
    )
    val changedCountry = country.copy(id = CountryId(new UUID(0L, 90L)), code = "NO", name = "Norway")
    val refs           = Refs()
    val service        = buildService(original, refs = refs)

    for
      result <- service.updateHome(albumId, homeChapter.id, Details.Home(changedCountry), folder.some)
      saved  <- refs.commits.get
    yield
      val expectedHome = homeChapter.copy(country = changedCountry, maybeSourceFolder = folder.some)
      assertEquals(result, UpdateResult.Updated)
      assertEquals(saved.map(_.maybeHome), Vector(expectedHome.some))
      assertEquals(saved.map(_.spreads), Vector(original.spreads))
      assertEquals(saved.map(_.countries), Vector(Vector(country, changedCountry)))

  test("should report missing and foreign chapter targets without changing the album"):
    val scenarios = Vector(album, album.copy(vacations = Vector(chapter), maybeHome = homeChapter.some))

    scenarios.traverse_ { initial =>
      val refs    = Refs()
      val service = buildService(initial, refs = refs)

      for
        vacationResult <- service.updateVacation(albumId, homeChapter.id, vacation, maybeFolder = None)
        homeResult     <- service.updateHome(albumId, chapter.id, home, maybeFolder = None)
        saved          <- refs.commits.get
      yield
        assertEquals(Vector(vacationResult, homeResult), Vector.fill(2)(UpdateResult.Rejected(ChapterConflict.ChapterNotFound)))
        assertEquals(saved, Vector.empty)
    }

  test("should propagate template and persistence failures and missing albums explicitly"):
    val scenarios = Vector(
      (ListChapterStartTemplatesResult.Failed, None, CreateResult.Failed, UpdateResult.Updated),
      (ListChapterStartTemplatesResult.Listed(NonEmptyList.one(template)), WriteResult.Failed.some, CreateResult.Failed, UpdateResult.Failed),
      (
        ListChapterStartTemplatesResult.Listed(NonEmptyList.one(template)),
        WriteResult.AlbumNotFound.some,
        CreateResult.AlbumNotFound,
        UpdateResult.AlbumNotFound
      )
    )

    scenarios.traverse_ { case (templates, maybeWriteResult, expectedCreate, expectedUpdate) =>
      val service = buildService(album.copy(vacations = Vector(chapter)), templates = templates, maybeWriteResult = maybeWriteResult)

      for
        created <- service.create(albumId, home, maybeFolder = None)
        updated <- service.updateVacation(albumId, chapter.id, vacation, folder.some)
      yield
        assertEquals(created, expectedCreate)
        assertEquals(updated, expectedUpdate)
    }

  final private case class Refs():
    val commits: Ref[IO, Vector[Album]] = Ref.unsafe(Vector.empty)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildService(
      initial: Album,
      refs: Refs = Refs(),
      concurrentCalls: Int = 0,
      templates: ListChapterStartTemplatesResult = ListChapterStartTemplatesResult.Listed(NonEmptyList.one(template)),
      maybeWriteResult: Option[WriteResult] = None
  ) =
    val stored            = Ref.unsafe[IO, Album](initial)
    val identifiersIssued = Ref.unsafe[IO, Long](100L)
    val entered           = Ref.unsafe[IO, Int](0)
    val ready             = Deferred.unsafe[IO, Unit]
    val identifiers       = new IdGenerator[IO]:
      override def nextId: IO[UUID] = identifiersIssued.getAndUpdate(_ + 1L).map(new UUID(2L, _))
    val spreads = new SpreadTemplateService[IO]:
      override def listAlbumStartTemplates: IO[SpreadTemplateService.ListAlbumStartTemplatesResult] =
        SpreadTemplateService.ListAlbumStartTemplatesResult.Failed.pure[IO]
      override def listChapterStartTemplates: IO[ListChapterStartTemplatesResult] = templates.pure[IO]
    val repository = new ChapterRepository[IO]:
      override def modifyAtomically(id: AlbumId, decide: Album => Decision): IO[WriteResult] =
        val barrier = entered.updateAndGet(_ + 1).flatMap: count =>
          if concurrentCalls <= 0 || count > concurrentCalls then IO.unit
          else if count === concurrentCalls then ready.complete(()).void else ready.get
        barrier >> stored.modify { current =>
          maybeWriteResult match
            case Some(result) => current -> (result, Option.empty[Album])
            case None         => decide(current) match
                case Decision.Reject(reason) => current -> (WriteResult.Rejected(reason), Option.empty[Album])
                case Decision.Save(changed)  => changed -> (WriteResult.Saved, changed.some)
        }.flatMap { case (result, maybeSaved) => maybeSaved.traverse_(saved => refs.commits.update(_ :+ saved)).as(result) }
    ChapterService.make(repository, spreads, identifiers)
  // scalafix:on DisableSyntax.defaultArgs

  private def withSpreads(count: Int) =
    val photos = Vector.tabulate(count - 2): index =>
      Spread.PhotosOnly(
        SpreadId(new UUID(3L, index.toLong)),
        SpreadTemplateId(new UUID(0L, 40L)),
        NonEmptyList.one(SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(4L, index.toLong)), box, Set.empty)),
        Vector.empty
      )
    album.copy(vacations = Vector(chapter.copy(spreads = photos)))

  private def buildVacation(index: Long) =
    chapter.copy(
      id = ChapterId(new UUID(0L, index)),
      destination = destination.copy(id = DestinationId(new UUID(5L, index))),
      maybeSourceFolder = None,
      opening = buildOpening(index)
    )

  private def buildOpening(index: Long) =
    Spread.ChapterStart(
      SpreadId(new UUID(6L, index)),
      template.id,
      Vector(SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(7L, index)), box, Set.empty)),
      SpreadSlot.Text(TextSpreadSlotId(new UUID(8L, index)), textBox),
      Vector.empty
    )
