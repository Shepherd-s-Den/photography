package photography.usecases

import cats.data.NonEmptyList
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import io.github.iltotore.iron.*
import munit.CatsEffectSuite
import photography.capabilities.IdGenerator
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.WorkspaceRepository
import photography.ports.WorkspaceRepository.*
import photography.usecases.WorkspaceService.*

import java.nio.file.Path
import java.time.{Instant, LocalDate}
import java.util.UUID

final class WorkspaceServiceComponentTest extends CatsEffectSuite:
  private val albumId         = AlbumId(new UUID(0L, 1L))
  private val country         = Country(CountryId(new UUID(0L, 2L)), "RO", "Romania", AbsoluteLocation(Path.of("/flags/ro.svg")))
  private val year: AlbumYear = 2026
  private val root            = AbsoluteLocation(Path.of("/photos"))
  private val box             = SlotBox(1.0, 2.0, 20.0, 40.0)
  private val opening         = Spread.AlbumStart(
    SpreadId(new UUID(0L, 3L)),
    albumId,
    SpreadTemplateId(new UUID(0L, 4L)),
    Vector(
      SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(0L, 31L)), SpreadId(new UUID(0L, 3L)), box, Set.empty),
      SpreadSlot.Text(TextSpreadSlotId(new UUID(0L, 32L)), SpreadId(new UUID(0L, 3L)), box.copy(x = 51.0))
    ),
    Vector.empty
  )
  private val album        = Album(albumId, "Old title", "Kept synopsis", Instant.EPOCH, NonEmptyList.one(opening: Spread))
  private val yearless     = AlbumWorkspace(album, maybeYear = None, Vector.empty, Vector(country))
  private val assigned     = yearless.copy(maybeYear = year.some, album = album.copy(title = AlbumYear.title(year)))
  private val chapterBoxes = Vector(box, box.copy(x = 22.0), box.copy(x = 43.0), box.copy(x = 64.0), box.copy(x = 86.0, width = 13.0, height = 90.0))
  private val template     = SpreadTemplate(
    SpreadTemplateId(new UUID(0L, 5L)),
    "Chapter default",
    SpreadTemplateKind.ChapterStart,
    NonEmptyList.of(
      SpreadTemplateSlot.Photo(box),
      SpreadTemplateSlot.Photo(box.copy(x = 22.0)),
      SpreadTemplateSlot.Photo(box.copy(x = 43.0)),
      SpreadTemplateSlot.Photo(box.copy(x = 64.0)),
      SpreadTemplateSlot.Text(box.copy(x = 86.0, width = 13.0, height = 90.0))
    )
  )
  private val vacation: ChapterDetails.Vacation = ChapterDetails.Vacation(
    "Kyoto",
    country.id,
    VisitPeriod.fromDates(LocalDate.parse("2025-12-28"), LocalDate.parse("2026-01-04")).fold(fail(_), identity)
  )
  private val home   = ChapterDetails.Home(country.id)
  private val folder = RelativeLocation.from("2026/selection/Kyoto").fold(fail(_), identity)

  test("should read workspace metadata and configured root without changing persisted composition"):
    val service = buildService(assigned)

    service.get(albumId).map { result =>
      assertEquals(result, GetResult.Found(WorkspaceView(assigned, root)))
    }

  test("should assign and revise an unused year without changing identity synopsis or composition"):
    val service = buildService(yearless)

    for
      saved   <- service.assignYear(albumId, year)
      revised <- service.assignYear(albumId, 2027)
      loaded  <- service.get(albumId)
    yield
      val expected = yearless.copy(maybeYear = (2027: AlbumYear).some, album = album.copy(title = AlbumYear.title(2027)))
      assertEquals(saved, SaveYearResult.Saved(year, AlbumYear.title(year)))
      assertEquals(revised, SaveYearResult.Saved(2027, AlbumYear.title(2027)))
      assertEquals(loaded, GetResult.Found(WorkspaceView(expected, root)))

  test("should preserve a chapter-bearing year and permit saving that same year"):
    val chapter   = Chapter(ChapterId(new UUID(0L, 6L)), albumId, home, "", maybeSourceFolder = None)
    val workspace = withChapters(Vector(chapter))
    val service   = buildService(workspace)

    for
      rejected  <- service.assignYear(albumId, 2027)
      unchanged <- service.assignYear(albumId, year)
      loaded    <- service.get(albumId)
    yield
      assertEquals(rejected, SaveYearResult.Invalid("An album with chapters cannot change year"))
      assertEquals(unchanged, SaveYearResult.Saved(year, AlbumYear.title(year)))
      assertEquals(loaded, GetResult.Found(WorkspaceView(workspace, root)))

  test("should assign the first year to a yearless album that already contains chapters without losing composition"):
    val workspace = withSpreads(4).copy(maybeYear = None, album = withSpreads(4).album.copy(title = "Existing yearless album"))
    val service   = buildService(workspace)

    for
      saved  <- service.assignYear(albumId, year)
      loaded <- service.get(albumId)
    yield
      val expected = workspace.copy(maybeYear = year.some, album = workspace.album.copy(title = AlbumYear.title(year)))
      assertEquals(saved, SaveYearResult.Saved(year, AlbumYear.title(year)))
      assertEquals(loaded, GetResult.Found(WorkspaceView(expected, root)))

  test("should create complete empty chapter openings and place late vacations before Home"):
    val refs    = Refs()
    val service = buildService(assigned, refs = refs)

    for
      createdHome     <- service.createChapter(albumId, home, maybeSourceFolder = None)
      createdVacation <- service.createChapter(albumId, vacation, folder.some)
      loaded          <- service.get(albumId)
      commands        <- refs.commands.get
    yield
      assertEquals(Vector(createdHome, createdVacation).collect { case SaveChapterResult.Saved(id) => id }.size, 2)
      val details = loaded match
        case GetResult.Found(view) => view.workspace.chapters.map(_.details)
        case _                     => fail("Expected persisted workspace")
      assertEquals(details, Vector(vacation, home))
      val openings = commands.collect { case Mutation.CreateChapter(_, start, _) => start }
      val boxes    = openings.map(_.slots.map {
        case slot: SpreadSlot.Photo => slot.box
        case slot: SpreadSlot.Text  => slot.box
      })
      assertEquals(boxes, Vector.fill(2)(chapterBoxes))
      assertEquals(openings.map(_.placements), Vector.fill(2)(Vector.empty[SpreadPlacement]))
      assertEquals(openings.map(_.template), Vector.fill(2)(template.id))
      assertEquals(openings.flatMap(_.slots.collect { case slot: SpreadSlot.Photo => slot.bleed }), Vector.fill(8)(Set.empty[SpreadEdge]))
      val slotIds = openings.flatMap(_.slots.map {
        case slot: SpreadSlot.Photo => slot.id.value
        case slot: SpreadSlot.Text  => slot.id.value
      })
      assertEquals(slotIds.distinct.size, 10)

  test("should accept the 64-to-65 opening transition and reject another creation without writing"):
    val refs    = Refs()
    val service = buildService(withSpreads(64), refs = refs)

    for
      last     <- service.createChapter(albumId, vacation, maybeSourceFolder = None)
      rejected <- service.createChapter(albumId, vacation, maybeSourceFolder = None)
      commands <- refs.commands.get
    yield
      assertEquals(Vector(last).collect { case SaveChapterResult.Saved(id) => id }.size, 1)
      assertEquals(rejected, SaveChapterResult.Invalid("Album capacity is 65 interior spreads"))
      assertEquals(commands.size, 1)

  test("should revalidate concurrent chapter creations so only one consumes the final spread"):
    val refs    = Refs()
    val service = buildService(withSpreads(64), refs = refs, concurrentReads = 8)

    for
      results <- Vector.fill(8)(service.createChapter(albumId, vacation, maybeSourceFolder = None)).parSequence
      loaded  <- service.get(albumId)
      stale   <- refs.staleCommits.get
    yield
      assertEquals(results.collect { case SaveChapterResult.Saved(id) => id }.size, 1)
      assertEquals(results.collect { case SaveChapterResult.Invalid(message) => message }, Vector.fill(7)("Album capacity is 65 interior spreads"))
      val count = loaded match
        case GetResult.Found(view) => view.workspace.album.spreads.length
        case _                     => fail("Expected workspace")
      assertEquals(count, 65)
      assertEquals(stale, 7)

  test("should allow a 21st chapter while spread capacity remains"):
    val chapters  = Vector.tabulate(20)(index => Chapter(ChapterId(new UUID(1L, index.toLong)), albumId, vacation, "", maybeSourceFolder = None))
    val workspace = withChapters(chapters)
    val service   = buildService(workspace)

    for
      saved  <- service.createChapter(albumId, vacation, maybeSourceFolder = None)
      loaded <- service.get(albumId)
    yield
      assertEquals(Vector(saved).collect { case SaveChapterResult.Saved(id) => id }.size, 1)
      val count = loaded match
        case GetResult.Found(view) => view.workspace.chapters.size
        case _                     => fail("Expected workspace")
      assertEquals(count, 21)

  test("should update metadata at capacity while preserving type identity description and every spread"):
    val chapterId = ChapterId(new UUID(0L, 6L))
    val chapter   = Chapter(chapterId, albumId, vacation, "Existing description", folder.some)
    val workspace = withSpreads(65).copy(chapters = Vector(chapter))
    val service   = buildService(workspace)
    val renamed   = vacation.copy(name = "South Morocco")

    for
      saved       <- service.updateChapter(albumId, chapterId, renamed, maybeSourceFolder = None)
      loaded      <- service.get(albumId)
      invalidKind <- service.updateChapter(albumId, chapterId, home, maybeSourceFolder = None)
    yield
      val expected = workspace.copy(chapters = Vector(chapter.copy(details = renamed, maybeSourceFolder = None)))
      assertEquals(saved, SaveChapterResult.Saved(chapterId))
      assertEquals(loaded, GetResult.Found(WorkspaceView(expected, root)))
      assertEquals(invalidKind, SaveChapterResult.Invalid("Chapter type cannot change"))

  test("should reject yearless creation unknown countries duplicate Home and duplicate folders without mutation"):
    val existingHome = Chapter(ChapterId(new UUID(0L, 6L)), albumId, home, "", folder.some)
    val scenarios    = Vector(
      (yearless, vacation, None, "Assign an album year before adding chapters"),
      (assigned, ChapterDetails.Home(CountryId(new UUID(9L, 9L))), None, "Choose a country from the catalog"),
      (withChapters(Vector(existingHome)), home, None, "An album can have only one Home chapter"),
      (withChapters(Vector(existingHome)), vacation, folder.some, "Photo folder is already assigned to another chapter")
    )

    scenarios.traverse_ { case (workspace, details, maybeFolder, message) =>
      val refs    = Refs()
      val service = buildService(workspace, refs = refs)

      for
        result   <- service.createChapter(albumId, details, maybeFolder)
        commands <- refs.commands.get
      yield
        assertEquals(result, SaveChapterResult.Invalid(message))
        assertEquals(commands, Vector.empty)
    }

  test("should report missing albums and missing or foreign chapter targets explicitly"):
    val service        = buildService(assigned)
    val missingAlbum   = AlbumId(new UUID(99L, 99L))
    val missingChapter = ChapterId(new UUID(99L, 99L))

    for
      read       <- service.get(missingAlbum)
      yearResult <- service.assignYear(missingAlbum, year)
      create     <- service.createChapter(missingAlbum, home, maybeSourceFolder = None)
      update     <- service.updateChapter(albumId, missingChapter, home, maybeSourceFolder = None)
    yield
      assertEquals(read, GetResult.NotFound)
      assertEquals(yearResult, SaveYearResult.NotFound)
      assertEquals(create, SaveChapterResult.AlbumNotFound)
      assertEquals(update, SaveChapterResult.ChapterNotFound)

  test("should reposition edited vacation blocks by first day last day and canonical chapter ID with Home last"):
    val first  = Chapter(ChapterId(new UUID(0L, 11L)), albumId, vacation, "", maybeSourceFolder = None)
    val second = first.copy(id = ChapterId(new UUID(0L, 12L)))
    val longer = first.copy(
      id = ChapterId(new UUID(0L, 10L)),
      details = vacation.copy(
        period = VisitPeriod.fromDates(LocalDate.parse("2025-12-28"), LocalDate.parse("2026-01-05")).fold(fail(_), identity)
      )
    )
    val later = first.copy(
      id = ChapterId(new UUID(0L, 13L)),
      details = vacation.copy(
        period = VisitPeriod.fromDates(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-04")).fold(fail(_), identity)
      )
    )
    val homeChapter = first.copy(id = ChapterId(new UUID(0L, 14L)), details = home)
    val workspace   = withChapters(Vector(later, second, longer, first, homeChapter))
    val refs        = Refs()
    val service     = buildService(workspace, refs = refs)
    val earliest    = vacation.copy(period =
      VisitPeriod.fromDates(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-02")).fold(fail(_), identity)
    )
    val expectedOrders = Vector(
      Vector(first.id, second.id, longer.id, later.id, homeChapter.id),
      Vector(later.id, first.id, second.id, longer.id, homeChapter.id)
    )

    for
      unchanged <- service.updateChapter(albumId, first.id, vacation, maybeSourceFolder = None)
      moved     <- service.updateChapter(albumId, later.id, earliest, maybeSourceFolder = None)
      commands  <- refs.commands.get
    yield
      val orders = commands.collect { case Mutation.UpdateChapter(_, order) => order }
      assertEquals(unchanged, SaveChapterResult.Saved(first.id))
      assertEquals(moved, SaveChapterResult.Saved(later.id))
      assertEquals(orders, expectedOrders)

  test("should let Home retain its kind country and own folder during metadata updates"):
    val chapter   = Chapter(ChapterId(new UUID(0L, 6L)), albumId, home, "Kept", folder.some)
    val workspace = withChapters(Vector(chapter))
    val service   = buildService(workspace)

    for
      result <- service.updateChapter(albumId, chapter.id, home, folder.some)
      loaded <- service.get(albumId)
    yield
      assertEquals(result, SaveChapterResult.Saved(chapter.id))
      assertEquals(loaded, GetResult.Found(WorkspaceView(workspace, root)))

  test("should expose incomplete or failed workspace reads without returning partial content or writing"):
    Vector(Failure.InvalidWorkspace, Failure.DatabaseFailure).traverse_ { failure =>
      val refs    = Refs()
      val service = buildService(assigned, refs = refs, maybeReadFailure = failure.some)

      for
        read      <- service.get(albumId)
        savedYear <- service.assignYear(albumId, year)
        created   <- service.createChapter(albumId, vacation, maybeSourceFolder = None)
        updated   <- service.updateChapter(albumId, ChapterId(new UUID(0L, 6L)), home, maybeSourceFolder = None)
        commands  <- refs.commands.get
      yield
        assertEquals(read, GetResult.Failed)
        assertEquals(savedYear, SaveYearResult.Failed)
        assertEquals(created, SaveChapterResult.Failed)
        assertEquals(updated, SaveChapterResult.Failed)
        assertEquals(commands, Vector.empty)
    }

  test("should leave workspace unchanged when opening initialization cannot load its default template"):
    val refs    = Refs()
    val service = buildService(assigned, refs = refs, templateResult = TemplateResult.Failed(Failure.InvalidWorkspace))

    for
      result   <- service.createChapter(albumId, vacation, maybeSourceFolder = None)
      loaded   <- service.get(albumId)
      commands <- refs.commands.get
    yield
      assertEquals(result, SaveChapterResult.Failed)
      assertEquals(loaded, GetResult.Found(WorkspaceView(assigned, root)))
      assertEquals(commands, Vector.empty)

  test("should report atomic write rejection disappearance and infrastructure failure without changing state"):
    val outcomes         = Vector(CommitResult.Rejected("Already in use"), CommitResult.NotFound, CommitResult.Failed(Failure.DatabaseFailure))
    val expectedYears    = Vector(SaveYearResult.Invalid("Already in use"), SaveYearResult.NotFound, SaveYearResult.Failed)
    val expectedChapters = Vector(SaveChapterResult.Invalid("Already in use"), SaveChapterResult.AlbumNotFound, SaveChapterResult.Failed)

    outcomes.zip(expectedYears.zip(expectedChapters)).traverse_ { case (outcome, (expectedYear, expectedChapter)) =>
      val service = buildService(withSpreads(2), maybeCommitResult = outcome.some)

      for
        savedYear <- service.assignYear(albumId, year)
        created   <- service.createChapter(albumId, vacation, maybeSourceFolder = None)
        updated   <- service.updateChapter(albumId, ChapterId(new UUID(0L, 6L)), vacation, maybeSourceFolder = None)
        loaded    <- service.get(albumId)
      yield
        assertEquals(savedYear, expectedYear)
        assertEquals(created, expectedChapter)
        assertEquals(updated, expectedChapter)
        assertEquals(loaded, GetResult.Found(WorkspaceView(withSpreads(2), root)))
    }

  test("should retry year and metadata saves against a new revision after a stale write"):
    val chapter         = Chapter(ChapterId(new UUID(0L, 6L)), albumId, vacation, "", maybeSourceFolder = None)
    val service         = buildService(withChapters(Vector(chapter)), staleWrites = 2)
    val metadataService = buildService(withChapters(Vector(chapter)), staleWrites = 2)

    for
      yearSaved    <- service.assignYear(albumId, year)
      chapterSaved <- metadataService.updateChapter(albumId, chapter.id, vacation, maybeSourceFolder = None)
    yield
      assertEquals(yearSaved, SaveYearResult.Saved(year, AlbumYear.title(year)))
      assertEquals(chapterSaved, SaveChapterResult.Saved(chapter.id))

  final private case class Refs():
    val commands: Ref[IO, Vector[Mutation]] = Ref.unsafe(Vector.empty)
    val staleCommits: Ref[IO, Int]          = Ref.unsafe(0)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildService(
      initial: AlbumWorkspace,
      refs: Refs = Refs(),
      concurrentReads: Int = 0,
      maybeReadFailure: Option[Failure] = None,
      templateResult: TemplateResult = TemplateResult.Found(template),
      maybeCommitResult: Option[CommitResult] = None,
      staleWrites: Int = 0
  ) =
    val stored             = Ref.unsafe[IO, Snapshot](Snapshot(initial, revision = 0L))
    val nextIdentifier     = Ref.unsafe[IO, Long](100L)
    val readCount          = Ref.unsafe[IO, Int](0)
    val pendingStaleWrites = Ref.unsafe[IO, Int](staleWrites)
    val readsReady         = Deferred.unsafe[IO, Unit]
    val identifiers        = new IdGenerator[IO]:
      override def nextId: IO[UUID] = nextIdentifier.getAndUpdate(_ + 1L).map(new UUID(2L, _))
    val repository = new WorkspaceRepository[IO]:
      override def get(id: AlbumId): IO[ReadResult] =
        for
          snapshot <- stored.get
          count    <- readCount.updateAndGet(_ + 1)
          _        <- if concurrentReads <= 0 || count > concurrentReads then IO.unit
          else if count === concurrentReads then readsReady.complete(()).void else readsReady.get
        yield maybeReadFailure.fold[ReadResult](
          if id.value === albumId.value then ReadResult.Found(snapshot) else ReadResult.NotFound
        )(ReadResult.Failed(_))
      override def defaultChapterTemplate: IO[TemplateResult]                                = templateResult.pure[IO]
      override def commit(id: AlbumId, revision: Long, mutation: Mutation): IO[CommitResult] =
        pendingStaleWrites.getAndUpdate(value => (value - 1).max(0)).flatMap { remaining =>
          stored.modify { snapshot =>
            if remaining > 0 then snapshot.copy(revision = snapshot.revision + 1L) -> CommitResult.Stale
            else
              maybeCommitResult match
                case Some(result) => snapshot -> result
                case None         =>
                  if snapshot.revision =!= revision then snapshot -> CommitResult.Stale
                  else
                    val workspace = applyMutation(snapshot.workspace, mutation)
                    Snapshot(workspace, revision + 1L) -> CommitResult.Applied
          }
        }.flatTap {
          case CommitResult.Applied => refs.commands.update(_ :+ mutation)
          case CommitResult.Stale   => refs.staleCommits.update(_ + 1)
          case _                    => IO.unit
        }
    WorkspaceService.make(repository, identifiers, root)
  // scalafix:on DisableSyntax.defaultArgs

  private def applyMutation(workspace: AlbumWorkspace, mutation: Mutation) =
    mutation match
      case Mutation.AssignYear(selectedYear, title) =>
        workspace.copy(maybeYear = selectedYear.some, album = workspace.album.copy(title = title))
      case Mutation.CreateChapter(chapter, start, order) =>
        val chapters = workspace.chapters :+ chapter
        val ordered  = order.flatMap(id => chapters.find(_.id.value === id.value))
        workspace.copy(chapters = ordered, album = workspace.album.copy(spreads = workspace.album.spreads.append(start)))
      case Mutation.UpdateChapter(chapter, order) =>
        val chapters = workspace.chapters.map(existing => if existing.id.value === chapter.id.value then chapter else existing)
        workspace.copy(chapters = order.flatMap(id => chapters.find(_.id.value === id.value)))

  private def withSpreads(count: Int) =
    val chapter   = Chapter(ChapterId(new UUID(0L, 6L)), albumId, vacation, "", maybeSourceFolder = None)
    val workspace = withChapters(Vector(chapter))
    val photos    = Vector.tabulate(count - 2) { index =>
      Spread.PhotosOnly(
        SpreadId(new UUID(3L, index.toLong)),
        chapter.id,
        (index + 1).refineUnsafe,
        template.id,
        Vector(SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(7L, index.toLong)), SpreadId(new UUID(3L, index.toLong)), box, Set.empty)),
        Vector.empty
      ): Spread
    }
    workspace.copy(album = workspace.album.copy(spreads = workspace.album.spreads.concat(photos.toList)))

  private def withChapters(chapters: Vector[Chapter]) =
    val starts = chapters.map { chapter =>
      val spread = SpreadId(new UUID(5L, chapter.id.value.getLeastSignificantBits))
      val slots  = template.slots.toList.zipWithIndex.map { case (slot, index) =>
        val id = new UUID(chapter.id.value.getLeastSignificantBits + 1000L, index.toLong)
        slot match
          case SpreadTemplateSlot.Photo(geometry) => SpreadSlot.Photo(PhotoSpreadSlotId(id), spread, geometry, Set.empty): SpreadSlot
          case SpreadTemplateSlot.Text(geometry)  => SpreadSlot.Text(TextSpreadSlotId(id), spread, geometry): SpreadSlot
      }
      Spread.ChapterStart(spread, chapter.id, template.id, slots.toVector, Vector.empty): Spread
    }
    assigned.copy(chapters = chapters, album = assigned.album.copy(spreads = NonEmptyList(opening: Spread, starts.toList)))
