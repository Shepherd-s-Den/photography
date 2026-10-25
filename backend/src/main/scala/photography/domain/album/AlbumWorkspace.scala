package photography.domain.album

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

type AlbumYearConstraint = GreaterEqual[1900] & LessEqual[2100]
type AlbumYear           = Int :| AlbumYearConstraint

object AlbumYear:
  def title(year: AlbumYear): AlbumName =
    s"Journey of the Shepherds $year".refineUnsafe[Not[Empty] & Not[Blank]]

final case class AlbumWorkspace(album: Album, maybeYear: Option[AlbumYear], chapters: Vector[Chapter], countries: Vector[Country])
