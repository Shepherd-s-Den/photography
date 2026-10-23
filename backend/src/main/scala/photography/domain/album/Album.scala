package photography.domain.album

import cats.data.NonEmptyList
import photography.domain.layout.Spread
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import java.time.Instant
import java.util.UUID

final case class Album(id: AlbumId, title: AlbumName, synopsis: String, createdAt: Instant, spreads: NonEmptyList[Spread])

opaque type AlbumId = UUID
object AlbumId:
  def apply(value: UUID): AlbumId         = value
  extension (id: AlbumId) def value: UUID = id

type AlbumName = String :| (Not[Empty] & Not[Blank])
