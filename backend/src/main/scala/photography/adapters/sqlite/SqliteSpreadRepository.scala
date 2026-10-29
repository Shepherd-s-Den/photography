package photography.adapters.sqlite

import cats.data.NonEmptyList
import cats.syntax.all.*
import org.typelevel.doobie.ConnectionIO
import photography.domain.album.{AlbumId, ChapterId}
import photography.domain.layout.{Spread, SpreadId}
import photography.ports.SpreadRepository
import photography.ports.SpreadRepository.Located

object SqliteSpreadRepository:
  def make: SpreadRepository[ConnectionIO] = new LiveSqliteSpreadRepository

  final private class LiveSqliteSpreadRepository extends SpreadRepository[ConnectionIO]:
    override def exists(album: AlbumId): ConnectionIO[Boolean]                                                   = unimplemented
    override def countInterior(album: AlbumId): ConnectionIO[Int]                                                = unimplemented
    override def find(album: AlbumId, spread: SpreadId): ConnectionIO[Option[Located]]                           = unimplemented
    override def findOrder(album: AlbumId, chapter: ChapterId): ConnectionIO[Option[NonEmptyList[SpreadId]]]     = unimplemented
    override def readOpening(album: AlbumId): ConnectionIO[Spread.AlbumStart]                                    = unimplemented
    override def readChapter(chapter: ChapterId): ConnectionIO[(Spread.ChapterStart, Vector[Spread.PhotosOnly])] = unimplemented
    override def insertOpening(album: AlbumId, spread: Spread.AlbumStart): ConnectionIO[Unit]                    = unimplemented
    override def insert(chapter: ChapterId, spread: Spread.PhotosOnly): ConnectionIO[Unit]                       = unimplemented
    override def replace(spread: Spread): ConnectionIO[Unit]                                                     = unimplemented
    override def reorder(chapter: ChapterId, order: NonEmptyList[SpreadId]): ConnectionIO[Unit]                  = unimplemented
    override def remove(spread: SpreadId): ConnectionIO[Unit]                                                    = unimplemented

    private def unimplemented[A] =
      new UnsupportedOperationException("Spread persistence is deferred to the adapter slice").raiseError[ConnectionIO, A]
