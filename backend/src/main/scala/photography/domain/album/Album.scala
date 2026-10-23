package photography.domain.album

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import java.time.Instant
import java.util.UUID

opaque type AlbumId = UUID
object AlbumId:
  def apply(value: UUID): AlbumId         = value
  extension (id: AlbumId) def value: UUID = id

type AlbumName = String :| Not[Empty]

final case class Album(id: AlbumId, title: AlbumName, synopsis: String, createdAt: Instant)
