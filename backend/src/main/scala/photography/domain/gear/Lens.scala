package photography.domain.gear

import cats.Eq
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import java.util.UUID

opaque type LensId = UUID
object LensId:
  def apply(value: UUID): LensId         = value
  extension (id: LensId) def value: UUID = id

type LensExifCode = String :| Not[Empty]

type LensName = String :| Not[Empty]

enum LensMount derives CanEqual:
  case SonyE, NikonZ

object LensMount:
  given Eq[LensMount] = Eq.by(_.ordinal)

enum SensorFormat derives CanEqual:
  case ApsC, FullFrame

object SensorFormat:
  given Eq[SensorFormat] = Eq.by(_.ordinal)

final case class Lens(id: LensId, code: LensExifCode, friendlyName: LensName, mount: LensMount, coverage: SensorFormat)
