package photography.usecases.gear

import cats.effect.kernel.MonadCancelThrow
import cats.effect.std.Mutex
import cats.syntax.all.*
import photography.capabilities.IdGenerator
import photography.domain.gear.*
import photography.ports.gear.CameraRepository
import photography.usecases.gear.CameraManager.*

trait CameraManager[F[_]]:
  def create(code: CameraExifCode, name: CameraName, kind: CameraKind): F[CreateResult]
  def update(camera: Camera): F[UpdateResult]
  def delete(id: CameraId): F[DeleteResult]

object CameraManager:
  def make[F[_]: MonadCancelThrow: Mutex: IdGenerator](repository: CameraRepository[F]): CameraManager[F] = new LiveCameraManager(repository)

  final private class LiveCameraManager[F[_]: MonadCancelThrow: Mutex: IdGenerator](repository: CameraRepository[F]) extends CameraManager[F]:
    override def create(code: CameraExifCode, name: CameraName, kind: CameraKind): F[CreateResult] =
      IdGenerator[F].generateId.flatMap: id =>
        val camera = Camera(CameraId(id), code, name, kind)
        repository.create(camera).map:
          case CameraRepository.CreateResult.Created   => CreateResult.Created(camera)
          case CameraRepository.CreateResult.Conflict  => CreateResult.Conflict
          case CameraRepository.CreateResult.Failed    => CreateResult.Failed
          case CameraRepository.CreateResult.Uncertain => CreateResult.Uncertain

    override def update(camera: Camera): F[UpdateResult] =
      MonadCancelThrow[F].uncancelable: _ =>
        summon[Mutex[F]].lock.surround:
          repository.readReferences(camera.id).flatMap:
            case CameraRepository.ReferencesResult.NotFound     => UpdateResult.NotFound.pure[F]
            case CameraRepository.ReferencesResult.Failed       => UpdateResult.Failed.pure[F]
            case CameraRepository.ReferencesResult.Found(usage) =>
              if preservesSelections(camera.kind, usage) then
                repository.update(camera).map:
                  case CameraRepository.UpdateResult.Updated   => UpdateResult.Updated
                  case CameraRepository.UpdateResult.NotFound  => UpdateResult.NotFound
                  case CameraRepository.UpdateResult.Conflict  => UpdateResult.Conflict
                  case CameraRepository.UpdateResult.Failed    => UpdateResult.Failed
                  case CameraRepository.UpdateResult.Uncertain => UpdateResult.Uncertain
              else UpdateResult.InvalidatesSelection.pure[F]

    override def delete(id: CameraId): F[DeleteResult] =
      MonadCancelThrow[F].uncancelable: _ =>
        summon[Mutex[F]].lock.surround:
          repository.delete(id).map:
            case CameraRepository.DeleteResult.Deleted   => DeleteResult.Deleted
            case CameraRepository.DeleteResult.NotFound  => DeleteResult.NotFound
            case CameraRepository.DeleteResult.Used      => DeleteResult.Used
            case CameraRepository.DeleteResult.Failed    => DeleteResult.Failed
            case CameraRepository.DeleteResult.Uncertain => DeleteResult.Uncertain

    private def preservesSelections(kind: CameraKind, usage: CameraRepository.Usage) = usage match
      case CameraRepository.Usage.Unused    => true
      case CameraRepository.Usage.FixedLens => kind match
          case CameraKind.FixedLens              => true
          case _: CameraKind.InterchangeableLens => false
      case CameraRepository.Usage.Interchangeable(lenses) => lenses.forall(lens => kind.accepts(lens.mount, coverage = lens.coverage))

  enum CreateResult:
    case Created(camera: Camera)
    case Conflict, Failed, Uncertain

  enum UpdateResult:
    case Updated, NotFound, Conflict, InvalidatesSelection, Failed, Uncertain

  enum DeleteResult:
    case Deleted, NotFound, Used, Failed, Uncertain
