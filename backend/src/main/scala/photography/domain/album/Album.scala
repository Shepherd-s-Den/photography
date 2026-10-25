package photography.domain.album

import cats.data.NonEmptyList
import photography.domain.layout.Spread
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import java.time.Instant
import java.util.UUID

type AlbumYearConstraint = GreaterEqual[1900] & LessEqual[2100]
type AlbumYear           = Int :| AlbumYearConstraint

final case class Album(
    id: AlbumId,
    year: AlbumYear,
    synopsis: String,
    createdAt: Instant,
    opening: Spread.AlbumStart,
    chapters: Vector[Chapter]
):
  def spreads: NonEmptyList[Spread] = NonEmptyList(opening, chapters.flatMap(chapter => chapter.opening +: chapter.spreads).toList)
  def countries: Vector[Country]    = chapters.map(_.destination.country).distinctBy(_.id)

opaque type AlbumId = UUID
object AlbumId:
  def apply(value: UUID): AlbumId         = value
  extension (id: AlbumId) def value: UUID = id
