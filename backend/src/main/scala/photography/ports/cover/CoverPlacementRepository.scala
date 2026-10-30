package photography.ports.cover

import photography.domain.album.AlbumId
import photography.domain.layout.{Cover, CoverPlacement}

trait CoverPlacementRepository[Db[_]]:
  def read(album: AlbumId): Db[Option[CoverPlacementRepository.Composition]]

  /** Replaces both sides atomically in the transaction containing the coordinated current-state read and decision. */
  def replace(album: AlbumId, placements: Vector[CoverPlacement]): Db[Unit]

object CoverPlacementRepository:
  final case class Composition(cover: Cover, placements: Vector[CoverPlacement])
