package photography.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import io.circe.{Decoder, Encoder}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import org.http4s.{HttpRoutes, MessageBodyFailure, Query, QueryParamDecoder, QueryParameterValue, Request}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import photography.domain.album.{Album, AlbumId, AlbumName}
import photography.usecases.AlbumService
import photography.usecases.AlbumService.*

trait AlbumApi[F[_]]:
  def routes: HttpRoutes[F]

object AlbumApi:
  def make[F[_]: Async](albums: AlbumService[F]): AlbumApi[F] =
    new LiveAlbumApi(albums)

  final private class LiveAlbumApi[F[_]: Async](albums: AlbumService[F]) extends AlbumApi[F] with Http4sDsl[F]:
    override def routes: HttpRoutes[F] =
      HttpRoutes.of[F] {
        case request @ GET -> Root / "api" / "albums"                    => list(request.uri.query)
        case request @ POST -> Root / "api" / "albums"                   => create(request)
        case GET -> Root / "api" / "albums" / AlbumIdVar(id)             => get(id)
        case request @ PATCH -> Root / "api" / "albums" / AlbumIdVar(id) => rename(id, request)
        case DELETE -> Root / "api" / "albums" / AlbumIdVar(id)          => delete(id)
        case (GET | PATCH | DELETE) -> Root / "api" / "albums" / _       => albumNotFound
      }

    private def list(query: Query) = query match
      case Page(page) =>
        albums.list(page).flatMap:
          case ListAlbumsResult.Listed(selected) => Ok(selected)
          case ListAlbumsResult.Failed           => serviceFailure
      case _ => BadRequest(Map("message" -> "Supply page once as a 32-bit integer"))

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

    private def albumNotFound  = NotFound(Map("code" -> "album_not_found"))
    private def serviceFailure = InternalServerError(Map("code" -> "service_failure"))
    private val invalidTitle   = Map("message" -> "Expected a JSON object with a nonblank string title")

    private object Page:
      def unapply(query: Query): Option[Int] =
        // multiParams drops valueless repetitions, so enforce cardinality before decoding.
        query.pairs.collect { case ("page", value) => value } match
          case Vector()            => 1.some
          case Vector(Some(value)) => QueryParamDecoder[Int].decode(QueryParameterValue(value)).toOption
          case _                   => None

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
