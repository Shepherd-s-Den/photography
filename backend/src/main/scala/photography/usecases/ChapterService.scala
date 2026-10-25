package photography.usecases

import cats.Monad
import cats.syntax.all.*
import photography.capabilities.IdGenerator
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.ChapterRepository
import photography.ports.ChapterRepository.{Decision, SaveResult}
import photography.usecases.ChapterService.*
import photography.usecases.SpreadTemplateService.ListChapterStartTemplatesResult

trait ChapterService[F[_]]:
  def create(album: AlbumId, details: Details, maybeFolder: Option[RelativeLocation]): F[CreateResult]
  def updateVacation(album: AlbumId, chapter: ChapterId, details: Details.Vacation, maybeFolder: Option[RelativeLocation]): F[UpdateResult]
  def updateHome(album: AlbumId, chapter: ChapterId, details: Details.Home, maybeFolder: Option[RelativeLocation]): F[UpdateResult]

object ChapterService:
  def make[F[_]: Monad](repository: ChapterRepository[F], templates: SpreadTemplateService[F], identifiers: IdGenerator[F]): ChapterService[F] =
    new LiveChapterService(repository, templates, identifiers)

  final private class LiveChapterService[F[_]: Monad](
      repository: ChapterRepository[F],
      templates: SpreadTemplateService[F],
      identifiers: IdGenerator[F]
  ) extends ChapterService[F]:
    override def create(album: AlbumId, details: Details, maybeFolder: Option[RelativeLocation]): F[CreateResult] =
      templates.listChapterStartTemplates.flatMap:
        case ListChapterStartTemplatesResult.Listed(available) =>
          buildChapter(details, maybeFolder, available.head).flatMap: chapter =>
            repository.modifyAtomically(album, current => createDecision(current, chapter)).map:
              case SaveResult.Saved            => CreateResult.Created(chapter.id)
              case SaveResult.Rejected(reason) => CreateResult.Rejected(reason)
              case SaveResult.AlbumNotFound    => CreateResult.AlbumNotFound
              case SaveResult.Failed           => CreateResult.Failed
        case ListChapterStartTemplatesResult.Failed => CreateResult.Failed.pure[F]

    override def updateVacation(
        album: AlbumId,
        chapter: ChapterId,
        details: Details.Vacation,
        maybeFolder: Option[RelativeLocation]
    ): F[UpdateResult] =
      repository.modifyAtomically(album, current => vacationDecision(current, chapter, details, maybeFolder)).map(toUpdateResult)

    override def updateHome(album: AlbumId, chapter: ChapterId, details: Details.Home, maybeFolder: Option[RelativeLocation]): F[UpdateResult] =
      repository.modifyAtomically(album, current => homeDecision(current, chapter, details, maybeFolder)).map(toUpdateResult)

    private def createDecision(album: Album, chapter: Chapter) =
      if album.spreads.length >= 65 then Decision.Reject(ChapterConflict.AtCapacity)
      else
        chapter match
          case _: Chapter.Home if album.maybeHome.nonEmpty => Decision.Reject(ChapterConflict.HomeExists)
          case _                                           => saveChapter(album, chapter)

    private def vacationDecision(album: Album, id: ChapterId, details: Details.Vacation, maybeFolder: Option[RelativeLocation]) =
      album.vacations.find(_.id.value === id.value) match
        case None          => Decision.Reject(ChapterConflict.ChapterNotFound)
        case Some(chapter) =>
          val destination = chapter.destination.copy(name = details.name, country = details.country)
          saveChapter(album, chapter.copy(destination = destination, period = details.period, maybeSourceFolder = maybeFolder))

    private def homeDecision(album: Album, id: ChapterId, details: Details.Home, maybeFolder: Option[RelativeLocation]) =
      album.maybeHome.filter(_.id.value === id.value) match
        case None          => Decision.Reject(ChapterConflict.ChapterNotFound)
        case Some(chapter) => saveChapter(album, chapter.copy(country = details.country, maybeSourceFolder = maybeFolder))

    private def saveChapter(album: Album, chapter: Chapter) =
      val others = album.chapters.filterNot(_.id.value === chapter.id.value)
      if chapter.maybeSourceFolder.exists(folder => others.exists(_.maybeSourceFolder.contains(folder))) then
        Decision.Reject(ChapterConflict.FolderAssigned)
      else
        chapter match
          case vacation: Chapter.Vacation =>
            val vacations = album.vacations.filterNot(_.id.value === vacation.id.value) :+ vacation
            val ordered   = vacations.sortBy(chapter => (chapter.period.firstDay.toEpochDay, chapter.period.days: Long, chapter.id.value.toString))
            Decision.Save(album.copy(vacations = ordered))
          case home: Chapter.Home => Decision.Save(album.copy(maybeHome = home.some))

    private def buildChapter(details: Details, maybeFolder: Option[RelativeLocation], template: SpreadTemplate.ChapterStart) =
      for
        id      <- identifiers.nextId.map(ChapterId(_))
        opening <- buildOpening(template)
        chapter <- details match
          case Details.Vacation(name, country, period) =>
            identifiers.nextId.map(destination =>
              Chapter.Vacation(id, Destination(DestinationId(destination), name, country), period, "", maybeFolder, opening, Vector.empty)
            )
          case Details.Home(country) => Chapter.Home(id, country, "", maybeFolder, opening, Vector.empty).pure[F]
      yield chapter

    private def buildOpening(template: SpreadTemplate.ChapterStart) =
      for
        id     <- identifiers.nextId.map(SpreadId(_))
        photos <- template.photoSlots.traverse(box => identifiers.nextId.map(slot => SpreadSlot.Photo(PhotoSpreadSlotId(slot), box, Set.empty)))
        text   <- identifiers.nextId.map(slot => SpreadSlot.Text(TextSpreadSlotId(slot), template.textSlot))
      yield Spread.ChapterStart(id, template.id, photos, text, Vector.empty)

    private def toUpdateResult(result: SaveResult) = result match
      case SaveResult.Saved            => UpdateResult.Updated
      case SaveResult.Rejected(reason) => UpdateResult.Rejected(reason)
      case SaveResult.AlbumNotFound    => UpdateResult.AlbumNotFound
      case SaveResult.Failed           => UpdateResult.Failed

  sealed trait Details

  object Details:
    final case class Vacation(name: ChapterName, country: Country, period: VisitPeriod) extends Details
    final case class Home(country: Country)                                             extends Details

  enum CreateResult:
    case Created(id: ChapterId)
    case Rejected(reason: ChapterConflict)
    case AlbumNotFound, Failed

  enum UpdateResult:
    case Updated
    case Rejected(reason: ChapterConflict)
    case AlbumNotFound, Failed
