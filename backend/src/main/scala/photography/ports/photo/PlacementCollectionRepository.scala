package photography.ports.photo

import photography.domain.album.{AlbumId, ChapterId, PhotoId}

trait PlacementCollectionRepository[Db[_]]:
  /**
   * Eligible identities and their use anywhere, including Cover, from the caller's current-state transaction. Read failure must not return empty
   * sets.
   */
  def read(album: AlbumId, maybeChapter: Option[ChapterId]): Db[PlacementCollectionRepository.Inventory]

object PlacementCollectionRepository:
  final case class Inventory(eligible: Set[PhotoId], placed: Set[PhotoId])
