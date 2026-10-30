package photography.ports.cover

import photography.domain.album.AlbumId
import photography.domain.layout.{Cover, CoverPlacement}

trait CoverPlacementRepository[Db[_]]:
  def read(album: AlbumId): Db[Option[CoverPlacementRepository.Composition]]

  def replace(album: AlbumId, placements: Vector[CoverPlacement]): Db[Unit]

object CoverPlacementRepository:
  final case class Composition(cover: Cover, placements: Vector[CoverPlacement])
