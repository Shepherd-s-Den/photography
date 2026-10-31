package photography.domain.album

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import photography.domain.layout.Spread
import photography.domain.gear.GearSelection

import java.time.LocalDate
import java.util.UUID

opaque type ChapterId = UUID
object ChapterId:
  def apply(value: UUID): ChapterId         = value
  extension (id: ChapterId) def value: UUID = id

type RelativeLocationConstraint = Not[Blank] & Match["(?!/)(?!.*[:\\\\\\x00])(?!(?:.*/)?\\.{1,2}(?:/|$))[^/]+(?:/[^/]+)*"]
type RelativeLocation           = String :| RelativeLocationConstraint
type PhotoSourceConstraint      = Match["/(?!.*[\\\\\\x00])(?!(?:.*/)?\\.{1,2}(?:/|$))[^/]+(?:/[^/]+)*"]
type PhotoSource                = String :| PhotoSourceConstraint
type VisitDays                  = Long :| Positive

final case class VisitPeriod(firstDay: LocalDate, days: VisitDays)

final case class Chapter(
    id: ChapterId,
    destination: Destination,
    period: VisitPeriod,
    description: String,
    gear: GearSelection,
    sourceFolder: PhotoSource,
    opening: Spread.ChapterStart,
    spreads: Vector[Spread.PhotosOnly]
)

enum ChapterConflict:
  case AtCapacity, HomeExists, HomePeriodMismatch, FolderAssigned, TypeChange, UnknownCountries
