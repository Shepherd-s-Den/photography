package photography.ports.gear

import photography.domain.gear.GearCatalog

/** A read supplies one domain-valid inventory with unique camera/lens identities and established compatibility facts. */
trait GearCatalogRepository[F[_]]:
  def read: F[GearCatalogRepository.ReadResult]

object GearCatalogRepository:
  enum ReadResult:
    case Found(catalog: GearCatalog)
    case Failed
