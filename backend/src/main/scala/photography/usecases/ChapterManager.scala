package photography.usecases

import cats.data.{EitherT, NonEmptyList}
import cats.effect.kernel.MonadCancelThrow
import cats.effect.std.Mutex
import cats.syntax.all.*
import photography.capabilities.IdGenerator
import org.typelevel.log4cats.Logger
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.{AlbumRepository, ChapterRepository}
import photography.syntax.EitherSyntax.*
import photography.usecases.ChapterManager.*
import photography.usecases.SpreadTemplateManager.ListChapterStartTemplatesResult
import photography.usecases.ChapterPhotoImport.{Request, Scheduling}
import io.github.iltotore.iron.autoRefine
import java.time.{LocalDate, Year}

trait ChapterManager[F[_]]:
  def create(album: AlbumId, name: DestinationName, countries: NonEmptyList[CountryId], period: Period, sourceFolder: PhotoSource): F[CreateResult]
  def update(
      album: AlbumId,
      chapter: ChapterId,
      name: DestinationName,
      countries: NonEmptyList[CountryId],
      period: Period,
      sourceFolder: PhotoSource
  ): F[UpdateResult]
  def refresh(album: AlbumId, chapter: ChapterId): F[RefreshResult]

object ChapterManager:
  def make[F[_]: MonadCancelThrow: IdGenerator: Mutex: Logger](
      albums: AlbumRepository[F],
      chapters: ChapterRepository[F],
      templates: SpreadTemplateManager[F],
      photos: ChapterPhotoImport[F]
  ): ChapterManager[F] =
    new LiveChapterManager(albums, chapters, templates, photos)

  final private class LiveChapterManager[F[_]: MonadCancelThrow: IdGenerator: Mutex: Logger](
      albums: AlbumRepository[F],
      chapters: ChapterRepository[F],
      templates: SpreadTemplateManager[F],
      photos: ChapterPhotoImport[F]
  ) extends ChapterManager[F]:
    override def create(
        album: AlbumId,
        name: DestinationName,
        countries: NonEmptyList[CountryId],
        period: Period,
        sourceFolder: PhotoSource
    ): F[CreateResult] =
      val creation = summon[Mutex[F]].lock.surround:
        val program =
          for
            current                          <- readAlbum(album).eitherT
            resolved                         <- EitherT.fromEither[F](checkCreation(current, name, period, sourceFolder))
            (template, photoSlots, textSlot) <- readChapterStartTemplate.eitherT
            chapter                          <- EitherT.liftF(buildChapter(name, countries, resolved, sourceFolder, template, photoSlots, textSlot))
            id                               <- persistCreation(album, current.chapters, chapter).eitherT
          yield id
        program.value.flatMap: outcome =>
          outcome.traverse(id => photos.prepare(id, sourceFolder).attempt.tupleLeft(id))
      MonadCancelThrow[F].uncancelable: _ =>
        creation.flatMap:
          case Left(failure)        => translateCreateFailure(failure).pure[F]
          case Right((id, request)) => launchImport(request).map(CreateResult.Created(id, _))

    override def update(
        album: AlbumId,
        chapter: ChapterId,
        name: DestinationName,
        countries: NonEmptyList[CountryId],
        period: Period,
        sourceFolder: PhotoSource
    ): F[UpdateResult] =
      val update = summon[Mutex[F]].lock.surround:
        val program =
          for
            current  <- readAlbum(album).eitherT.leftWiden[UpdateFailure]
            resolved <- EitherT.fromEither[F](checkUpdate(current, chapter, name, period, sourceFolder))
            changed = current.chapters.exists(value => value.id.value === chapter.value && (value.sourceFolder: String) =!= sourceFolder)
            _       <- persistUpdate(album, current.chapters, chapter, name, countries, resolved, sourceFolder).eitherT
            request <- EitherT.liftF(if changed then photos.prepare(chapter, sourceFolder).attempt.map(_.some) else None.pure[F])
          yield request
        program.value
      MonadCancelThrow[F].uncancelable: _ =>
        update.flatMap:
          case Left(failure)        => translateUpdateFailure(failure).pure[F]
          case Right(None)          => UpdateResult.Updated(Scheduling.NotRequested).pure[F]
          case Right(Some(request)) => launchImport(request).map(UpdateResult.Updated(_))

    override def refresh(album: AlbumId, chapter: ChapterId): F[RefreshResult] =
      val refresh = summon[Mutex[F]].lock.surround:
        val program =
          for
            current <- readAlbum(album).eitherT.leftWiden[UpdateFailure]
            saved   <- EitherT.fromOption[F](current.chapters.find(_.id.value === chapter.value), MissingChapter: UpdateFailure)
            request <- EitherT.liftF(photos.prepare(chapter, saved.sourceFolder).attempt)
          yield request
        program.value
      MonadCancelThrow[F].uncancelable: _ =>
        refresh.flatMap:
          case Right(request)                                        => launchImport(request).map(RefreshResult.Scheduled(_))
          case Left(Failure.AlbumNotFound)                           => RefreshResult.AlbumNotFound.pure[F]
          case Left(MissingChapter)                                  => RefreshResult.ChapterNotFound.pure[F]
          case Left(Failure.Unavailable) | Left(_: Failure.Conflict) => RefreshResult.Failed.pure[F]

    private def launchImport(request: Either[Throwable, Request]) =
      request.traverse(photos.launch).flatMap:
        case Right(scheduling) => scheduling.pure[F]
        case Left(error)       => Logger[F].error(error)("Saved chapter photo import could not be prepared").as(Scheduling.Failed)
      .handleErrorWith(error => Logger[F].error(error)("Saved chapter photo import could not be launched").as(Scheduling.Failed))

    private def readAlbum(id: AlbumId) =
      albums.get(id).map:
        case AlbumRepository.GetResult.Found(album) => album.asRight[Failure]
        case AlbumRepository.GetResult.NotFound     => Failure.AlbumNotFound.asLeft[Album]
        case _: AlbumRepository.GetResult.Failed    => Failure.Unavailable.asLeft[Album]

    private def checkCreation(current: Album, name: DestinationName, period: Period, folder: PhotoSource) =
      val resolved      = resolvePeriod(current.year, period)
      val maybeConflict =
        if current.spreads.length >= 65 then ChapterConflict.AtCapacity.some
        else findMetadataConflict(current.year, current.chapters, name, resolved, folder)
      maybeConflict.toLeft(resolved).leftMap(Failure.Conflict(_))

    private def checkUpdate(current: Album, id: ChapterId, name: DestinationName, period: Period, folder: PhotoSource) =
      current.chapters.find(_.id.value === id.value).toRight(MissingChapter: UpdateFailure).flatMap: existing =>
        val resolved      = resolvePeriod(current.year, period)
        val others        = current.chapters.filterNot(_.id.value === id.value)
        val maybeConflict =
          if existing.destination.isHome =!= ((name: String) === "Home") then ChapterConflict.TypeChange.some
          else findMetadataConflict(current.year, others, name, resolved, folder)
        maybeConflict.toLeft(resolved).leftMap(Failure.Conflict(_))

    private def findMetadataConflict(year: AlbumYear, chapters: Vector[Chapter], name: DestinationName, period: VisitPeriod, folder: PhotoSource) =
      val maybeHomeConflict = if (name: String) === "Home" then findHomeConflict(year, period, chapters.exists(_.destination.isHome)) else None
      maybeHomeConflict.orElse(Option.when(chapters.exists(value => (value.sourceFolder: String) === folder))(ChapterConflict.FolderAssigned))

    private def readChapterStartTemplate =
      templates.listChapterStartTemplates.map:
        case ListChapterStartTemplatesResult.Listed(available) =>
          val template = available.head
          (template.id, template.photoSlots, template.textSlot).asRight[Failure]
        case ListChapterStartTemplatesResult.Failed =>
          Failure.Unavailable.asLeft[(SpreadTemplateId, Vector[SlotBox], SlotBox)]

    private def persistCreation(album: AlbumId, currentChapters: Vector[Chapter], chapter: ChapterRepository.NewChapter) =
      val order = buildChapterOrder(currentChapters, chapter.id, (chapter.name: String) === "Home", chapter.period)
      chapters.create(album, chapter, order).map:
        case ChapterRepository.CreateResult.Saved            => chapter.id.asRight[Failure]
        case ChapterRepository.CreateResult.AlbumNotFound    => Failure.AlbumNotFound.asLeft[ChapterId]
        case ChapterRepository.CreateResult.UnknownCountries => Failure.Conflict(ChapterConflict.UnknownCountries).asLeft[ChapterId]
        case ChapterRepository.CreateResult.Failed           => Failure.Unavailable.asLeft[ChapterId]

    private def persistUpdate(
        album: AlbumId,
        currentChapters: Vector[Chapter],
        id: ChapterId,
        name: DestinationName,
        countries: NonEmptyList[CountryId],
        period: VisitPeriod,
        folder: PhotoSource
    ) =
      val others = currentChapters.filterNot(_.id.value === id.value)
      val order  = buildChapterOrder(others, id, (name: String) === "Home", period)
      chapters.update(album, id, name, countries, period, folder, order).map:
        case ChapterRepository.UpdateResult.Saved            => ().asRight[UpdateFailure]
        case ChapterRepository.UpdateResult.ChapterNotFound  => MissingChapter.asLeft[Unit]
        case ChapterRepository.UpdateResult.AlbumNotFound    => Failure.AlbumNotFound.asLeft[Unit]
        case ChapterRepository.UpdateResult.UnknownCountries => Failure.Conflict(ChapterConflict.UnknownCountries).asLeft[Unit]
        case ChapterRepository.UpdateResult.Failed           => Failure.Unavailable.asLeft[Unit]

    private def buildChapterOrder(chapters: Vector[Chapter], id: ChapterId, isHome: Boolean, period: VisitPeriod) =
      val others = chapters.map(value => ChapterOrder(value.id, value.destination.isHome, value.period))
      orderChapterIds(NonEmptyList(ChapterOrder(id, isHome, period), others.toList))

    private def resolvePeriod(year: AlbumYear, period: Period) = period match
      case Period.AlbumYear    => buildYearPeriod(year)
      case Period.Dates(value) => value

    private def findHomeConflict(year: AlbumYear, period: VisitPeriod, homeExists: Boolean) =
      if homeExists then ChapterConflict.HomeExists.some
      else if !arePeriodsEqual(period, buildYearPeriod(year)) then ChapterConflict.HomePeriodMismatch.some
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
        sourceFolder: PhotoSource,
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

    private def translateCreateFailure(failure: Failure) = failure match
      case Failure.AlbumNotFound    => CreateResult.AlbumNotFound
      case Failure.Conflict(reason) => CreateResult.Rejected(reason)
      case Failure.Unavailable      => CreateResult.Failed

    private def translateUpdateFailure(failure: UpdateFailure) = failure match
      case Failure.AlbumNotFound    => UpdateResult.AlbumNotFound
      case MissingChapter           => UpdateResult.ChapterNotFound
      case Failure.Conflict(reason) => UpdateResult.Rejected(reason)
      case Failure.Unavailable      => UpdateResult.Failed

  final private case class ChapterOrder(id: ChapterId, isHome: Boolean, period: VisitPeriod)

  private enum Failure:
    case AlbumNotFound, Unavailable
    case Conflict(reason: ChapterConflict)

  private case object MissingChapter
  private type UpdateFailure = Failure | MissingChapter.type

  enum CreateResult:
    case Created(id: ChapterId, scheduling: Scheduling)
    case Rejected(reason: ChapterConflict)
    case AlbumNotFound, Failed

  enum UpdateResult:
    case Updated(scheduling: Scheduling)
    case Rejected(reason: ChapterConflict)
    case ChapterNotFound, AlbumNotFound, Failed

  enum RefreshResult:
    case Scheduled(scheduling: Scheduling)
    case ChapterNotFound, AlbumNotFound, Failed

  enum Period:
    case AlbumYear
    case Dates(value: VisitPeriod)
