package photography.domain.album

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import photography.domain.layout.Spread

import java.time.LocalDate
import java.util.UUID

opaque type ChapterId = UUID
object ChapterId:
  def apply(value: UUID): ChapterId         = value
  extension (id: ChapterId) def value: UUID = id

type ChapterName                = DestinationName
type RelativeLocationConstraint = Not[Blank] & Match["(?!/)(?!.*[:\\\\\\x00])(?!(?:.*/)?\\.{1,2}(?:/|$))[^/]+(?:/[^/]+)*"]
type RelativeLocation           = String :| RelativeLocationConstraint
type VisitDays                  = Long :| Positive

final case class VisitPeriod(firstDay: LocalDate, days: VisitDays)

sealed trait Chapter:
  def id: ChapterId
  def country: Country
  def description: String
  def maybeSourceFolder: Option[RelativeLocation]
  def opening: Spread.ChapterStart
  def spreads: Vector[Spread.PhotosOnly]

object Chapter:
  final case class Vacation(
      id: ChapterId,
      destination: Destination,
      period: VisitPeriod,
      description: String,
      maybeSourceFolder: Option[RelativeLocation],
      opening: Spread.ChapterStart,
      spreads: Vector[Spread.PhotosOnly]
  ) extends Chapter:
    def country: Country = destination.country

  final case class Home(
      id: ChapterId,
      country: Country,
      description: String,
      maybeSourceFolder: Option[RelativeLocation],
      opening: Spread.ChapterStart,
      spreads: Vector[Spread.PhotosOnly]
  ) extends Chapter

enum ChapterConflict:
  case AtCapacity, HomeExists, FolderAssigned, ChapterNotFound
