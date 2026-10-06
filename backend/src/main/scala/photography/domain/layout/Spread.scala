package photography.domain.layout

import photography.domain.album.{AlbumId, ChapterId}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

import java.util.UUID

type PositionInChapter = Int :| Positive

opaque type SpreadId = UUID
object SpreadId:
  def apply(value: UUID): SpreadId = value
  extension (id: SpreadId) def value: UUID = id

enum SpreadOwner:
  case Album(id: AlbumId)
  case Chapter(id: ChapterId)

enum Spread:
  case AlbumStart(id: SpreadId, album: AlbumId, template: SpreadTemplateId)
  case ChapterStart(id: SpreadId, chapter: ChapterId, template: SpreadTemplateId)
  case PhotosOnly(
      id: SpreadId,
      chapter: ChapterId,
      position: PositionInChapter,
      template: SpreadTemplateId
  )
