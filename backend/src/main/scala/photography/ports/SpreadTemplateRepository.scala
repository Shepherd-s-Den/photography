package photography.ports

import cats.data.NonEmptyList
import photography.domain.layout.SpreadTemplate

trait SpreadTemplateRepository[F[_]]:
  // Adapters return only valid AlbumStart templates, never an empty list, ordered by ascending ID; a violation is reported as Failed.
  def listAlbumStartTemplates: F[SpreadTemplateRepository.ListAlbumStartTemplatesResult]

object SpreadTemplateRepository:
  enum Failure derives CanEqual:
    case InvalidSpreadData, DatabaseFailure

  enum ListAlbumStartTemplatesResult derives CanEqual:
    case Listed(templates: NonEmptyList[SpreadTemplate])
    case Failed(reason: Failure)
