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
      // Concurrent requests must not both claim the last spread, Home or a folder from the same album snapshot.
      summon[Mutex[F]].lock.surround:
        albums.get(album).flatMap:
          case AlbumRepository.GetResult.Found(current) =>
            val resolvedPeriod = resolvePeriod(current.year, period)
            findCreationConflict(current, name, resolvedPeriod, sourceFolder) match
              case Some(reason) => CreateResult.Rejected(reason).pure[F]
              case None         =>
                templates.listChapterStartTemplates.flatMap:
                  case ListChapterStartTemplatesResult.Listed(available) =>
                    for
                      chapter <- buildChapter(name, countries, resolvedPeriod, sourceFolder, available.head)
                      saved   <- chapters.create(album, chapter, orderChapterIds(chapter, current.chapters))
                    yield translateCreateResult(saved, chapter.id)
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
      // Metadata edits must not race creation or each other when checking folder ownership and replacing chapter order.
      summon[Mutex[F]].lock.surround:
        albums.get(album).flatMap:
          case AlbumRepository.GetResult.Found(current) =>
            current.chapters.find(_.id.value === chapter.value) match
              case None           => UpdateResult.ChapterNotFound.pure[F]
              case Some(existing) =>
                val others = current.chapters.filterNot(_.id.value === chapter.value)
                reviseChapter(current.year, existing, others, name, countries, resolvePeriod(current.year, period), sourceFolder) match
                  case Left(reason)   => UpdateResult.Rejected(reason).pure[F]
                  case Right(updated) =>
                    chapters.update(
                      album,
                      chapter,
                      name,
                      countries,
                      updated.period,
                      sourceFolder,
                      orderChapterIds(updated, others)
                    ).map(translateUpdateResult)
          case AlbumRepository.GetResult.NotFound  => UpdateResult.AlbumNotFound.pure[F]
          case AlbumRepository.GetResult.Failed(_) => UpdateResult.Failed.pure[F]

    private def findCreationConflict(
        album: Album,
        name: DestinationName,
        period: VisitPeriod,
        sourceFolder: SourceFolder
    ) =
      if album.spreads.length >= 65 then ChapterConflict.AtCapacity.some
      else findConflict(album.year, album.chapters, name, period, sourceFolder)

    private def reviseChapter(
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
        findConflict(year, others, name, period, sourceFolder).toLeft:
          current.copy(destination = current.destination.copy(name = name, countries = countries), period = period, sourceFolder = sourceFolder)

    private def resolvePeriod(year: AlbumYear, period: Period) = period match
      case Period.AlbumYear    => buildYearPeriod(year)
      case Period.Dates(value) => value

    private def findConflict(year: AlbumYear, others: Vector[Chapter], name: DestinationName, period: VisitPeriod, sourceFolder: SourceFolder) =
      val isHome = (name: String) === "Home"
      if isHome && others.exists(_.destination.isHome) then ChapterConflict.HomeExists.some
      else if isHome && !arePeriodsEqual(period, buildYearPeriod(year)) then ChapterConflict.HomePeriodMismatch.some
      else if others.exists(value => (value.sourceFolder: String) === sourceFolder) then ChapterConflict.FolderAssigned.some
      else None

    private def orderChapterIds(chapter: Chapter, others: Vector[Chapter]) =
      NonEmptyList(chapter, others.toList).sortBy { value =>
        (value.destination.isHome, value.period.firstDay.toEpochDay, value.period.days: Long, value.id.value.toString)
      }.map(_.id)

    private def buildYearPeriod(year: AlbumYear) =
      val days: VisitDays = if Year.isLeap(year.toLong) then 366L else 365L
      VisitPeriod(firstDay = LocalDate.of(year, 1, 1), days = days)

    private def arePeriodsEqual(left: VisitPeriod, right: VisitPeriod) =
      left.firstDay.compareTo(right.firstDay) === 0 && left.days.compare(right.days) === 0

    private def buildChapter(
        name: DestinationName,
        countries: NonEmptyList[Country],
        period: VisitPeriod,
        sourceFolder: SourceFolder,
        template: SpreadTemplate.ChapterStart
    ) =
      for
        id          <- IdGenerator[F].generateId.map(ChapterId(_))
        opening     <- buildOpening(template)
        destination <- IdGenerator[F].generateId.map(value => Destination(DestinationId(value), name = name, countries))
      yield Chapter(id, destination, period, description = "", sourceFolder = sourceFolder, opening, spreads = Vector.empty[Spread.PhotosOnly])

    private def buildOpening(template: SpreadTemplate.ChapterStart) =
      val newPhotos =
        template.photoSlots.traverse(box => IdGenerator[F].generateId.map(slot => SpreadSlot.Photo(PhotoSpreadSlotId(slot), box, bleed = Set.empty)))
      for
        id     <- IdGenerator[F].generateId.map(SpreadId(_))
        photos <- newPhotos
        text   <- IdGenerator[F].generateId.map(slot => SpreadSlot.Text(TextSpreadSlotId(slot), template.textSlot))
      yield Spread.ChapterStart(id, template.id, photoSlots = photos, textSlot = text, placements = Vector.empty[SpreadPlacement])

    private def translateCreateResult(result: ChapterRepository.CreateResult, id: ChapterId) = result match
      case ChapterRepository.CreateResult.Saved         => CreateResult.Created(id)
      case ChapterRepository.CreateResult.AlbumNotFound => CreateResult.AlbumNotFound
      case ChapterRepository.CreateResult.Failed        => CreateResult.Failed

    private def translateUpdateResult(result: ChapterRepository.UpdateResult) = result match
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
