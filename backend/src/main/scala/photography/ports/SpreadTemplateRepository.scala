package photography.ports

import cats.data.NonEmptyList
import photography.domain.layout.{SpreadTemplate, SpreadTemplateId}

trait SpreadTemplateRepository[F[_]]:
  def listAlbumStartTemplates: F[SpreadTemplateRepository.ListAlbumStartTemplatesResult]
  def listChapterStartTemplates: F[SpreadTemplateRepository.ListChapterStartTemplatesResult]
  def listPhotosOnlyTemplates: F[SpreadTemplateRepository.ListPhotosOnlyTemplatesResult]
  def findTemplate(id: SpreadTemplateId): F[SpreadTemplateRepository.FindTemplateResult]

object SpreadTemplateRepository:
  enum ListAlbumStartTemplatesResult derives CanEqual:
    case Listed(templates: NonEmptyList[SpreadTemplate.AlbumStart])
    case Failed(reason: Failure)

  enum ListChapterStartTemplatesResult derives CanEqual:
    case Listed(templates: NonEmptyList[SpreadTemplate.ChapterStart])
    case Failed(reason: Failure)

  enum ListPhotosOnlyTemplatesResult derives CanEqual:
    case Listed(templates: NonEmptyList[SpreadTemplate.PhotosOnly])
    case Failed(reason: Failure)

  enum FindTemplateResult derives CanEqual:
    case Found(template: SpreadTemplate)
    case NotFound
    case Failed(reason: Failure)

  enum Failure derives CanEqual:
    case DatabaseFailure
