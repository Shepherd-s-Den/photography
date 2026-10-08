package photography.domain.album

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import java.time.Instant
import java.util.UUID
import photography.domain.layout.{CoverComposition, SpreadComposition}

opaque type AlbumId = UUID
object AlbumId:
  def apply(value: UUID): AlbumId         = value
  extension (id: AlbumId) def value: UUID = id

type AlbumName = String :| Not[Empty]

final case class Album(id: AlbumId, title: AlbumName, synopsis: String, createdAt: Instant)

final case class ChapterComposition(
    chapter: Chapter,
    start: SpreadComposition,
    photos: Vector[Photo],
    spreads: Vector[SpreadComposition]
)

final case class AlbumComposition(
    album: Album,
    start: SpreadComposition,
    cover: CoverComposition,
    chapters: Vector[ChapterComposition]
)
