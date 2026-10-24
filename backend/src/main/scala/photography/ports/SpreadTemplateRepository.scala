package photography.ports

import cats.data.NonEmptyList
import photography.domain.layout.SpreadTemplate

trait SpreadTemplateRepository[F[_]]:
  def listAlbumStartTemplates: F[SpreadTemplateRepository.ListAlbumStartTemplatesResult]

object SpreadTemplateRepository:
  enum ListAlbumStartTemplatesResult derives CanEqual:
    case Listed(templates: NonEmptyList[SpreadTemplate])
    case Failed(reason: Failure)

  enum Failure derives CanEqual:
    case InvalidSpreadData, DatabaseFailure
