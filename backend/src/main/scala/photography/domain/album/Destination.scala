package photography.domain.album

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import cats.syntax.all.*
import cats.data.NonEmptyList
import java.util.UUID

opaque type DestinationId = UUID
object DestinationId:
  def apply(value: UUID): DestinationId         = value
  extension (id: DestinationId) def value: UUID = id

type DestinationName = String :| (Not[Empty] & Not[Blank])

final case class Destination(id: DestinationId, name: DestinationName, countries: NonEmptyList[Country]):
  def isHome: Boolean = (name: String) === "Home"
