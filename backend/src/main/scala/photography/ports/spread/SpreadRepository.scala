package photography.ports.spread

import cats.data.NonEmptyList
import photography.domain.album.ChapterId
import photography.domain.layout.{Spread, SpreadId}

trait SpreadRepository[Db[_]]:
  def insert(chapter: ChapterId, spread: Spread.PhotosOnly): Db[Unit]
  def replace(spread: Spread): Db[Unit]
  def reorder(chapter: ChapterId, order: NonEmptyList[SpreadId]): Db[Unit]
  def remove(spread: SpreadId): Db[Unit]
