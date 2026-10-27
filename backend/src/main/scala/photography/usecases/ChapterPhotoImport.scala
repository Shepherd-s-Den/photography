package photography.usecases

import cats.Eq
import cats.effect.{Async, Fiber, Ref, Resource}
import cats.effect.std.{Mutex, Supervisor}
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import photography.capabilities.{IdGenerator, Transaction}
import photography.domain.album.*
import photography.ports.{PhotoRepository, PhotoStorage}
import photography.usecases.ChapterPhotoImport.*

trait ChapterPhotoImport[F[_]]:
  /** Register saved metadata while holding the chapter-mutation mutex; launch after releasing it. */
  def prepare(chapter: ChapterId, source: PhotoSource): F[Request]
  def launch(request: Request): F[Scheduling]
  def status(chapter: ChapterId): F[Status]

object ChapterPhotoImport:
  def make[F[_]: Async: IdGenerator: Logger: Mutex, Db[_]](
      storage: PhotoStorage[F],
      photos: PhotoRepository[Db],
      generator: PhotoThumbnailGenerator[F]
  )(using Transaction[Db, F]): Resource[F, ChapterPhotoImport[F]] =
    for
      state      <- Resource.eval(Ref.of[F, Map[ChapterId, Entry[F]]](Map.empty))
      supervisor <- Supervisor[F](await = false)
    yield new LiveChapterPhotoImport(storage, photos, generator, state, supervisor)

  final private class LiveChapterPhotoImport[F[_]: Async: IdGenerator: Logger: Mutex, Db[_]](
      storage: PhotoStorage[F],
      photos: PhotoRepository[Db],
      generator: PhotoThumbnailGenerator[F],
      state: Ref[F, Map[ChapterId, Entry[F]]],
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
            (current.updated(chapter, Entry(request, Status.Queued, maybeWorker = None, previous = previous)), request)

    override def launch(request: Request): F[Scheduling] =
      val launch = summon[Mutex[F]].lock.surround:
        state.get.flatMap: current =>
          current.get(request.chapter) match
            case Some(entry) if entry.request === request && entry.maybeWorker.isEmpty && isActive(entry.status) =>
              supervisor.supervise(run(request)).flatMap: worker =>
                state.update(_.updatedWith(request.chapter)(_.map(_.copy(
                  maybeWorker = worker.some,
                  previous = Vector.empty[Fiber[F, Throwable, Unit]]
                ))))
                  .as((Scheduling.Queued, entry.previous))
            case _ => (Scheduling.Coalesced, Vector.empty[Fiber[F, Throwable, Unit]]).pure[F]
      Async[F].uncancelable: _ =>
        launch.flatMap { case (scheduling, previous) => previous.traverse_(worker => supervisor.supervise(worker.cancel).void).as(scheduling) }
          .handleErrorWith: error =>
            Logger[F].error(error)("Photo import launch failed") >> recordStatus(request, Status.LaunchFailed).as(Scheduling.Failed)

    override def status(chapter: ChapterId): F[Status] =
      state.get.map(_.get(chapter).fold[Status](Status.Idle)(_.status))

    private def run(request: Request) =
      given Transaction[Db, F] with
        override def run[A](program: Db[A]): F[A] =
          summon[Mutex[F]].lock.surround:
            state.get.flatMap: current =>
              if current.get(request.chapter).exists(_.request === request) then transaction.run(program)
              else Async[F].raiseError(new IllegalStateException("Photo import source was superseded"))
      val manager = PhotoManager.make(storage, photos, generator)
      val started = summon[Mutex[F]].lock.surround(recordStatus(request, Status.Running))
      started >> manager.refresh(request.chapter, request.source).flatMap(result => recordStatus(request, Status.Finished(result)))

    private def recordStatus(request: Request, status: Status) =
      state.update: current =>
        current.updatedWith(request.chapter):
          _.map(entry => if entry.request === request then entry.copy(status = status) else entry)

    private def isActive(status: Status) = status match
      case Status.Queued | Status.Running => true
      case _                              => false

  final case class Request(chapter: ChapterId, source: PhotoSource, generation: BigInt)
  object Request:
    given Eq[Request] = Eq.by(request => (request.chapter.value, request.source: String, request.generation))

  enum Scheduling:
    case Queued, Coalesced, NotRequested, Failed

  enum Status:
    case Idle, Queued, Running, LaunchFailed
    case Finished(result: PhotoManager.RefreshResult)

  final private case class Entry[F[_]](
      request: Request,
      status: Status,
      maybeWorker: Option[Fiber[F, Throwable, Unit]],
      previous: Vector[Fiber[F, Throwable, Unit]]
  )
