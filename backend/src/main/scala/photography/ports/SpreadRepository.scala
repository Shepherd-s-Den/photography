package photography.ports

import photography.domain.layout.SpreadTemplate

enum SpreadRepositoryFailure derives CanEqual:
  case InvalidSpreadData, DatabaseFailure

trait SpreadRepository[F[_]]:
  def listAlbumStartTemplates: F[SpreadRepository.ListAlbumStartTemplatesResult]

object SpreadRepository:
  enum ListAlbumStartTemplatesResult derives CanEqual:
    case Listed(templates: Vector[SpreadTemplate])
    case Failed(reason: SpreadRepositoryFailure)
