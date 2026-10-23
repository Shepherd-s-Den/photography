package photography.usecases

import cats.Functor
import cats.syntax.all.*
import photography.domain.layout.{SpreadTemplate, SpreadTemplateKind}
import photography.ports.SpreadRepository

trait SpreadTemplateService[F[_]]:
  def listAlbumStartTemplates: F[SpreadTemplateService.ListAlbumStartTemplatesResult]

object SpreadTemplateService:
  enum ListAlbumStartTemplatesResult derives CanEqual:
    case Listed(templates: Vector[SpreadTemplate])
    case Failed

  def make[F[_]: Functor](repository: SpreadRepository[F]): SpreadTemplateService[F] =
    new LiveSpreadTemplateService(repository)

  final private class LiveSpreadTemplateService[F[_]: Functor](repository: SpreadRepository[F]) extends SpreadTemplateService[F]:
    override def listAlbumStartTemplates: F[ListAlbumStartTemplatesResult] =
      repository.listAlbumStartTemplates.map:
        case SpreadRepository.ListAlbumStartTemplatesResult.Listed(templates) =>
          ListAlbumStartTemplatesResult.Listed(templates.filter(isAlbumStart).sortBy(_.id.value))
        case SpreadRepository.ListAlbumStartTemplatesResult.Failed(_) => ListAlbumStartTemplatesResult.Failed

    private def isAlbumStart(template: SpreadTemplate): Boolean =
      template.kind match
        case SpreadTemplateKind.AlbumStart => true
        case _                             => false
