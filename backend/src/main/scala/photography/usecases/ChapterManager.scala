package photography.usecases

import cats.data.{EitherT, NonEmptyList}
import cats.MonadThrow
import cats.effect.kernel.MonadCancelThrow
import cats.effect.std.Mutex
import cats.syntax.all.*
import photography.capabilities.{IdGenerator, Transaction}
import org.typelevel.log4cats.Logger
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.{AlbumRepository, ChapterRepository, PhotoRepository}
import photography.syntax.EitherSyntax.*
import photography.syntax.EitherSyntax.Pure.*
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
  def make[F[_]: MonadCancelThrow: IdGenerator: Mutex: Logger, Db[_]: MonadThrow](
      albums: AlbumRepository[F],
      chapters: ChapterRepository[Db],
      templates: SpreadTemplateManager[F],
      photos: ChapterPhotoImport[F],
      photoRepository: PhotoRepository[Db]
  )(using Transaction[Db, F]): ChapterManager[F] =
    new LiveChapterManager(albums, chapters, templates, photos, photoRepository)

  final private class LiveChapterManager[F[_]: MonadCancelThrow: IdGenerator: Mutex: Logger, Db[_]: MonadThrow](
      albums: AlbumRepository[F],
      chapters: ChapterRepository[Db],
      templates: SpreadTemplateManager[F],
      photos: ChapterPhotoImport[F],
      photoRepository: PhotoRepository[Db]
  )(using Transaction[Db, F]) extends ChapterManager[F]:
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
            resolved                         <- checkCreation(current, name, period, sourceFolder).eitherT[F]
            (template, photoSlots, textSlot) <- readChapterStartTemplate.eitherT
            chapter <- buildChapter(name, countries = countries, resolved, sourceFolder, template, photoSlots = photoSlots, textSlot).lift[Failure]
            id      <- persistCreation(album, current.chapters, chapter).eitherT
          yield id
        program.value.flatMap: outcome =>
          outcome.traverse(id => photos.prepare(id, sourceFolder).attempt.tupleLeft(id))
      MonadCancelThrow[F].uncancelable: _ =>
        creation.flatMap:
          case Left(failure)        => translateCreateFailure(failure).pure[F]
          case Right((id, request)) => launchImport(request, retirements = Vector.empty[PhotoWriteIntent.Retirement]).map(CreateResult.Created(id, _))

    override def update(
        album: AlbumId,
        chapter: ChapterId,
        name: DestinationName,
        countries: NonEmptyList[CountryId],
        period: Period,
        sourceFolder: PhotoSource
    ): F[UpdateResult] =
      val update = summon[Mutex[F]].lock.surround:
        val program: EitherT[F, UpdateFailure, Option[ImportLaunch]] =
          for
            current     <- readAlbum(album).eitherT.leftWiden[UpdateFailure]
            plan        <- planUpdate(current, chapter, name, period, sourceFolder).eitherT[F]
            retirements <- prepareRetirements(chapter, sourceChanged = plan.sourceChanged).lift[UpdateFailure]
            command <- allocateUpdate(album, chapter, name, countries = countries, plan.period, sourceFolder, order = plan.order).lift[UpdateFailure]
            _       <- persistUpdate(plan.previousDestination, command, retirements = retirements).eitherT
            request <- prepareImport(chapter, sourceFolder, sourceChanged = plan.sourceChanged).lift[UpdateFailure]
          yield request.map(value => ImportLaunch(value, retirements = retirements))
        program.value
      MonadCancelThrow[F].uncancelable: _ =>
        update.flatMap:
          case Left(failure)       => translateUpdateFailure(failure).pure[F]
          case Right(None)         => UpdateResult.Updated(Scheduling.NotRequested).pure[F]
          case Right(Some(launch)) => launchImport(launch.request, retirements = launch.retirements).map(UpdateResult.Updated(_))
      .handleErrorWith(error => Logger[F].error(error)("Chapter update failed").as(UpdateResult.Failed))

    override def refresh(album: AlbumId, chapter: ChapterId): F[RefreshResult] =
      val refresh = summon[Mutex[F]].lock.surround:
        val program =
          for
            current <- readAlbum(album).eitherT.leftWiden[UpdateFailure]
            saved   <- current.chapters.find(_.id.value === chapter.value).toRight(MissingChapter: UpdateFailure).eitherT[F]
            request <- photos.prepare(chapter, saved.sourceFolder).attempt.lift[UpdateFailure]
          yield request
        program.value
      MonadCancelThrow[F].uncancelable: _ =>
        refresh.flatMap:
          case Right(request) => launchImport(request, retirements = Vector.empty[PhotoWriteIntent.Retirement]).map(RefreshResult.Scheduled(_))
          case Left(Failure.AlbumNotFound)                           => RefreshResult.AlbumNotFound.pure[F]
          case Left(MissingChapter)                                  => RefreshResult.ChapterNotFound.pure[F]
          case Left(Failure.Unavailable) | Left(_: Failure.Conflict) => RefreshResult.Failed.pure[F]

    private def launchImport(request: Either[Throwable, Request], retirements: Vector[PhotoWriteIntent.Retirement]) =
      request.traverse(value => photos.launch(value, retirements = retirements)).flatMap:
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

    private def planUpdate(current: Album, id: ChapterId, name: DestinationName, period: Period, folder: PhotoSource) =
      current.chapters.find(_.id.value === id.value).toRight(MissingChapter: UpdateFailure).flatMap: existing =>
        val resolved      = resolvePeriod(current.year, period)
        val others        = current.chapters.filterNot(_.id.value === id.value)
        val maybeConflict =
          if existing.destination.isHome =!= ((name: String) === "Home") then ChapterConflict.TypeChange.some
          else findMetadataConflict(current.year, others, name, resolved, folder)
        val sourceChanged = (existing.sourceFolder: String) =!= folder
        val order         = buildChapterOrder(chapters = current.chapters, id, isHome = (name: String) === "Home", resolved)
        val plan          = UpdatePlan(existing.destination.id, sourceChanged = sourceChanged, resolved, order = order)
        maybeConflict.toLeft(plan).leftMap(Failure.Conflict(_))

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
      val order = buildChapterOrder(chapters = currentChapters, chapter.id, isHome = (chapter.name: String) === "Home", chapter.period)
      Transaction[Db, F].run(chapters.create(album, chapter, order = order)).map:
        case ChapterRepository.CreateResult.Saved            => chapter.id.asRight[Failure]
        case ChapterRepository.CreateResult.AlbumNotFound    => Failure.AlbumNotFound.asLeft[ChapterId]
        case ChapterRepository.CreateResult.UnknownCountries => Failure.Conflict(ChapterConflict.UnknownCountries).asLeft[ChapterId]
        case ChapterRepository.CreateResult.Failed           => Failure.Unavailable.asLeft[ChapterId]
      .handleErrorWith(error => Logger[F].error(error)("Chapter creation failed").as(Failure.Unavailable.asLeft[ChapterId]))

    private def persistUpdate(
        previousDestination: DestinationId,
        command: ChapterRepository.Update,
        retirements: Vector[PhotoWriteIntent.Retirement]
    ) =
      val update = chapters.update(command).flatTap:
        case ChapterRepository.UpdateResult.Saved => retirements.traverse_(removePhoto)
        case _                                    => ().pure[Db]
      val commit = Transaction[Db, F].run(update).handleErrorWith: error =>
        resolveUpdate(command.album, command.chapter, previousDestination, command.destination, error)
      commit.map:
        case ChapterRepository.UpdateResult.Saved            => ().asRight[UpdateFailure]
        case ChapterRepository.UpdateResult.ChapterNotFound  => MissingChapter.asLeft[Unit]
        case ChapterRepository.UpdateResult.AlbumNotFound    => Failure.AlbumNotFound.asLeft[Unit]
        case ChapterRepository.UpdateResult.UnknownCountries => Failure.Conflict(ChapterConflict.UnknownCountries).asLeft[Unit]
        case ChapterRepository.UpdateResult.Failed           => Failure.Unavailable.asLeft[Unit]

    private def prepareRetirements(chapter: ChapterId, sourceChanged: Boolean) =
      if !sourceChanged then Vector.empty[PhotoWriteIntent.Retirement].pure[F]
      else
        Transaction[Db, F].run(photoRepository.listChapter(chapter)).flatMap:
          case PhotoRepository.ListResult.Listed(current) => current.traverse(allocateRetirement)
          case PhotoRepository.ListResult.Failed          =>
            new IllegalStateException("Chapter photo listing failed").raiseError[F, Vector[PhotoWriteIntent.Retirement]]

    private def allocateRetirement(photo: Photo) =
      IdGenerator[F].generateId.map: id =>
        PhotoWriteIntent.Retirement(PhotoWriteIntentId(id), photo.chapter, photo.id, photo.thumbnail.location): PhotoWriteIntent.Retirement

    private def allocateUpdate(
        album: AlbumId,
        chapter: ChapterId,
        name: DestinationName,
        countries: NonEmptyList[CountryId],
        period: VisitPeriod,
        folder: PhotoSource,
        order: NonEmptyList[ChapterId]
    ) =
      IdGenerator[F].generateId.map: id =>
        ChapterRepository.Update(album, chapter, DestinationId(id), name, countries = countries, period, folder, order = order)

    private def removePhoto(intent: PhotoWriteIntent.Retirement) =
      val record = photoRepository.recordWrite(intent).flatMap:
        case PhotoRepository.RecordWriteResult.Recorded => ().pure[Db]
        case PhotoRepository.RecordWriteResult.Failed   => new IllegalStateException("Chapter retirement evidence rejected").raiseError[Db, Unit]
      record >> photoRepository.remove(intent.photo).flatMap:
        case PhotoRepository.WriteResult.Applied => ().pure[Db]
        case PhotoRepository.WriteResult.Failed  => new IllegalStateException("Chapter photo removal rejected").raiseError[Db, Unit]

    private def prepareImport(chapter: ChapterId, source: PhotoSource, sourceChanged: Boolean) =
      if !sourceChanged then none[Either[Throwable, Request]].pure[F]
      else
        photos.prepare(chapter, source).attempt.flatTap:
          case Left(error)                  => Logger[F].error(error)("Committed source import preparation failed") >> photos.suspend(chapter)
          case _: Right[Throwable, Request] => ().pure[F]
        .map(_.some)

    private def resolveUpdate(album: AlbumId, chapter: ChapterId, previousDestination: DestinationId, destination: DestinationId, error: Throwable) =
      Logger[F].error(error)("Chapter update commit acknowledgement failed") >> albums.get(album).flatMap:
        case AlbumRepository.GetResult.Found(current) =>
          current.chapters.find(_.id.value === chapter.value) match
            case Some(saved) if saved.destination.id.value === destination.value         => ChapterRepository.UpdateResult.Saved.pure[F]
            case Some(saved) if saved.destination.id.value === previousDestination.value => ChapterRepository.UpdateResult.Failed.pure[F]
            case _ => photos.suspend(chapter).as(ChapterRepository.UpdateResult.Failed)
        case _ => photos.suspend(chapter).as(ChapterRepository.UpdateResult.Failed)
      .handleErrorWith: failure =>
        Logger[F].error(failure)("Chapter update commit resolution failed") >> photos.suspend(chapter).as(ChapterRepository.UpdateResult.Failed)

    private def buildChapterOrder(chapters: Vector[Chapter], id: ChapterId, isHome: Boolean, period: VisitPeriod) =
      val others = chapters.filterNot(_.id.value === id.value).map(value => ChapterOrder(value.id, isHome = value.destination.isHome, value.period))
      orderChapterIds(chapters = NonEmptyList(ChapterOrder(id, isHome = isHome, period), tail = others.toList))

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
  final private case class UpdatePlan(
      previousDestination: DestinationId,
      sourceChanged: Boolean,
      period: VisitPeriod,
      order: NonEmptyList[ChapterId]
  )
  final private case class ImportLaunch(request: Either[Throwable, Request], retirements: Vector[PhotoWriteIntent.Retirement])

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
