package photography.usecases

import cats.data.NonEmptyList
import cats.effect.kernel.MonadCancelThrow
import cats.effect.std.Mutex
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
  def make[F[_]: MonadCancelThrow: IdGenerator: Mutex](
      albums: AlbumRepository[F],
      chapters: ChapterRepository[F],
      templates: SpreadTemplateManager[F]
  ): ChapterManager[F] =
    new LiveChapterManager(albums, chapters, templates)

  final private class LiveChapterManager[F[_]: MonadCancelThrow: IdGenerator: Mutex](
      albums: AlbumRepository[F],
      chapters: ChapterRepository[F],
      templates: SpreadTemplateManager[F]
  ) extends ChapterManager[F]:
    override def create(
        album: AlbumId,
        name: DestinationName,
        countries: NonEmptyList[Country],
        period: Period,
        sourceFolder: SourceFolder
    ): F[CreateResult] =
      summon[Mutex[F]].lock.surround:
        albums.get(album).flatMap:
          case AlbumRepository.GetResult.Found(current) =>
            val resolvedPeriod = resolvePeriod(current.year, period)
            creationConflict(current, name, resolvedPeriod, sourceFolder) match
              case Some(reason) => CreateResult.Rejected(reason).pure[F]
              case None         =>
                templates.listChapterStartTemplates.flatMap:
                  case ListChapterStartTemplatesResult.Listed(available) =>
                    for
                      chapter <- buildChapter(name, countries, resolvedPeriod, sourceFolder, available.head)
                      saved   <- chapters.create(album, chapter, chapterOrder(chapter, current.chapters))
                    yield toCreateResult(saved, chapter.id)
                  case ListChapterStartTemplatesResult.Failed => CreateResult.Failed.pure[F]
          case AlbumRepository.GetResult.NotFound  => CreateResult.AlbumNotFound.pure[F]
          case AlbumRepository.GetResult.Failed(_) => CreateResult.Failed.pure[F]

    override def update(
        album: AlbumId,
        chapter: ChapterId,
        name: DestinationName,
        countries: NonEmptyList[Country],
        period: Period,
        sourceFolder: SourceFolder
    ): F[UpdateResult] =
      summon[Mutex[F]].lock.surround:
        albums.get(album).flatMap:
          case AlbumRepository.GetResult.Found(current) =>
            current.chapters.find(_.id.value === chapter.value) match
              case None           => UpdateResult.ChapterNotFound.pure[F]
              case Some(existing) =>
                val others = current.chapters.filterNot(_.id.value === chapter.value)
                revisedChapter(current.year, existing, others, name, countries, resolvePeriod(current.year, period), sourceFolder) match
                  case Left(reason)   => UpdateResult.Rejected(reason).pure[F]
                  case Right(updated) =>
                    chapters.update(album, chapter, name, countries, updated.period, sourceFolder, chapterOrder(updated, others)).map(toUpdateResult)
          case AlbumRepository.GetResult.NotFound  => UpdateResult.AlbumNotFound.pure[F]
          case AlbumRepository.GetResult.Failed(_) => UpdateResult.Failed.pure[F]

    private def creationConflict(
        album: Album,
        name: DestinationName,
        period: VisitPeriod,
        sourceFolder: SourceFolder
    ) =
      if album.spreads.length >= 65 then ChapterConflict.AtCapacity.some
      else conflict(album.year, album.chapters, name, period, sourceFolder)

    private def revisedChapter(
        year: AlbumYear,
        current: Chapter,
        others: Vector[Chapter],
        name: DestinationName,
        countries: NonEmptyList[Country],
        period: VisitPeriod,
        sourceFolder: SourceFolder
    ) =
      if current.destination.isHome =!= ((name: String) === "Home") then ChapterConflict.TypeChange.asLeft[Chapter]
      else
        conflict(year, others, name, period, sourceFolder).toLeft:
          current.copy(destination = current.destination.copy(name = name, countries = countries), period = period, sourceFolder = sourceFolder)

    private def resolvePeriod(year: AlbumYear, period: Period) = period match
      case Period.AlbumYear    => yearPeriod(year)
      case Period.Dates(value) => value

    private def conflict(year: AlbumYear, others: Vector[Chapter], name: DestinationName, period: VisitPeriod, sourceFolder: SourceFolder) =
      val isHome = (name: String) === "Home"
      if isHome && others.exists(_.destination.isHome) then ChapterConflict.HomeExists.some
      else if isHome && !samePeriod(period, yearPeriod(year)) then ChapterConflict.HomePeriodMismatch.some
      else if others.exists(value => (value.sourceFolder: String) === sourceFolder) then ChapterConflict.FolderAssigned.some
      else None

    private def chapterOrder(chapter: Chapter, others: Vector[Chapter]) =
      NonEmptyList(chapter, others.toList).sortBy { value =>
        (value.destination.isHome, value.period.firstDay.toEpochDay, value.period.days: Long, value.id.value.toString)
      }.map(_.id)

    private def yearPeriod(year: AlbumYear) =
      val days: VisitDays = if Year.isLeap(year.toLong) then 366L else 365L
      VisitPeriod(LocalDate.of(year, 1, 1), days)

    private def samePeriod(left: VisitPeriod, right: VisitPeriod) =
      left.firstDay.compareTo(right.firstDay) === 0 && left.days.compare(right.days) === 0

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
      case ChapterRepository.CreateResult.Saved         => CreateResult.Created(id)
      case ChapterRepository.CreateResult.AlbumNotFound => CreateResult.AlbumNotFound
      case ChapterRepository.CreateResult.Failed        => CreateResult.Failed

    private def toUpdateResult(result: ChapterRepository.UpdateResult) = result match
      case ChapterRepository.UpdateResult.Saved           => UpdateResult.Updated
      case ChapterRepository.UpdateResult.ChapterNotFound => UpdateResult.ChapterNotFound
      case ChapterRepository.UpdateResult.AlbumNotFound   => UpdateResult.AlbumNotFound
      case ChapterRepository.UpdateResult.Failed          => UpdateResult.Failed

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
