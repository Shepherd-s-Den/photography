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
  def create(album: AlbumId, name: DestinationName, countries: NonEmptyList[CountryId], period: Period, sourceFolder: SourceFolder): F[CreateResult]
  def update(
      album: AlbumId,
      chapter: ChapterId,
      name: DestinationName,
      countries: NonEmptyList[CountryId],
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
        countries: NonEmptyList[CountryId],
        period: Period,
        sourceFolder: SourceFolder
    ): F[CreateResult] =
      summon[Mutex[F]].lock.surround:
        albums.get(album).flatMap:
          case AlbumRepository.GetResult.Found(current) =>
            val resolvedPeriod = resolvePeriod(current.year, period)
            val maybeConflict  =
              if current.spreads.length >= 65 then ChapterConflict.AtCapacity.some
              else
                findConflict(
                  current.year,
                  isHome = (name: String) === "Home",
                  resolvedPeriod,
                  homeExists = current.chapters.exists(_.destination.isHome),
                  folderAssigned = current.chapters.exists(value => (value.sourceFolder: String) === sourceFolder)
                )
            maybeConflict match
              case Some(reason) => CreateResult.Rejected(reason).pure[F]
              case None         =>
                templates.listChapterStartTemplates.flatMap:
                  case ListChapterStartTemplatesResult.Listed(available) =>
                    val template   = available.head
                    val others     = current.chapters.map(value => ChapterOrder(value.id, value.destination.isHome, value.period))
                    val newChapter = buildChapter(name, countries, resolvedPeriod, sourceFolder, template.id, template.photoSlots, template.textSlot)
                    newChapter.flatMap: chapter =>
                      val order = NonEmptyList(ChapterOrder(chapter.id, isHome = (chapter.name: String) === "Home", chapter.period), others.toList)
                      chapters.create(album, chapter, orderChapterIds(order)).map(saved => translateCreateResult(saved, chapter.id))
                  case ListChapterStartTemplatesResult.Failed => CreateResult.Failed.pure[F]
          case AlbumRepository.GetResult.NotFound  => CreateResult.AlbumNotFound.pure[F]
          case AlbumRepository.GetResult.Failed(_) => CreateResult.Failed.pure[F]

    override def update(
        album: AlbumId,
        chapter: ChapterId,
        name: DestinationName,
        countries: NonEmptyList[CountryId],
        period: Period,
        sourceFolder: SourceFolder
    ): F[UpdateResult] =
      summon[Mutex[F]].lock.surround:
        albums.get(album).flatMap:
          case AlbumRepository.GetResult.Found(current) =>
            current.chapters.find(_.id.value === chapter.value) match
              case None           => UpdateResult.ChapterNotFound.pure[F]
              case Some(existing) =>
                val others         = current.chapters.filterNot(_.id.value === chapter.value)
                val resolvedPeriod = resolvePeriod(current.year, period)
                val isHome         = (name: String) === "Home"
                val maybeConflict  =
                  if existing.destination.isHome =!= isHome then ChapterConflict.TypeChange.some
                  else
                    findConflict(
                      current.year,
                      isHome,
                      resolvedPeriod,
                      homeExists = others.exists(_.destination.isHome),
                      folderAssigned = others.exists(value => (value.sourceFolder: String) === sourceFolder)
                    )
                maybeConflict match
                  case Some(reason) => UpdateResult.Rejected(reason).pure[F]
                  case None         =>
                    val order = NonEmptyList(
                      ChapterOrder(chapter, isHome, resolvedPeriod),
                      others.map(value => ChapterOrder(value.id, value.destination.isHome, value.period)).toList
                    )
                    chapters.update(album, chapter, name, countries, resolvedPeriod, sourceFolder, orderChapterIds(order)).map(translateUpdateResult)
          case AlbumRepository.GetResult.NotFound  => UpdateResult.AlbumNotFound.pure[F]
          case AlbumRepository.GetResult.Failed(_) => UpdateResult.Failed.pure[F]

    private def resolvePeriod(year: AlbumYear, period: Period) = period match
      case Period.AlbumYear    => buildYearPeriod(year)
      case Period.Dates(value) => value

    private def findConflict(year: AlbumYear, isHome: Boolean, period: VisitPeriod, homeExists: Boolean, folderAssigned: Boolean) =
      if isHome && homeExists then ChapterConflict.HomeExists.some
      else if isHome && !arePeriodsEqual(period, buildYearPeriod(year)) then ChapterConflict.HomePeriodMismatch.some
      else if folderAssigned then ChapterConflict.FolderAssigned.some
      else None

    private def orderChapterIds(chapters: NonEmptyList[ChapterOrder]) =
      chapters.sortBy { value =>
        (value.isHome, value.period.firstDay.toEpochDay, value.period.days: Long, value.id.value.toString)
      }.map(_.id)

    private def buildYearPeriod(year: AlbumYear) =
      val days: VisitDays = if Year.isLeap(year.toLong) then 366L else 365L
      VisitPeriod(firstDay = LocalDate.of(year, 1, 1), days = days)

    private def arePeriodsEqual(left: VisitPeriod, right: VisitPeriod) =
      left.firstDay.compareTo(right.firstDay) === 0 && left.days.compare(right.days) === 0

    private def buildChapter(
        name: DestinationName,
        countries: NonEmptyList[CountryId],
        period: VisitPeriod,
        sourceFolder: SourceFolder,
        template: SpreadTemplateId,
        photoSlots: Vector[SlotBox],
        textSlot: SlotBox
    ) =
      for
        id          <- IdGenerator[F].generateId.map(ChapterId(_))
        opening     <- buildOpening(template, photoSlots, textSlot)
        destination <- IdGenerator[F].generateId.map(DestinationId(_))
      yield ChapterRepository.NewChapter(id, destination, name, countries, period, description = "", sourceFolder, opening)

    private def buildOpening(template: SpreadTemplateId, photoSlots: Vector[SlotBox], textSlot: SlotBox) =
      val newPhotos = photoSlots.traverse(box => IdGenerator[F].generateId.map(id => SpreadSlot.Photo(PhotoSpreadSlotId(id), box, bleed = Set.empty)))
      for
        id     <- IdGenerator[F].generateId.map(SpreadId(_))
        photos <- newPhotos
        text   <- IdGenerator[F].generateId.map(slot => SpreadSlot.Text(TextSpreadSlotId(slot), textSlot))
      yield Spread.ChapterStart(id, template, photoSlots = photos, textSlot = text, placements = Vector.empty[SpreadPlacement])

    private def translateCreateResult(result: ChapterRepository.CreateResult, id: ChapterId) = result match
      case ChapterRepository.CreateResult.Saved         => CreateResult.Created(id)
      case ChapterRepository.CreateResult.AlbumNotFound => CreateResult.AlbumNotFound
      case ChapterRepository.CreateResult.Failed        => CreateResult.Failed

    private def translateUpdateResult(result: ChapterRepository.UpdateResult) = result match
      case ChapterRepository.UpdateResult.Saved           => UpdateResult.Updated
      case ChapterRepository.UpdateResult.ChapterNotFound => UpdateResult.ChapterNotFound
      case ChapterRepository.UpdateResult.AlbumNotFound   => UpdateResult.AlbumNotFound
      case ChapterRepository.UpdateResult.Failed          => UpdateResult.Failed

  final private case class ChapterOrder(id: ChapterId, isHome: Boolean, period: VisitPeriod)

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
