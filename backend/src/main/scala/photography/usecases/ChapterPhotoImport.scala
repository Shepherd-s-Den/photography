package photography.usecases

import cats.{Eq, MonadThrow}
import cats.effect.{Async, Fiber, Ref, Resource}
import cats.effect.std.{Mutex, Supervisor}
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import photography.capabilities.{IdGenerator, Transaction}
import photography.domain.album.*
import photography.ports.{PhotoRepository, PhotoStorage}
import photography.usecases.ChapterPhotoImport.*

trait ChapterPhotoImport[F[_]]:
  def prepare(chapter: ChapterId, source: PhotoSource): F[Request]
  def launch(request: Request): F[Scheduling]
  def retire(retirements: Vector[PhotoWriteIntent.Retirement]): F[Unit]
  def suspend(chapter: ChapterId): F[Unit]
  def status(chapter: ChapterId): F[ExecutionOutcome]

object ChapterPhotoImport:
  def make[F[_]: Async: IdGenerator: Logger: Mutex, Db[_]: MonadThrow](
      storage: PhotoStorage[F],
      photos: PhotoRepository[Db],
      generator: PhotoThumbnailGenerator[F]
  )(using Transaction[Db, F]): Resource[F, ChapterPhotoImport[F]] =
    val recovery     = PhotoWriteRecovery.make(photos, storage)
    val buildManager = (guarded: Transaction[Db, F]) =>
      given Transaction[Db, F] = guarded
      PhotoManager.make(storage, photos, generator)
    for
      state      <- Resource.eval(Ref.of[F, Map[ChapterId, Entry[F]]](Map.empty))
      issues     <- Resource.eval(Ref.of[F, Map[ChapterId, Vector[PhotoWriteRecovery.Issue]]](Map.empty))
      supervisor <- Supervisor[F](await = false)
    yield new LiveChapterPhotoImport(buildManager, recovery, state, issues, supervisor)

  final private class LiveChapterPhotoImport[F[_]: Async: Logger: Mutex, Db[_]](
      buildManager: Transaction[Db, F] => PhotoManager[F],
      recovery: PhotoWriteRecovery[F],
      state: Ref[F, Map[ChapterId, Entry[F]]],
      issues: Ref[F, Map[ChapterId, Vector[PhotoWriteRecovery.Issue]]],
      supervisor: Supervisor[F]
  )(using transaction: Transaction[Db, F]) extends ChapterPhotoImport[F]:
    override def prepare(chapter: ChapterId, source: PhotoSource): F[Request] =
      state.modify: current =>
        current.get(chapter) match
          case Some(entry) if (entry.request.source: String) === source && isActive(entry.status) => (current, entry.request)
          case maybePrevious                                                                      =>
            val generation = maybePrevious.fold(BigInt(0))(_.request.generation + 1)
            val request    = Request(chapter, source, generation = generation)
            val previous   = maybePrevious.toList.flatMap(entry => entry.previous ++ entry.maybeWorker.toList).toVector
            val queued     = Entry(request, Status.Queued, maybeWorker = None, previous = previous)
            (current.updated(chapter, queued), request)

    override def launch(request: Request): F[Scheduling] =
      val launch = summon[Mutex[F]].lock.surround:
        state.get.flatMap: current =>
          current.get(request.chapter) match
            case Some(entry) if entry.request === request && entry.maybeWorker.isEmpty && isActive(entry.status) =>
              supervisor.supervise(run(request)).flatMap: worker =>
                val register = state.update: current =>
                  current.updatedWith(request.chapter): maybeEntry =>
                    maybeEntry.map: active =>
                      active.copy(maybeWorker = worker.some, previous = Vector.empty[Fiber[F, Throwable, Unit]])
                register.as((Scheduling.Queued, entry.previous))
            case _ => (Scheduling.Coalesced, Vector.empty[Fiber[F, Throwable, Unit]]).pure[F]
      Async[F].uncancelable: _ =>
        launch.flatMap { case (scheduling, previous) =>
          val cancelPrevious = previous.traverse_(worker => supervisor.supervise(worker.cancel).void)
          cancelPrevious.as(scheduling)
        }.handleErrorWith: error =>
          Logger[F].error(error)("Photo import launch failed") >> recordStatus(request, Status.LaunchFailed).as(Scheduling.Failed)

    override def retire(retirements: Vector[PhotoWriteIntent.Retirement]): F[Unit] =
      Async[F].uncancelable: _ =>
        retirements.traverse_(intent => supervisor.supervise(retirePhoto(intent)).void)

    override def suspend(chapter: ChapterId): F[Unit] =
      state.update: current =>
        current.updatedWith(chapter)(_.map(_.copy(status = Status.Uncertain)))

    override def status(chapter: ChapterId): F[ExecutionOutcome] =
      (state.get, issues.get).mapN: (entries, failures) =>
        ExecutionOutcome(entries.get(chapter).fold[Status](Status.Idle)(_.status), retirementIssues = failures.getOrElse(chapter, Vector.empty))

    private def run(request: Request) =
      val guarded = new Transaction[Db, F]:
        override def run[A](program: Db[A]): F[A] =
          summon[Mutex[F]].lock.surround:
            state.get.flatMap: current =>
              val isCurrent = current.get(request.chapter).exists(entry => entry.request === request && isActive(entry.status))
              if isCurrent then transaction.run(program)
              else Async[F].raiseError(new IllegalStateException("Photo import source was superseded"))
        override def resolve[A](program: Db[A]): F[A] = transaction.resolve(program)
      val manager = buildManager(guarded)
      val started = summon[Mutex[F]].lock.surround(recordStatus(request, Status.Running))
      started >> manager.refresh(request.chapter, request.source).flatMap(result => recordStatus(request, Status.Finished(result)))

    private def retirePhoto(intent: PhotoWriteIntent.Retirement) =
      recovery.resolve(intent).flatMap:
        case PhotoWriteRecovery.RecoveryResult.Recovered        => ().pure[F]
        case PhotoWriteRecovery.RecoveryResult.Failed(failures) =>
          issues.update(current =>
            current.updated(intent.chapter, current.getOrElse(intent.chapter, Vector.empty[PhotoWriteRecovery.Issue]) ++ failures)
          )

    private def recordStatus(request: Request, status: Status) =
      state.update: current =>
        current.updatedWith(request.chapter): maybeEntry =>
          maybeEntry.map(entry => if entry.request === request && isActive(entry.status) then entry.copy(status = status) else entry)

    private def isActive(status: Status) = status match
      case Status.Queued | Status.Running => true
      case _                              => false

  final case class Request(chapter: ChapterId, source: PhotoSource, generation: BigInt)
  object Request:
    given Eq[Request] = Eq.by(request => (request.chapter.value, request.source: String, request.generation))

  enum Scheduling:
    case Queued, Coalesced, NotRequested, Failed

  enum Status:
    case Idle, Queued, Running, LaunchFailed, Uncertain
    case Finished(result: PhotoManager.RefreshResult)

  final case class ExecutionOutcome(status: Status, retirementIssues: Vector[PhotoWriteRecovery.Issue])

  final private case class Entry[F[_]](
      request: Request,
      status: Status,
      maybeWorker: Option[Fiber[F, Throwable, Unit]],
      previous: Vector[Fiber[F, Throwable, Unit]]
  )
