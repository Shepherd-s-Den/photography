package photography.ports.album

import cats.data.NonEmptyList
import photography.domain.album.Country

trait CountryRepository[F[_]]:
  def list: F[CountryRepository.ListResult]

object CountryRepository:
  enum ListResult derives CanEqual:
    case Listed(countries: NonEmptyList[Country])
    case Failed
