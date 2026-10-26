package photography.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import io.circe.Encoder
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import photography.domain.layout.{SlotBox, SpreadTemplate}
import photography.usecases.SpreadTemplateManager
import photography.usecases.SpreadTemplateManager.ListAlbumStartTemplatesResult

trait SpreadTemplateApi[F[_]]:
  def routes: HttpRoutes[F]

object SpreadTemplateApi:
  def make[F[_]: Async](templates: SpreadTemplateManager[F]): SpreadTemplateApi[F] =
    new LiveSpreadTemplateApi(templates)

  final private class LiveSpreadTemplateApi[F[_]: Async](templates: SpreadTemplateManager[F])
      extends SpreadTemplateApi[F] with Http4sDsl[F]:
    override def routes: HttpRoutes[F] = HttpRoutes.of[F]:
      case GET -> Root / "api" / "templates" / "album-start" =>
        templates.listAlbumStartTemplates.flatMap:
          case listed: ListAlbumStartTemplatesResult.Listed => Ok(listed)
          case ListAlbumStartTemplatesResult.Failed         => InternalServerError(Map("code" -> "service_failure"))

    private given Encoder[(String, SlotBox)] = Encoder.forProduct5("kind", "x", "y", "width", "height"): slot =>
      val (kind, box) = slot
      (kind, box.x: Double, box.y: Double, box.width: Double, box.height: Double)

    private given Encoder[SpreadTemplate.AlbumStart] = Encoder.forProduct3("id", "name", "slots"): template =>
      val slots = template.photoSlots.map(box => "photo" -> box) :+ ("text" -> template.textSlot)
      (template.id.value.toString, template.name: String, slots)

    private given Encoder[ListAlbumStartTemplatesResult.Listed] = Encoder.forProduct1("templates")(_.templates.toList)
