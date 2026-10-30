package photography.ports.gear

import photography.domain.gear.{CameraKind, Lens, LensId}

/** Reference reads and atomic writes share backend mutation coordination; deletion of a referenced lens returns Used. */
trait LensRepository[F[_]]:
  def create(lens: Lens): F[LensRepository.CreateResult]
  def readReferences(id: LensId): F[LensRepository.ReferencesResult]
  def update(lens: Lens): F[LensRepository.UpdateResult]
  def delete(id: LensId): F[LensRepository.DeleteResult]

object LensRepository:
  enum CreateResult:
    case Created, Conflict, Failed, Uncertain

  enum ReferencesResult:
    case Found(cameras: Vector[CameraKind])
    case NotFound, Failed

  enum UpdateResult:
    case Updated, NotFound, Conflict, Failed, Uncertain

  enum DeleteResult:
    case Deleted, NotFound, Used, Failed, Uncertain
