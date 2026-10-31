package photography.usecases.gear

import cats.effect.kernel.MonadCancelThrow
import cats.effect.std.Mutex
import cats.syntax.all.*
import photography.capabilities.IdGenerator
import photography.domain.gear.*
import photography.ports.gear.LensRepository
import photography.usecases.gear.LensManager.*

trait LensManager[F[_]]:
  def create(code: LensExifCode, name: LensName, mount: LensMount, coverage: SensorFormat): F[CreateResult]
  def update(lens: Lens): F[UpdateResult]
  def delete(id: LensId): F[DeleteResult]

object LensManager:
  def make[F[_]: MonadCancelThrow: Mutex: IdGenerator](repository: LensRepository[F]): LensManager[F] = new LiveLensManager(repository)

  final private class LiveLensManager[F[_]: MonadCancelThrow: Mutex: IdGenerator](repository: LensRepository[F]) extends LensManager[F]:
    override def create(code: LensExifCode, name: LensName, mount: LensMount, coverage: SensorFormat): F[CreateResult] =
      IdGenerator[F].generateId.flatMap: id =>
        val lens = Lens(LensId(id), code, name, mount = mount, coverage = coverage)
        repository.create(lens).map:
          case LensRepository.CreateResult.Created   => CreateResult.Created(lens)
          case LensRepository.CreateResult.Conflict  => CreateResult.Conflict
          case LensRepository.CreateResult.Failed    => CreateResult.Failed
          case LensRepository.CreateResult.Uncertain => CreateResult.Uncertain

    override def update(lens: Lens): F[UpdateResult] =
      MonadCancelThrow[F].uncancelable: _ =>
        summon[Mutex[F]].lock.surround:
          repository.readReferences(lens.id).flatMap:
            case LensRepository.ReferencesResult.NotFound       => UpdateResult.NotFound.pure[F]
            case LensRepository.ReferencesResult.Failed         => UpdateResult.Failed.pure[F]
            case LensRepository.ReferencesResult.Found(cameras) =>
              if cameras.forall(_.accepts(lens)) then
                repository.update(lens).map:
                  case LensRepository.UpdateResult.Updated   => UpdateResult.Updated
                  case LensRepository.UpdateResult.NotFound  => UpdateResult.NotFound
                  case LensRepository.UpdateResult.Conflict  => UpdateResult.Conflict
                  case LensRepository.UpdateResult.Failed    => UpdateResult.Failed
                  case LensRepository.UpdateResult.Uncertain => UpdateResult.Uncertain
              else UpdateResult.InvalidatesSelection.pure[F]

    override def delete(id: LensId): F[DeleteResult] =
      MonadCancelThrow[F].uncancelable: _ =>
        summon[Mutex[F]].lock.surround:
          repository.delete(id).map:
            case LensRepository.DeleteResult.Deleted   => DeleteResult.Deleted
            case LensRepository.DeleteResult.NotFound  => DeleteResult.NotFound
            case LensRepository.DeleteResult.Used      => DeleteResult.Used
            case LensRepository.DeleteResult.Failed    => DeleteResult.Failed
            case LensRepository.DeleteResult.Uncertain => DeleteResult.Uncertain

  enum CreateResult:
    case Created(lens: Lens)
    case Conflict, Failed, Uncertain

  enum UpdateResult:
    case Updated, NotFound, Conflict, InvalidatesSelection, Failed, Uncertain

  enum DeleteResult:
    case Deleted, NotFound, Used, Failed, Uncertain
