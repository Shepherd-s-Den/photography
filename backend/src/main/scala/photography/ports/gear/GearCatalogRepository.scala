package photography.ports.gear

import photography.domain.gear.GearCatalog

trait GearCatalogRepository[F[_]]:
  def read: F[GearCatalogRepository.ReadResult]

object GearCatalogRepository:
  enum ReadResult:
    case Found(catalog: GearCatalog)
    case Failed
