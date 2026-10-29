package photography.adapters.sqlite

import cats.data.NonEmptyList
import cats.syntax.all.*
import org.typelevel.doobie.ConnectionIO
import photography.domain.album.{AlbumId, ChapterId}
import photography.domain.layout.SpreadId
import photography.ports.AlbumCompositionRepository
import photography.ports.AlbumCompositionRepository.Located

object SqliteAlbumCompositionRepository:
  def make: AlbumCompositionRepository[ConnectionIO] = new LiveSqliteAlbumCompositionRepository

  final private class LiveSqliteAlbumCompositionRepository extends AlbumCompositionRepository[ConnectionIO]:
    override def hasAlbum(album: AlbumId): ConnectionIO[Boolean]                                                    = failDeferred
    override def countInteriorSpreads(album: AlbumId): ConnectionIO[Int]                                            = failDeferred
    override def findSpread(album: AlbumId, spread: SpreadId): ConnectionIO[Option[Located]]                        = failDeferred
    override def findChapterOrder(album: AlbumId, chapter: ChapterId): ConnectionIO[Option[NonEmptyList[SpreadId]]] = failDeferred

    private def failDeferred[A] =
      new UnsupportedOperationException("Composition reads are deferred to the adapter slice").raiseError[ConnectionIO, A]
