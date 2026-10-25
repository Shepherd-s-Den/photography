package photography.usecases

import cats.Functor
import cats.data.NonEmptyList
import cats.syntax.all.*
import photography.domain.layout.SpreadTemplate
import photography.ports.SpreadTemplateRepository

trait SpreadTemplateService[F[_]]:
  def listAlbumStartTemplates: F[SpreadTemplateService.ListAlbumStartTemplatesResult]
  def listChapterStartTemplates: F[SpreadTemplateService.ListChapterStartTemplatesResult]

object SpreadTemplateService:
  def make[F[_]: Functor](repository: SpreadTemplateRepository[F]): SpreadTemplateService[F] =
    new LiveSpreadTemplateService(repository)

  final private class LiveSpreadTemplateService[F[_]: Functor](repository: SpreadTemplateRepository[F]) extends SpreadTemplateService[F]:
    override def listAlbumStartTemplates: F[ListAlbumStartTemplatesResult] =
      repository.listAlbumStartTemplates.map:
        case SpreadTemplateRepository.ListAlbumStartTemplatesResult.Listed(templates) => ListAlbumStartTemplatesResult.Listed(templates)
        case SpreadTemplateRepository.ListAlbumStartTemplatesResult.Failed(_)         => ListAlbumStartTemplatesResult.Failed

    override def listChapterStartTemplates: F[ListChapterStartTemplatesResult] =
      repository.listChapterStartTemplates.map:
        case SpreadTemplateRepository.ListChapterStartTemplatesResult.Listed(templates) => ListChapterStartTemplatesResult.Listed(templates)
        case SpreadTemplateRepository.ListChapterStartTemplatesResult.Failed(_)         => ListChapterStartTemplatesResult.Failed

  enum ListAlbumStartTemplatesResult derives CanEqual:
    case Listed(templates: NonEmptyList[SpreadTemplate.AlbumStart])
    case Failed

  enum ListChapterStartTemplatesResult derives CanEqual:
    case Listed(templates: NonEmptyList[SpreadTemplate.ChapterStart])
    case Failed
