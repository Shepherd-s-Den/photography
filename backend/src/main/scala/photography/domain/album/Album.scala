package photography.domain.album

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import java.util.UUID
import photography.domain.layout.Cover
import photography.domain.layout.Spread.*

opaque type AlbumId = UUID
object AlbumId:
  def apply(value: UUID): AlbumId         = value
  extension (id: AlbumId) def value: UUID = id

type AlbumName = String :| Not[Empty]

final case class Album(id: AlbumId, title: AlbumName, synopsis: String)

final case class ChapterComposition(
    chapter: Chapter,
    start: ChapterStart,
    photos: Vector[Photo],
    spreads: Vector[PhotosOnly]
)

final case class AlbumComposition(
    album: Album,
    start: AlbumStart,
    cover: Cover,
    chapters: Vector[ChapterComposition]
)
