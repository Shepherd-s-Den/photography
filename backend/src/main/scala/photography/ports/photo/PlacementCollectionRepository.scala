package photography.ports.photo

import photography.domain.album.{AlbumId, ChapterId, PhotoId}

trait PlacementCollectionRepository[Db[_]]:
  def read(album: AlbumId, maybeChapter: Option[ChapterId]): Db[PlacementCollectionRepository.Inventory]

object PlacementCollectionRepository:
  final case class Inventory(eligible: Set[PhotoId], placed: Set[PhotoId])
