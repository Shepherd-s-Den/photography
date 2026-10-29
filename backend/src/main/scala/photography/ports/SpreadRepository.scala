package photography.ports

import cats.data.NonEmptyList
import photography.domain.album.{Album, AlbumId, ChapterId}
import photography.domain.layout.{Spread, SpreadId}

trait SpreadRepository[Db[_]]:
  def loadAlbum(album: AlbumId): Db[Option[Album]]
  def insert(chapter: ChapterId, spread: Spread.PhotosOnly): Db[Unit]
  def replace(spread: Spread): Db[Unit]
  def reorder(chapter: ChapterId, order: NonEmptyList[SpreadId]): Db[Unit]
  def remove(spread: SpreadId): Db[Unit]
