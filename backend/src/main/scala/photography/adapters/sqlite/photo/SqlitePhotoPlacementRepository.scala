package photography.adapters.sqlite.photo

import org.typelevel.doobie.{ConnectionIO, Fragment}
import org.typelevel.doobie.implicits.*
import photography.adapters.sqlite.SqliteCodecs.given
import photography.domain.album.{AlbumId, ChapterId, PhotoId}
import photography.ports.photo.PhotoPlacementRepository
import photography.ports.photo.PhotoPlacementRepository.{FindResult, InspectResult}
import java.util.UUID

object SqlitePhotoPlacementRepository:
  def make: PhotoPlacementRepository[ConnectionIO] = new LiveSqlitePhotoPlacementRepository

  final private class LiveSqlitePhotoPlacementRepository extends PhotoPlacementRepository[ConnectionIO]:
    override def findPlacedPhotos(chapter: ChapterId): ConnectionIO[FindResult] =
      sql"""SELECT DISTINCT p.id FROM photo p
        JOIN placement placed ON placed.photo_id = p.id
        WHERE p.chapter_id = ${chapter.value}"""
        .query[UUID].to[Vector].map(ids => FindResult.Found(photos = ids.map(PhotoId(_)).toSet))

    override def inspect(album: AlbumId, maybeChapter: Option[ChapterId], photo: PhotoId): ConnectionIO[InspectResult] =
      val chapterScope = maybeChapter.fold(Fragment.empty)(chapter => fr"AND c.id = ${chapter.value}")
      val membership   = fr"c.album_id = ${album.value}" ++ chapterScope
      val select       = fr"SELECT (" ++ membership ++
        fr"""), EXISTS(SELECT 1 FROM placement WHERE photo_id = p.id)
              FROM photo p JOIN chapter c ON c.id = p.chapter_id WHERE p.id = ${photo.value}"""
      select.query[(Boolean, Boolean)].option.map:
        _.fold[InspectResult](InspectResult.NotFound) { case (member, placed) => InspectResult.Found(inCollection = member, placed = placed) }
