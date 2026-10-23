package photography.domain.gear

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import java.util.UUID

opaque type LensId = UUID
object LensId:
  def apply(value: UUID): LensId         = value
  extension (id: LensId) def value: UUID = id

type LensExifCode = String :| Not[Empty]

type LensName = String :| Not[Empty]

final case class Lens(id: LensId, code: LensExifCode, friendlyName: LensName, camera: CameraId)
