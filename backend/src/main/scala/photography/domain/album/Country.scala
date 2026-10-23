package photography.domain.album

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import java.nio.file.Path
import java.util.UUID

opaque type CountryId = UUID
object CountryId:
  def apply(value: UUID): CountryId         = value
  extension (id: CountryId) def value: UUID = id

type CountryCode = String :| Match["[A-Z]{2}"]

type CountryName = String :| Not[Empty]

opaque type AbsoluteLocation = Path
object AbsoluteLocation:
  def apply(value: Path): AbsoluteLocation               = value
  extension (location: AbsoluteLocation) def value: Path = location

final case class Country(id: CountryId, code: CountryCode, name: CountryName, flag: AbsoluteLocation)
