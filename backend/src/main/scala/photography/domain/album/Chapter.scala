package photography.domain.album

import cats.syntax.all.*
import java.time.{Instant, LocalDate, ZoneOffset}
import java.nio.file.Path
import java.util.UUID
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

opaque type ChapterId = UUID
object ChapterId:
  def apply(value: UUID): ChapterId         = value
  extension (id: ChapterId) def value: UUID = id

type PositionInAlbumConstraint = GreaterEqual[0]
type PositionInAlbum           = Int :| PositionInAlbumConstraint
type ChapterName               = String :| (Not[Empty] & Not[Blank])

object ChapterName:
  def from(value: String): Either[String, ChapterName] =
    value.trim.refineEither[Not[Empty] & Not[Blank]]

opaque type RelativeLocation = Path
object RelativeLocation:
  def from(value: String): Either[String, RelativeLocation] =
    val folder   = value.trim
    val segments = folder.split('/').toVector
    val invalid  = folder.isEmpty || folder.startsWith("/") || folder.contains(":") || folder.contains("\\") ||
      folder.contains('\u0000') || segments.exists(segment => segment.isEmpty || segment === "." || segment === "..")
    Either.cond(!invalid, Path.of(folder), "Photo folder must be a contained relative path")

  extension (location: RelativeLocation) def value: Path = location

final case class VisitPeriod private (start: Instant, end: Instant):
  def firstDay: LocalDate = start.atOffset(ZoneOffset.UTC).toLocalDate
  def lastDay: LocalDate  = end.atOffset(ZoneOffset.UTC).toLocalDate.minusDays(1L)

object VisitPeriod:
  def wholeYear(year: AlbumYear): VisitPeriod =
    VisitPeriod(
      LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant,
      LocalDate.of(year + 1, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant
    )

  def fromDates(first: LocalDate, last: LocalDate): Either[String, VisitPeriod] =
    Either.cond(
      !last.isBefore(first) && last.isBefore(LocalDate.MAX),
      VisitPeriod(first.atStartOfDay(ZoneOffset.UTC).toInstant, last.plusDays(1L).atStartOfDay(ZoneOffset.UTC).toInstant),
      "Last day must be on or after first day and have a representable exclusive endpoint"
    )

enum ChapterDetails:
  case Vacation(name: ChapterName, country: CountryId, period: VisitPeriod)
  case Home(country: CountryId)

  def countryId: CountryId = this match
    case Vacation(_, country, _) => country
    case Home(country)           => country

final case class Chapter(
    id: ChapterId,
    album: AlbumId,
    details: ChapterDetails,
    description: String,
    maybeSourceFolder: Option[RelativeLocation]
)
