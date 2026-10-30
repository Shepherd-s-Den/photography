package photography.domain.gear

import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import java.util.UUID

opaque type CameraId = UUID
object CameraId:
  def apply(value: UUID): CameraId         = value
  extension (id: CameraId) def value: UUID = id

type CameraExifCode = String :| Not[Empty]

type CameraName = String :| Not[Empty]

enum CameraKind derives CanEqual:
  case FixedLens
  case InterchangeableLens(mount: LensMount, sensor: SensorFormat)

  def accepts(lens: Lens): Boolean = this match
    case FixedLens                          => false
    case InterchangeableLens(mount, sensor) =>
      (mount: String) === (lens.mount: String) && (sensor === SensorFormat.ApsC || lens.coverage === SensorFormat.FullFrame)

final case class Camera(id: CameraId, code: CameraExifCode, friendlyName: CameraName, kind: CameraKind)
