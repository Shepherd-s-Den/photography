package photography.domain.album

import java.time.Instant
import java.nio.file.Path
import java.util.UUID
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

opaque type ChapterId = UUID
object ChapterId:
  def apply(value: UUID): ChapterId         = value
  extension (id: ChapterId) def value: UUID = id

type PositionInAlbum = Int :| (GreaterEqual[0] & Less[20])

opaque type RelativeLocation = Path
object RelativeLocation:
  def apply(value: Path): RelativeLocation               = value
  extension (location: RelativeLocation) def value: Path = location

final case class VisitPeriod(start: Instant, end: Instant)

final case class Chapter(
    id: ChapterId,
    album: AlbumId,
    destination: DestinationId,
    description: String,
    period: VisitPeriod,
    position: PositionInAlbum,
    sourceFolder: RelativeLocation
)
