package photography.ports.gear

import cats.data.NonEmptyVector
import photography.domain.gear.{Camera, CameraId, Lens}

/** Updates and deletion are atomic. Reference reads and writes participate in the backend's shared mutation coordination. */
trait CameraRepository[F[_]]:
  def create(camera: Camera): F[CameraRepository.CreateResult]
  def readReferences(id: CameraId): F[CameraRepository.ReferencesResult]
  def update(camera: Camera): F[CameraRepository.UpdateResult]
  def delete(id: CameraId): F[CameraRepository.DeleteResult]

object CameraRepository:
  enum CreateResult:
    case Created, Conflict, Failed, Uncertain

  enum ReferencesResult:
    case Found(usage: Usage)
    case NotFound, Failed

  enum Usage:
    case Unused, FixedLens
    case Interchangeable(lenses: NonEmptyVector[Lens])

  enum UpdateResult:
    case Updated, NotFound, Conflict, Failed, Uncertain

  enum DeleteResult:
    case Deleted, NotFound, Used, Failed, Uncertain
