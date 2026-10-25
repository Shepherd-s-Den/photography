package photography.usecases

import cats.Monad
import cats.syntax.all.*
import photography.capabilities.IdGenerator
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.WorkspaceRepository
import photography.ports.WorkspaceRepository.{CommitResult, Mutation, ReadResult, Snapshot, TemplateResult}
import photography.usecases.WorkspaceService.*

import java.time.Instant

trait WorkspaceService[F[_]]:
  def get(id: AlbumId): F[GetResult]
  def assignYear(id: AlbumId, year: AlbumYear): F[SaveYearResult]
  def createChapter(id: AlbumId, details: ChapterDetails, maybeSourceFolder: Option[RelativeLocation]): F[SaveChapterResult]
  def updateChapter(id: AlbumId, chapter: ChapterId, details: ChapterDetails, maybeSourceFolder: Option[RelativeLocation]): F[SaveChapterResult]

object WorkspaceService:
  def make[F[_]: Monad](repository: WorkspaceRepository[F], identifiers: IdGenerator[F], photoRoot: AbsoluteLocation): WorkspaceService[F] =
    new LiveWorkspaceService(repository, identifiers, photoRoot)

  final private class LiveWorkspaceService[F[_]: Monad](
      repository: WorkspaceRepository[F],
      identifiers: IdGenerator[F],
      photoRoot: AbsoluteLocation
  ) extends WorkspaceService[F]:
    override def get(id: AlbumId): F[GetResult] =
      repository.get(id).map:
        case ReadResult.Found(snapshot) => GetResult.Found(WorkspaceView(snapshot.workspace, photoRoot))
        case ReadResult.NotFound        => GetResult.NotFound
        case ReadResult.Failed(_)       => GetResult.Failed

    override def assignYear(id: AlbumId, year: AlbumYear): F[SaveYearResult] =
      retry(id, SaveYearResult.NotFound, SaveYearResult.Failed) { snapshot =>
        val workspace = snapshot.workspace
        if workspace.chapters.nonEmpty && workspace.maybeYear.exists(current => (current: Int) =!= (year: Int)) then
          SaveYearResult.Invalid("An album with chapters cannot change year").asRight[Unit].pure[F]
        else
          repository.commit(id, snapshot.revision, Mutation.AssignYear(year, AlbumYear.title(year))).map:
            case CommitResult.Applied           => SaveYearResult.Saved(year, AlbumYear.title(year)).asRight[Unit]
            case CommitResult.Stale             => ().asLeft[SaveYearResult]
            case CommitResult.NotFound          => SaveYearResult.NotFound.asRight[Unit]
            case CommitResult.Rejected(message) => SaveYearResult.Invalid(message).asRight[Unit]
            case CommitResult.Failed(_)         => SaveYearResult.Failed.asRight[Unit]
      }

    override def createChapter(id: AlbumId, details: ChapterDetails, maybeSourceFolder: Option[RelativeLocation]): F[SaveChapterResult] =
      retry(id, SaveChapterResult.AlbumNotFound, SaveChapterResult.Failed) { snapshot =>
        validateChapter(snapshot.workspace, details, maybeSourceFolder, maybeExisting = None) match
          case Some(message) => SaveChapterResult.Invalid(message).asRight[Unit].pure[F]
          case None          => createOpening(id, snapshot, details, maybeSourceFolder)
      }

    override def updateChapter(
        id: AlbumId,
        chapter: ChapterId,
        details: ChapterDetails,
        maybeSourceFolder: Option[RelativeLocation]
    ): F[SaveChapterResult] =
      retry(id, SaveChapterResult.AlbumNotFound, SaveChapterResult.Failed) { snapshot =>
        snapshot.workspace.chapters.find(_.id.value === chapter.value) match
          case None           => SaveChapterResult.ChapterNotFound.asRight[Unit].pure[F]
          case Some(existing) => updateMetadata(id, snapshot, existing, details, maybeSourceFolder)
      }

    private def retry[A](id: AlbumId, missing: A, failed: A)(operation: Snapshot => F[Either[Unit, A]]) =
      Monad[F].tailRecM(()) { _ =>
        repository.get(id).flatMap:
          case ReadResult.Found(snapshot) => operation(snapshot)
          case ReadResult.NotFound        => missing.asRight[Unit].pure[F]
          case ReadResult.Failed(_)       => failed.asRight[Unit].pure[F]
      }

    private def createOpening(id: AlbumId, snapshot: Snapshot, details: ChapterDetails, maybeFolder: Option[RelativeLocation]) =
      repository.defaultChapterTemplate.flatMap:
        case TemplateResult.Failed(_)       => SaveChapterResult.Failed.asRight[Unit].pure[F]
        case TemplateResult.Found(template) =>
          for
            chapterId <- identifiers.nextId.map(ChapterId(_))
            spreadId  <- identifiers.nextId.map(SpreadId(_))
            slots     <- template.slots.toList.traverse(initialSlot(spreadId, _))
            result    <- saveNewChapter(id, snapshot, Chapter(chapterId, id, details, "", maybeFolder), template.id, spreadId, slots.toVector)
          yield result

    private def saveNewChapter(
        id: AlbumId,
        snapshot: Snapshot,
        chapter: Chapter,
        template: SpreadTemplateId,
        spread: SpreadId,
        slots: Vector[SpreadSlot]
    ) =
      val opening = Spread.ChapterStart(spread, chapter.id, template, slots, Vector.empty)
      val order   = chronologicalOrder(snapshot.workspace.chapters :+ chapter)
      repository.commit(id, snapshot.revision, Mutation.CreateChapter(chapter, opening, order)).map(chapterResult(chapter.id, _))

    private def updateMetadata(
        id: AlbumId,
        snapshot: Snapshot,
        existing: Chapter,
        details: ChapterDetails,
        maybeFolder: Option[RelativeLocation]
    ) =
      validateChapter(snapshot.workspace, details, maybeFolder, existing.some) match
        case Some(message) => SaveChapterResult.Invalid(message).asRight[Unit].pure[F]
        case None          =>
          val updated  = existing.copy(details = details, maybeSourceFolder = maybeFolder)
          val chapters = snapshot.workspace.chapters.map(chapter => if chapter.id.value === existing.id.value then updated else chapter)
          repository.commit(id, snapshot.revision, Mutation.UpdateChapter(updated, chronologicalOrder(chapters))).map(chapterResult(existing.id, _))

    private def validateChapter(
        workspace: AlbumWorkspace,
        details: ChapterDetails,
        maybeFolder: Option[RelativeLocation],
        maybeExisting: Option[Chapter]
    ) =
      val others = workspace.chapters.filterNot(chapter => maybeExisting.exists(_.id.value === chapter.id.value))
      if workspace.maybeYear.isEmpty then "Assign an album year before adding chapters".some
      else if maybeExisting.isEmpty && workspace.album.spreads.length >= 65 then "Album capacity is 65 interior spreads".some
      else if !workspace.countries.exists(_.id.value === details.countryId.value) then "Choose a country from the catalog".some
      else if maybeExisting.exists(chapter => isHome(chapter.details) =!= isHome(details)) then "Chapter type cannot change".some
      else if isHome(details) && others.exists(chapter => isHome(chapter.details)) then "An album can have only one Home chapter".some
      else if maybeFolder.exists(folder => others.exists(_.maybeSourceFolder.exists(_.value.toString === folder.value.toString))) then
        "Photo folder is already assigned to another chapter".some
      else None

    private def chronologicalOrder(chapters: Vector[Chapter]) =
      chapters.sortBy { chapter =>
        chapter.details match
          case ChapterDetails.Vacation(_, _, period) => (0, period.start, period.end, chapter.id.value.toString)
          case ChapterDetails.Home(_)                => (1, Instant.EPOCH, Instant.EPOCH, chapter.id.value.toString)
      }.map(_.id)

    private def isHome(details: ChapterDetails) = details match
      case ChapterDetails.Home(_)           => true
      case ChapterDetails.Vacation(_, _, _) => false

    private def initialSlot(spread: SpreadId, slot: SpreadTemplateSlot) =
      identifiers.nextId.map { id =>
        slot match
          case SpreadTemplateSlot.Photo(box) => SpreadSlot.Photo(PhotoSpreadSlotId(id), spread, box, Set.empty): SpreadSlot
          case SpreadTemplateSlot.Text(box)  => SpreadSlot.Text(TextSpreadSlotId(id), spread, box): SpreadSlot
      }

    private def chapterResult(id: ChapterId, result: CommitResult) =
      result match
        case CommitResult.Applied           => SaveChapterResult.Saved(id).asRight[Unit]
        case CommitResult.Stale             => ().asLeft[SaveChapterResult]
        case CommitResult.NotFound          => SaveChapterResult.AlbumNotFound.asRight[Unit]
        case CommitResult.Rejected(message) => SaveChapterResult.Invalid(message).asRight[Unit]
        case CommitResult.Failed(_)         => SaveChapterResult.Failed.asRight[Unit]

  final case class WorkspaceView(workspace: AlbumWorkspace, photoRoot: AbsoluteLocation)

  enum GetResult:
    case Found(view: WorkspaceView)
    case NotFound, Failed

  enum SaveYearResult:
    case Saved(year: AlbumYear, title: AlbumName)
    case Invalid(message: String)
    case NotFound, Failed

  enum SaveChapterResult:
    case Saved(id: ChapterId)
    case Invalid(message: String)
    case AlbumNotFound, ChapterNotFound, Failed
