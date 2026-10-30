package photography.ports.cover

import photography.domain.album.AlbumId
import photography.domain.layout.{Cover, CoverPlacement}

trait CoverRepository[Db[_]]:
  def read(album: AlbumId): Db[Option[CoverRepository.Composition]]

  def replace(album: AlbumId, placements: Vector[CoverPlacement]): Db[Unit]

object CoverRepository:
  final case class Composition(cover: Cover, placements: Vector[CoverPlacement])
