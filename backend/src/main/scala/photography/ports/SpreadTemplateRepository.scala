package photography.ports

import cats.data.NonEmptyList
import photography.domain.layout.SpreadTemplate

trait SpreadTemplateRepository[F[_]]:
  def listAlbumStartTemplates: F[SpreadTemplateRepository.ListAlbumStartTemplatesResult]
  def listChapterStartTemplates: F[SpreadTemplateRepository.ListChapterStartTemplatesResult]

object SpreadTemplateRepository:
  enum ListAlbumStartTemplatesResult derives CanEqual:
    case Listed(templates: NonEmptyList[SpreadTemplate.AlbumStart])
    case Failed(reason: Failure)

  enum ListChapterStartTemplatesResult derives CanEqual:
    case Listed(templates: NonEmptyList[SpreadTemplate.ChapterStart])
    case Failed(reason: Failure)

  enum Failure derives CanEqual:
    case DatabaseFailure
