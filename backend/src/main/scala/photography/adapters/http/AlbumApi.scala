package photography.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import io.circe.Json
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import org.http4s.{EntityDecoder, EntityEncoder, HttpRoutes, Request, Response}
import org.http4s.circe.{jsonEncoderOf, jsonOf}
import org.http4s.dsl.Http4sDsl
import photography.domain.album.{Album, AlbumId, AlbumName}
import photography.domain.layout.{SpreadTemplate, SpreadTemplateSlot}
import photography.usecases.{AlbumService, SpreadTemplateService}
import photography.usecases.AlbumService.*
import photography.usecases.SpreadTemplateService.ListAlbumStartTemplatesResult

import java.util.UUID
import scala.util.Try

trait AlbumApi[F[_]]:
  def routes: HttpRoutes[F]

object AlbumApi:
  def make[F[_]: Async](albums: AlbumService[F], templates: SpreadTemplateService[F]): AlbumApi[F] =
    new LiveAlbumApi(albums, templates)

  final private class LiveAlbumApi[F[_]: Async](albums: AlbumService[F], templates: SpreadTemplateService[F])
      extends AlbumApi[F] with Http4sDsl[F]:
    override def routes: HttpRoutes[F] = HttpRoutes.of[F]:
      case request @ GET -> Root / "api" / "albums"           => list(request)
      case request @ POST -> Root / "api" / "albums"          => withTitle(request)(create)
      case GET -> Root / "api" / "albums" / rawId             => withId(rawId)(get)
      case request @ PATCH -> Root / "api" / "albums" / rawId => withId(rawId)(id => withTitle(request)(rename(id, _)))
      case DELETE -> Root / "api" / "albums" / rawId          => withId(rawId)(delete)
      case GET -> Root / "api" / "templates" / "album-start"  => listTemplates

    private given EntityDecoder[F, Json] = jsonOf[F, Json]
    private given EntityEncoder[F, Json] = jsonEncoderOf[F, Json]

    private def list(request: Request[F]) =
      parsePage(request) match
        case Left(message) => BadRequest(Json.obj("message" -> Json.fromString(message)))
        case Right(page)   =>
          albums.list(page).flatMap:
            case ListAlbumsResult.Listed(selected) => Ok(encodePage(selected))
            case ListAlbumsResult.Failed           => serviceFailure

    private def create(title: AlbumName) =
      albums.create(title).flatMap:
        case CreateAlbumResult.Created(album) => Created(encodeSummary(album))
        case CreateAlbumResult.Failed         => serviceFailure

    private def get(id: AlbumId) =
      albums.get(id).flatMap:
        case GetAlbumResult.Found(album) => Ok(encodeSummary(album).deepMerge(Json.obj("synopsis" -> Json.fromString(album.synopsis))))
        case GetAlbumResult.NotFound     => albumNotFound
        case GetAlbumResult.Failed       => serviceFailure

    private def rename(id: AlbumId, title: AlbumName) =
      albums.rename(id, title).flatMap:
        case RenameAlbumResult.Renamed(album) => Ok(encodeSummary(album))
        case RenameAlbumResult.NotFound       => albumNotFound
        case RenameAlbumResult.Failed         => serviceFailure

    private def delete(id: AlbumId) =
      albums.delete(id).flatMap:
        case DeleteAlbumResult.Deleted  => NoContent()
        case DeleteAlbumResult.NotFound => albumNotFound
        case DeleteAlbumResult.Failed   => serviceFailure

    private def listTemplates =
      templates.listAlbumStartTemplates.flatMap:
        case ListAlbumStartTemplatesResult.Listed(catalog) => Ok(Json.obj("templates" -> Json.fromValues(catalog.toList.map(encodeTemplate))))
        case ListAlbumStartTemplatesResult.Failed          => serviceFailure

    private def withTitle(request: Request[F])(respond: AlbumName => F[Response[F]]) =
      request.attemptAs[Json].value.flatMap:
        case Left(_)     => BadRequest(Json.obj("message" -> Json.fromString("Expected a JSON object with a string title")))
        case Right(json) =>
          json.hcursor.get[String]("title").leftMap(_.message).flatMap(_.trim.refineEither[Not[Empty] & Not[Blank]]) match
            case Left(_)      => BadRequest(Json.obj("message" -> Json.fromString("Title must be a nonblank string")))
            case Right(title) => respond(title)

    private def withId(rawId: String)(respond: AlbumId => F[Response[F]]) =
      Try(UUID.fromString(rawId)).toOption.filter(_.toString.equalsIgnoreCase(rawId)) match
        case None        => albumNotFound
        case Some(value) => respond(AlbumId(value))

    private def parsePage(request: Request[F]) =
      request.uri.query.multiParams.get("page") match
        case None         => 1.asRight[String]
        case Some(values) =>
          values.toList match
            case value :: Nil => value.toIntOption.toRight("Page must be a 32-bit integer")
            case _            => "Supply page once".asLeft[Int]

    private def albumNotFound  = NotFound(Json.obj("code" -> Json.fromString("album_not_found")))
    private def serviceFailure = InternalServerError(Json.obj("code" -> Json.fromString("service_failure")))

    private def encodeSummary(album: Album) = Json.obj(
      "id"        -> Json.fromString(album.id.value.toString),
      "title"     -> Json.fromString(album.title),
      "createdAt" -> Json.fromString(album.createdAt.toString)
    )

    private def encodePage(page: AlbumPage) = Json.obj(
      "albums"      -> Json.fromValues(page.albums.map(encodeSummary)),
      "page"        -> Json.fromInt(page.page),
      "pageSize"    -> Json.fromInt(page.pageSize),
      "totalAlbums" -> Json.fromLong(page.totalAlbums),
      "totalPages"  -> Json.fromInt(page.totalPages)
    )

    private def encodeTemplate(template: SpreadTemplate) = Json.obj(
      "id"    -> Json.fromString(template.id.value.toString),
      "name"  -> Json.fromString(template.name),
      "slots" -> Json.fromValues(template.slots.toList.map(encodeSlot))
    )

    private def encodeSlot(slot: SpreadTemplateSlot) =
      val (kind, box) = slot match
        case SpreadTemplateSlot.Photo(box) => ("photo", box)
        case SpreadTemplateSlot.Text(box)  => ("text", box)
      Json.obj(
        "kind"   -> Json.fromString(kind),
        "x"      -> Json.fromDoubleOrString(box.x),
        "y"      -> Json.fromDoubleOrString(box.y),
        "width"  -> Json.fromDoubleOrString(box.width),
        "height" -> Json.fromDoubleOrString(box.height)
      )
