package photography.ports

import cats.data.NonEmptyList
import photography.domain.album.{AlbumId, ChapterId}
import photography.domain.layout.{Spread, SpreadId}
import photography.ports.AlbumSpreads

/**
 * Callers hold the shared chapter-mutation lock and run current-state reads, decisions and writes in one transaction. Scoped lookups return None for
 * absent owners/targets; malformed persisted state fails. Chapter orders begin with their opening.
 */
trait SpreadRepository[Db[_]] extends AlbumSpreads[Db]:
  def exists(album: AlbumId): Db[Boolean]
  def countInterior(album: AlbumId): Db[Int]
  def find(album: AlbumId, spread: SpreadId): Db[Option[SpreadRepository.Located]]
  def findOrder(album: AlbumId, chapter: ChapterId): Db[Option[NonEmptyList[SpreadId]]]
  def insert(chapter: ChapterId, spread: Spread.PhotosOnly): Db[Unit]
  def replace(spread: Spread): Db[Unit]
  def reorder(chapter: ChapterId, order: NonEmptyList[SpreadId]): Db[Unit]
  def remove(spread: SpreadId): Db[Unit]

object SpreadRepository:
  enum Located:
    case AlbumOpening(value: Spread.AlbumStart)
    case ChapterOpening(chapter: ChapterId, value: Spread.ChapterStart)
    case Photo(chapter: ChapterId, value: Spread.PhotosOnly)

    def spread: Spread = this match
      case AlbumOpening(value)      => value
      case ChapterOpening(_, value) => value
      case Photo(_, value)          => value
