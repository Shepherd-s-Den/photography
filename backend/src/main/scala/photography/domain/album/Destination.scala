package photography.domain.album

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import java.util.UUID

opaque type DestinationId = UUID
object DestinationId:
  def apply(value: UUID): DestinationId         = value
  extension (id: DestinationId) def value: UUID = id

type DestinationName = String :| (Not[Empty] & Not[Blank])

final case class Destination(id: DestinationId, name: DestinationName, country: Country)
