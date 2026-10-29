package photography.adapters.sqlite

import cats.data.NonEmptyList
import cats.syntax.all.*
import org.typelevel.doobie.ConnectionIO
import photography.domain.album.ChapterId
import photography.domain.layout.{Spread, SpreadId}
import photography.ports.SpreadRepository

object SqliteSpreadRepository:
  def make: SpreadRepository[ConnectionIO] = new LiveSqliteSpreadRepository

  final private class LiveSqliteSpreadRepository extends SpreadRepository[ConnectionIO]:
    override def insert(chapter: ChapterId, spread: Spread.PhotosOnly): ConnectionIO[Unit]      = failDeferred
    override def replace(spread: Spread): ConnectionIO[Unit]                                    = failDeferred
    override def reorder(chapter: ChapterId, order: NonEmptyList[SpreadId]): ConnectionIO[Unit] = failDeferred
    override def remove(spread: SpreadId): ConnectionIO[Unit]                                   = failDeferred

    private def failDeferred[A] =
      new UnsupportedOperationException("Spread persistence is deferred to the adapter slice").raiseError[ConnectionIO, A]
