package photography.domain.gear

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
  case FixedLens, InterchangeableLens

final case class Camera(id: CameraId, code: CameraExifCode, friendlyName: CameraName, kind: CameraKind)
