package photography.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import io.circe.{Decoder, Encoder}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import org.http4s.{HttpRoutes, MessageBodyFailure, Request}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import photography.domain.album.{Album, AlbumId, AlbumName}
import photography.domain.layout.{SpreadTemplate, SpreadTemplateSlot}
import photography.usecases.{AlbumService, SpreadTemplateService}
import photography.usecases.AlbumService.*
import photography.usecases.SpreadTemplateService.ListAlbumStartTemplatesResult

trait AlbumApi[F[_]]:
  def routes: HttpRoutes[F]

object AlbumApi:
  def make[F[_]: Async](albums: AlbumService[F], templates: SpreadTemplateService[F]): AlbumApi[F] =
    new LiveAlbumApi(albums, templates)

  final private class LiveAlbumApi[F[_]: Async](albums: AlbumService[F], templates: SpreadTemplateService[F])
      extends AlbumApi[F] with Http4sDsl[F]:
    override def routes: HttpRoutes[F] =
      HttpRoutes.of[F] {
        case request @ GET -> Root / "api" / "albums" :? Page(page) if request.multiParams.get("page").forall(_.size === 1) =>
          list(page)
        case GET -> Root / "api" / "albums"                              => BadRequest(Map("message" -> "Supply page once as a 32-bit integer"))
        case request @ POST -> Root / "api" / "albums"                   => create(request)
        case GET -> Root / "api" / "albums" / AlbumIdVar(id)             => get(id)
        case request @ PATCH -> Root / "api" / "albums" / AlbumIdVar(id) => rename(id, request)
        case DELETE -> Root / "api" / "albums" / AlbumIdVar(id)          => delete(id)
        case (GET | PATCH | DELETE) -> Root / "api" / "albums" / _       => albumNotFound
        case GET -> Root / "api" / "templates" / "album-start"           => listTemplates
      }

    private def list(page: Int) =
      albums.list(page).flatMap:
        case ListAlbumsResult.Listed(selected) => Ok(selected)
        case ListAlbumsResult.Failed           => serviceFailure

    private def create(request: Request[F]) =
      request.as[AlbumName].flatMap(albums.create).flatMap {
        case CreateAlbumResult.Created(album) => Created(album)
        case CreateAlbumResult.Failed         => serviceFailure
      }.recoverWith { case _: MessageBodyFailure => BadRequest(invalidTitle) }

    private def get(id: AlbumId) =
      albums.get(id).flatMap:
        case found: GetAlbumResult.Found => Ok(found)
        case GetAlbumResult.NotFound     => albumNotFound
        case GetAlbumResult.Failed       => serviceFailure

    private def rename(id: AlbumId, request: Request[F]) =
      request.as[AlbumName].flatMap(albums.rename(id, _)).flatMap {
        case RenameAlbumResult.Renamed(album) => Ok(album)
        case RenameAlbumResult.NotFound       => albumNotFound
        case RenameAlbumResult.Failed         => serviceFailure
      }.recoverWith { case _: MessageBodyFailure => BadRequest(invalidTitle) }

    private def delete(id: AlbumId) =
      albums.delete(id).flatMap:
        case DeleteAlbumResult.Deleted  => NoContent()
        case DeleteAlbumResult.NotFound => albumNotFound
        case DeleteAlbumResult.Failed   => serviceFailure

    private def listTemplates =
      templates.listAlbumStartTemplates.flatMap:
        case listed: ListAlbumStartTemplatesResult.Listed => Ok(listed)
        case ListAlbumStartTemplatesResult.Failed         => serviceFailure

    private def albumNotFound  = NotFound(Map("code" -> "album_not_found"))
    private def serviceFailure = InternalServerError(Map("code" -> "service_failure"))
    private val invalidTitle   = Map("message" -> "Expected a JSON object with a nonblank string title")

    private object Page extends QueryParamDecoderMatcherWithDefault[Int]("page", 1)

    private object AlbumIdVar:
      def unapply(value: String): Option[AlbumId] =
        UUIDVar.unapply(value).filter(_.toString.equalsIgnoreCase(value)).map(AlbumId(_))

    private given Decoder[AlbumName] = Decoder.decodeString
      .emap(_.trim.refineEither[Not[Empty] & Not[Blank]])
      .prepare(_.downField("title"))

    private given Encoder[Album] = Encoder.forProduct3("id", "title", "createdAt"): album =>
      (album.id.value.toString, album.title: String, album.createdAt.toString)

    private given Encoder[GetAlbumResult.Found] = Encoder.forProduct4("id", "title", "synopsis", "createdAt"): found =>
      val album = found.album
      (album.id.value.toString, album.title: String, album.synopsis, album.createdAt.toString)

    private given Encoder[AlbumPage] = Encoder.forProduct5("albums", "page", "pageSize", "totalAlbums", "totalPages"): page =>
      (page.albums, page.page, page.pageSize, page.totalAlbums, page.totalPages)

    private given Encoder[SpreadTemplateSlot] = Encoder.forProduct5("kind", "x", "y", "width", "height"): slot =>
      val (kind, box) = slot match
        case SpreadTemplateSlot.Photo(box) => ("photo", box)
        case SpreadTemplateSlot.Text(box)  => ("text", box)
      (kind, box.x: Double, box.y: Double, box.width: Double, box.height: Double)

    private given Encoder[SpreadTemplate] = Encoder.forProduct3("id", "name", "slots"): template =>
      (template.id.value.toString, template.name: String, template.slots.toList)

    private given Encoder[ListAlbumStartTemplatesResult.Listed] = Encoder.forProduct1("templates")(_.templates.toList)
