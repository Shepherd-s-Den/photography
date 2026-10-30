package photography.adapters.sqlite.photo

import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits.*
import photography.adapters.sqlite.SqliteCodecs.given
import photography.domain.album.{ChapterId, PhotoId}
import photography.ports.photo.PhotoPlacementRepository
import photography.ports.photo.PhotoPlacementRepository.FindResult
import java.util.UUID

object SqlitePhotoPlacementRepository:
  def make: PhotoPlacementRepository[ConnectionIO] = new LiveSqlitePhotoPlacementRepository

  final private class LiveSqlitePhotoPlacementRepository extends PhotoPlacementRepository[ConnectionIO]:
    override def findPlacedPhotos(chapter: ChapterId): ConnectionIO[FindResult] =
      sql"""SELECT DISTINCT p.id FROM photo p
        JOIN placement placed ON placed.photo_id = p.id
        WHERE p.chapter_id = ${chapter.value}"""
        .query[UUID].to[Vector].map(ids => FindResult.Found(photos = ids.map(PhotoId(_)).toSet))
