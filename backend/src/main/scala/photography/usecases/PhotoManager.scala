package photography.usecases

import cats.effect.{Async, Ref, Resource}
import cats.effect.std.{Mutex, Supervisor}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.constraint.numeric.GreaterEqual
import fs2.Stream
import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.Logger
import photography.capabilities.{IdGenerator, Transaction}
import photography.domain.album.*
import photography.ports.{ChapterRepository, PhotoFileSystem, PhotoPlacementRepository, PhotoRepository}
import photography.usecases.PhotoManager.*

trait PhotoManager[F[_]]:
  def refresh(chapter: ChapterId): F[RefreshResult]
  def listChapter(chapter: ChapterId): F[InventoryResult]
  def subscribeToChanges(album: AlbumId): Stream[F, PhotoEvent]

object PhotoManager:
  def make[F[_]: Async: Logger: IdGenerator: Mutex: Transaction](
      chapters: ChapterRepository.PhotoReader[F],
      files: PhotoFileSystem[F],
      photos: PhotoRepository[F],
      placements: PhotoPlacementRepository[F],
      generator: PhotoThumbnailGenerator[F],
      publication: PhotoPublication[F],
      epochs: Ref[F, Map[ChapterId, BigInt]],
      quality: ThumbnailQuality
  ): Resource[F, PhotoManager[F]] =
    for
      workers     <- Supervisor[F]
      work        <- Resource.eval(Ref.of[F, Map[ChapterId, Work]](Map.empty))
      subscribers <- Resource.eval(Ref.of[F, Map[AlbumId, Set[SignallingRef[F, Set[ChapterId]]]]](Map.empty))
    yield new LivePhotoManager(chapters, files, photos, placements, generator, publication, epochs, quality, workers, work, subscribers)

  final private class LivePhotoManager[F[_]: Async: Logger: IdGenerator: Mutex: Transaction](
      chapters: ChapterRepository.PhotoReader[F],
      files: PhotoFileSystem[F],
      photos: PhotoRepository[F],
      placements: PhotoPlacementRepository[F],
      generator: PhotoThumbnailGenerator[F],
      publication: PhotoPublication[F],
      epochs: Ref[F, Map[ChapterId, BigInt]],
      quality: ThumbnailQuality,
      workers: Supervisor[F],
      work: Ref[F, Map[ChapterId, Work]],
      subscribers: Ref[F, Map[AlbumId, Set[SignallingRef[F, Set[ChapterId]]]]]
  ) extends PhotoManager[F]:
    override def refresh(chapter: ChapterId): F[RefreshResult] =
      summon[Mutex[F]].lock.surround:
        chapters.findPhotoContext(chapter).flatMap:
          case ChapterRepository.PhotoReader.FindResult.Found(album, source) => enqueue(chapter, album, source)
          case ChapterRepository.PhotoReader.FindResult.NotFound             => RefreshResult.ChapterNotFound.pure[F]
          case ChapterRepository.PhotoReader.FindResult.Failed               => RefreshResult.Failed.pure[F]

    override def listChapter(chapter: ChapterId): F[InventoryResult] =
      summon[Mutex[F]].lock.surround:
        chapters.findPhotoContext(chapter).flatMap:
          case ChapterRepository.PhotoReader.FindResult.Found(_, _) => readInventory(chapter)
          case ChapterRepository.PhotoReader.FindResult.NotFound    => InventoryResult.NotFound.pure[F]
          case ChapterRepository.PhotoReader.FindResult.Failed      => InventoryResult.Failed.pure[F]

    override def subscribeToChanges(album: AlbumId): Stream[F, PhotoEvent] =
      val subscribe = SignallingRef[F, Set[ChapterId]](Set.empty).flatTap { signal =>
        subscribers.update(values => values.updated(album, values.getOrElse(album, Set.empty) + signal))
      }
      val registered = Resource.make(subscribe)(signal => subscribers.update(_.updatedWith(album)(_.map(_ - signal).filter(_.nonEmpty))))
      Stream.resource(registered).flatMap: signal =>
        val changes = signal.discrete.filter(_.nonEmpty).evalMap(_ => signal.getAndSet(Set.empty))
          .flatMap(ids => Stream.emits(ids.toVector.sortBy(_.value.toString)))
        Stream.emit(PhotoEvent.Subscribed) ++ changes.map(PhotoEvent.InventoryChanged(_))

    private def enqueue(chapter: ChapterId, album: AlbumId, source: PhotoSource) =
      for
        epoch   <- epochs.get.map(_.getOrElse(chapter, BigInt(0)))
        current <- work.get
        result  <- current.get(chapter) match
          case Some(value) if value.epoch === epoch && (value.source: String) === (source: String) && value.processing.isActive =>
            RefreshResult.Coalesced.pure[F]
          case _ => start(chapter, Work(album, source, epoch, Processing(ProcessingState.Queued, unsupportedCount = 0, issues = Vector.empty[Issue])))
      yield result

    private def start(chapter: ChapterId, queued: Work) =
      val run = process(chapter, queued).handleErrorWith { error =>
        Logger[F].error(error)("Photo reconciliation failed") >> issue(chapter, queued.epoch, Issue.Unexpected)
      }
      Async[F].uncancelable: _ =>
        for
          _         <- work.update(_.updated(chapter, queued))
          _         <- notifyChapter(queued.album, chapter)
          scheduled <- workers.supervise(run.guarantee(finish(chapter, queued.epoch))).attempt
          result    <- scheduled.fold(error => failScheduling(chapter, queued.epoch, error), _ => RefreshResult.Queued.pure[F])
        yield result

    private def failScheduling(chapter: ChapterId, epoch: BigInt, error: Throwable) =
      Logger[F].error(error)("Photo scheduling failed") >> issue(chapter, epoch, Issue.Scheduling) >> finish(chapter, epoch).as(RefreshResult.Failed)

    private def process(chapter: ChapterId, queued: Work) =
      update(chapter, queued.epoch)(_.copy(state = ProcessingState.Running)) >>
        files.discoverOriginals(queued.source).flatMap:
          case PhotoFileSystem.DiscoveryResult.Complete(originals, unsupported) =>
            update(chapter, queued.epoch)(_.copy(unsupportedCount = unsupported)) >> reconcile(chapter, queued.epoch, originals)
          case PhotoFileSystem.DiscoveryResult.Failed => issue(chapter, queued.epoch, Issue.Discovery)

    private def reconcile(chapter: ChapterId, epoch: BigInt, originals: Vector[OriginalPhotoLocation]) =
      photos.listChapter(chapter).flatMap:
        case PhotoRepository.ListResult.Listed(existing) =>
          val present = originals.toSet
          val removed = existing.filterNot(photo => present.contains(photo.original))
          removed.traverse_(photo => retire(photo, epoch)) >>
            originals.traverse_(original => readOriginal(chapter, epoch, original, existing.find(_.original === original)))
        case PhotoRepository.ListResult.Failed => issue(chapter, epoch, Issue.Persistence)

    private def retire(photo: Photo, epoch: BigInt) =
      publication.retire(photo.chapter, photo.id, photo.thumbnail.location, epoch).flatMap:
        case PhotoPublication.RetireResult.Retired        => update(photo.chapter, epoch)(identity)
        case PhotoPublication.RetireResult.Superseded     => ().pure[F]
        case PhotoPublication.RetireResult.Failed(reason) => issue(photo.chapter, epoch, Issue.Publication(reason, maybeFilename = None))

    private def readOriginal(chapter: ChapterId, epoch: BigInt, original: OriginalPhotoLocation, maybePrevious: Option[Photo]) =
      files.readOriginal(original).flatMap:
        case PhotoFileSystem.ReadResult.Read(content, metadata) =>
          val unchanged =
            maybePrevious.exists(photo => (photo.metadata.hash: String) === (metadata.hash: String) && photo.thumbnail.quality === quality)
          if unchanged then ().pure[F]
          else buildDraft(chapter, original, metadata, maybePrevious).flatMap(draft => generate(draft, content, epoch))
        case PhotoFileSystem.ReadResult.Undecodable => issue(chapter, epoch, Issue.Undecodable(original.filename))
        case PhotoFileSystem.ReadResult.Unstable    => issue(chapter, epoch, Issue.Unstable(original.filename))
        case PhotoFileSystem.ReadResult.Failed      => issue(chapter, epoch, Issue.Read(original.filename))

    private def generate(draft: PhotoPublication.Draft, content: PhotoContent, epoch: BigInt) =
      generator.generate(content).flatMap:
        case PhotoThumbnailGenerator.GenerationResult.Generated(image) => publish(draft, image, epoch)
        case PhotoThumbnailGenerator.GenerationResult.Failed           => issue(draft.chapter, epoch, Issue.Generation(draft.original.filename))

    private def publish(draft: PhotoPublication.Draft, image: EncodedThumbnail, epoch: BigInt) =
      summon[Mutex[F]].lock.surround(epochs.get.map(_.getOrElse(draft.chapter, BigInt(0)))).flatMap: current =>
        if current =!= epoch then ().pure[F]
        else
          publication.publish(draft, image, epoch).flatMap:
            case PhotoPublication.PublishResult.Published      => update(draft.chapter, epoch)(identity)
            case PhotoPublication.PublishResult.Superseded     => ().pure[F]
            case PhotoPublication.PublishResult.Failed(reason) => issue(draft.chapter, epoch, Issue.Publication(reason, draft.original.filename.some))

    private def buildDraft(chapter: ChapterId, original: OriginalPhotoLocation, metadata: PhotoMetadata, maybePrevious: Option[Photo]) =
      maybePrevious.fold(IdGenerator[F].generateId.map(PhotoId(_)))(photo => photo.id.pure[F]).map: id =>
        PhotoPublication.Draft(id, chapter, original, metadata, maybePrevious.flatMap(_.maybeCaption), maybePrevious.map(_.thumbnail.location))

    private def readInventory(chapter: ChapterId) =
      val snapshot =
        for
          listed <- photos.listChapter(chapter)
          placed <- placements.findPlacedPhotos(chapter)
        yield (listed, placed) match
          case (PhotoRepository.ListResult.Listed(values), PhotoPlacementRepository.FindResult.Found(ids)) =>
            Transaction.Decision.Commit[Unit, (Vector[Photo], Set[PhotoId])]((values.sorted, ids))
          case _ => Transaction.Decision.Reject[Unit, (Vector[Photo], Set[PhotoId])](())
      Transaction[F].atomically(snapshot).flatMap:
        case Transaction.Result.Committed((values, ids)) =>
          for
            epoch   <- epochs.get.map(_.getOrElse(chapter, BigInt(0)))
            current <- work.get
          yield InventoryResult.Found(Inventory(values, ids, current.get(chapter).filter(_.epoch === epoch).fold(Processing.idle)(_.processing)))
        case Transaction.Result.Rejected(_) | Transaction.Result.Failed => InventoryResult.Failed.pure[F]

    private def issue(chapter: ChapterId, epoch: BigInt, value: Issue) =
      val warn = Logger[F].warn(s"Photo reconciliation issue for ${chapter.value}: $value")
      warn >> update(chapter, epoch)(state => state.copy(issues = state.issues :+ value))

    private def finish(chapter: ChapterId, epoch: BigInt) =
      update(chapter, epoch) { state =>
        state.copy(state = if state.issues.exists(_.isFailure) then ProcessingState.Failed else ProcessingState.Completed)
      }

    private def update(chapter: ChapterId, epoch: BigInt)(change: Processing => Processing) =
      epochs.get.flatMap: revisions =>
        work.modify { current =>
          current.get(chapter) match
            case Some(value) if value.epoch === epoch && revisions.getOrElse(chapter, BigInt(0)) === epoch =>
              (current.updated(chapter, value.copy(processing = change(value.processing))), value.album.some)
            case _ => (current, None)
        }.flatMap(_.traverse_(album => notifyChapter(album, chapter)))

    private def notifyChapter(album: AlbumId, chapter: ChapterId) =
      subscribers.get.flatMap(_.getOrElse(album, Set.empty).toVector.traverse_(_.update(_ + chapter)))

  enum RefreshResult:
    case Queued, Coalesced, ChapterNotFound, Failed

  enum InventoryResult:
    case Found(value: Inventory)
    case NotFound, Failed

  final case class Inventory(photos: Vector[Photo], placed: Set[PhotoId], processing: Processing):
    def total: Int    = photos.size
    def unplaced: Int = photos.count(photo => !placed.contains(photo.id))

  enum PhotoEvent:
    case Subscribed
    case InventoryChanged(chapter: ChapterId)

  enum ProcessingState:
    case Idle, Queued, Running, Completed, Failed

  final case class Processing(state: ProcessingState, unsupportedCount: Int :| GreaterEqual[0], issues: Vector[Issue]):
    def skippedCount: Int = issues.count(issue => !issue.isFailure)
    def isActive: Boolean = state match
      case ProcessingState.Queued | ProcessingState.Running => true
      case _                                                => false

  object Processing:
    val idle: Processing = Processing(ProcessingState.Idle, unsupportedCount = 0, issues = Vector.empty[Issue])

  enum Issue:
    case Discovery, Persistence, Scheduling, Unexpected
    case Read(filename: String)
    case Generation(filename: String)
    case Undecodable(filename: String)
    case Unstable(filename: String)
    case Publication(reason: PhotoPublication.Failure, maybeFilename: Option[String])

    def isFailure: Boolean = this match
      case Undecodable(_) | Unstable(_) => false
      case _                            => true

  final private case class Work(album: AlbumId, source: PhotoSource, epoch: BigInt, processing: Processing)
