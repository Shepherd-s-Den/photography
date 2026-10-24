package photography.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import io.circe.Encoder
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import photography.domain.layout.{SpreadTemplate, SpreadTemplateSlot}
import photography.usecases.SpreadTemplateService
import photography.usecases.SpreadTemplateService.ListAlbumStartTemplatesResult

trait SpreadTemplateApi[F[_]]:
  def routes: HttpRoutes[F]

object SpreadTemplateApi:
  def make[F[_]: Async](templates: SpreadTemplateService[F]): SpreadTemplateApi[F] =
    new LiveSpreadTemplateApi(templates)

  final private class LiveSpreadTemplateApi[F[_]: Async](templates: SpreadTemplateService[F])
      extends SpreadTemplateApi[F] with Http4sDsl[F]:
    override def routes: HttpRoutes[F] = HttpRoutes.of[F]:
      case GET -> Root / "api" / "templates" / "album-start" =>
        templates.listAlbumStartTemplates.flatMap:
          case listed: ListAlbumStartTemplatesResult.Listed => Ok(listed)
          case ListAlbumStartTemplatesResult.Failed         => InternalServerError(Map("code" -> "service_failure"))

    private given Encoder[SpreadTemplateSlot] = Encoder.forProduct5("kind", "x", "y", "width", "height"): slot =>
      val (kind, box) = slot match
        case SpreadTemplateSlot.Photo(box) => ("photo", box)
        case SpreadTemplateSlot.Text(box)  => ("text", box)
      (kind, box.x: Double, box.y: Double, box.width: Double, box.height: Double)

    private given Encoder[SpreadTemplate] = Encoder.forProduct3("id", "name", "slots"): template =>
      (template.id.value.toString, template.name: String, template.slots.toList)

    private given Encoder[ListAlbumStartTemplatesResult.Listed] = Encoder.forProduct1("templates")(_.templates.toList)
