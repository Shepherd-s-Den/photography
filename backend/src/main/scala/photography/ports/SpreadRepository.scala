package photography.ports

import photography.domain.layout.SpreadTemplate

trait SpreadRepository[F[_]]:
  def listAlbumStartTemplates: F[SpreadRepository.ListAlbumStartTemplatesResult]

object SpreadRepository:
  enum Failure derives CanEqual:
    case InvalidSpreadData, DatabaseFailure

  enum ListAlbumStartTemplatesResult derives CanEqual:
    case Listed(templates: Vector[SpreadTemplate])
    case Failed(reason: Failure)
