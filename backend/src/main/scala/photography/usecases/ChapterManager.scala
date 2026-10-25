package photography.usecases

import cats.Monad
import cats.data.NonEmptyList
import cats.syntax.all.*
import photography.capabilities.IdGenerator
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.{AlbumRepository, ChapterRepository}
import photography.usecases.ChapterManager.*
import photography.usecases.SpreadTemplateManager.ListChapterStartTemplatesResult
import io.github.iltotore.iron.autoRefine
import java.time.{LocalDate, Year}

trait ChapterManager[F[_]]:
  def create(album: AlbumId, name: DestinationName, countries: NonEmptyList[Country], period: Period, sourceFolder: SourceFolder): F[CreateResult]
  def update(
      album: AlbumId,
      chapter: ChapterId,
      name: DestinationName,
      countries: NonEmptyList[Country],
      period: Period,
      sourceFolder: SourceFolder
  ): F[UpdateResult]

object ChapterManager:
  def make[F[_]: Monad: IdGenerator](
      albums: AlbumRepository[F],
      chapters: ChapterRepository[F],
      templates: SpreadTemplateManager[F],
      rules: Rules
  ): ChapterManager[F] =
    new LiveChapterManager(albums, chapters, templates, rules)

  def rules: Rules = new LiveRules

  final private class LiveChapterManager[F[_]: Monad: IdGenerator](
      albums: AlbumRepository[F],
      chapters: ChapterRepository[F],
      templates: SpreadTemplateManager[F],
      rules: Rules
  ) extends ChapterManager[F]:
    override def create(
        album: AlbumId,
        name: DestinationName,
        countries: NonEmptyList[Country],
        period: Period,
        sourceFolder: SourceFolder
    ): F[CreateResult] =
      albums.get(album).flatMap:
        case AlbumRepository.GetResult.Found(current) => createChapter(current, name, countries, period, sourceFolder)
        case AlbumRepository.GetResult.NotFound       => CreateResult.AlbumNotFound.pure[F]
        case AlbumRepository.GetResult.Failed(_)      => CreateResult.Failed.pure[F]

    override def update(
        album: AlbumId,
        chapter: ChapterId,
        name: DestinationName,
        countries: NonEmptyList[Country],
        period: Period,
        sourceFolder: SourceFolder
    ): F[UpdateResult] =
      albums.get(album).flatMap:
        case AlbumRepository.GetResult.Found(current) =>
          chapters.update(album, chapter, name, countries, resolvePeriod(current.year, period), sourceFolder).map(toUpdateResult)
        case AlbumRepository.GetResult.NotFound  => UpdateResult.AlbumNotFound.pure[F]
        case AlbumRepository.GetResult.Failed(_) => UpdateResult.Failed.pure[F]

    private def createChapter(album: Album, name: DestinationName, countries: NonEmptyList[Country], period: Period, sourceFolder: SourceFolder) =
      templates.listChapterStartTemplates.flatMap:
        case ListChapterStartTemplatesResult.Listed(available) =>
          buildChapter(name, countries, resolvePeriod(album.year, period), sourceFolder, available.head).flatMap: chapter =>
            chapters.create(album.id, chapter).map(toCreateResult(_, chapter.id))
        case ListChapterStartTemplatesResult.Failed => CreateResult.Failed.pure[F]

    private def resolvePeriod(year: AlbumYear, period: Period) = period match
      case Period.AlbumYear    => rules.yearPeriod(year)
      case Period.Dates(value) => value

    private def buildChapter(
        name: DestinationName,
        countries: NonEmptyList[Country],
        period: VisitPeriod,
        sourceFolder: SourceFolder,
        template: SpreadTemplate.ChapterStart
    ) =
      for
        id          <- IdGenerator[F].nextId.map(ChapterId(_))
        opening     <- buildOpening(template)
        destination <- IdGenerator[F].nextId.map(value => Destination(DestinationId(value), name, countries))
      yield Chapter(id, destination, period, description = "", sourceFolder, opening, spreads = Vector.empty[Spread.PhotosOnly])

    private def buildOpening(template: SpreadTemplate.ChapterStart) =
      val newPhotos =
        template.photoSlots.traverse(box => IdGenerator[F].nextId.map(slot => SpreadSlot.Photo(PhotoSpreadSlotId(slot), box, bleed = Set.empty)))
      for
        id     <- IdGenerator[F].nextId.map(SpreadId(_))
        photos <- newPhotos
        text   <- IdGenerator[F].nextId.map(slot => SpreadSlot.Text(TextSpreadSlotId(slot), template.textSlot))
      yield Spread.ChapterStart(id, template.id, photos, text, placements = Vector.empty[SpreadPlacement])

    private def toCreateResult(result: ChapterRepository.CreateResult, id: ChapterId) = result match
      case ChapterRepository.CreateResult.Saved            => CreateResult.Created(id)
      case ChapterRepository.CreateResult.Rejected(reason) => CreateResult.Rejected(reason)
      case ChapterRepository.CreateResult.AlbumNotFound    => CreateResult.AlbumNotFound
      case ChapterRepository.CreateResult.Failed           => CreateResult.Failed

    private def toUpdateResult(result: ChapterRepository.UpdateResult) = result match
      case ChapterRepository.UpdateResult.Saved            => UpdateResult.Updated
      case ChapterRepository.UpdateResult.Rejected(reason) => UpdateResult.Rejected(reason)
      case ChapterRepository.UpdateResult.ChapterNotFound  => UpdateResult.ChapterNotFound
      case ChapterRepository.UpdateResult.AlbumNotFound    => UpdateResult.AlbumNotFound
      case ChapterRepository.UpdateResult.Failed           => UpdateResult.Failed

  final private class LiveRules extends Rules:
    override def ordered(chapters: Vector[Chapter]): Vector[Chapter] =
      chapters.sortBy { chapter =>
        (chapter.destination.isHome, chapter.period.firstDay.toEpochDay, chapter.period.days: Long, chapter.id.value.toString)
      }

    override def yearPeriod(year: AlbumYear): VisitPeriod =
      val days: VisitDays = if Year.isLeap(year.toLong) then 366L else 365L
      VisitPeriod(LocalDate.of(year, 1, 1), days)

    override def conflict(album: Album): Option[ChapterConflict] =
      val chapters = album.chapters
      val home     = chapters.filter(_.destination.isHome)
      if album.spreads.length > 65 then ChapterConflict.AtCapacity.some
      else if home.sizeIs > 1 then ChapterConflict.HomeExists.some
      else if home.exists(chapter => !samePeriod(chapter.period, yearPeriod(album.year))) then ChapterConflict.HomePeriodMismatch.some
      else if chapters.map(_.sourceFolder).distinct.size =!= chapters.size then ChapterConflict.FolderAssigned.some
      else None

    private def samePeriod(left: VisitPeriod, right: VisitPeriod) =
      left.firstDay.compareTo(right.firstDay) === 0 && left.days.compare(right.days) === 0

  enum CreateResult:
    case Created(id: ChapterId)
    case Rejected(reason: ChapterConflict)
    case AlbumNotFound, Failed

  enum UpdateResult:
    case Updated
    case Rejected(reason: ChapterConflict)
    case ChapterNotFound, AlbumNotFound, Failed

  enum Period:
    case AlbumYear
    case Dates(value: VisitPeriod)

  trait Rules:
    def yearPeriod(year: AlbumYear): VisitPeriod
    def conflict(album: Album): Option[ChapterConflict]
    def ordered(chapters: Vector[Chapter]): Vector[Chapter]
