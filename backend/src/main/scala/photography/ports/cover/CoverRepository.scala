package photography.ports.cover

import photography.domain.album.AlbumId
import photography.domain.layout.{Cover, CoverPlacement}

trait CoverRepository[Db[_]]:
  def read(album: AlbumId): Db[CoverRepository.ReadResult]

  def replace(album: AlbumId, placements: Vector[CoverPlacement]): Db[CoverRepository.ReplaceResult]

object CoverRepository:
  final case class Composition(cover: Cover, placements: Vector[CoverPlacement])

  enum ReadResult:
    case Found(composition: Composition)
    case NotFound

  enum ReplaceResult:
    case Replaced, NotFound
