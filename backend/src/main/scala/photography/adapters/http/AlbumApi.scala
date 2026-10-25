package photography.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.github.iltotore.iron.*
import org.http4s.{HttpRoutes, MessageBodyFailure, Query, QueryParamDecoder, QueryParameterValue, Request}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import photography.domain.album.{Album, AlbumId, AlbumYear, AlbumYearConstraint}
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
        case request @ GET -> Root / "api" / "albums"                           => list(request.uri.query)
        case request @ POST -> Root / "api" / "albums"                          => create(request)
        case GET -> Root / "api" / "albums" / AlbumIdVar(id)                    => get(id)
        case request @ PATCH -> Root / "api" / "albums" / AlbumIdVar(id)        => rename(id, request)
        case DELETE -> Root / "api" / "albums" / AlbumIdVar(id)                 => delete(id)
        case request @ PUT -> Root / "api" / "albums" / AlbumIdVar(id) / "year" => changeYear(id, request)
        case PUT -> Root / "api" / "albums" / _ / "year"                        => albumNotFound
        case (GET | PATCH | DELETE) -> Root / "api" / "albums" / _              => albumNotFound
      }

    private def list(query: Query) = query match
      case Page(page) =>
        albums.list(page).flatMap:
          case ListAlbumsResult.Listed(selected) => Ok(selected)
          case ListAlbumsResult.Failed           => serviceFailure
      case _ => BadRequest(Map("message" -> "Supply page once as a 32-bit integer"))

    private def create(request: Request[F]) =
      request.as[AlbumYear].flatMap(albums.create).flatMap {
        case CreateAlbumResult.Created(album)  => Created(album)
        case CreateAlbumResult.YearAlreadyUsed => BadRequest(Map("message" -> "An album already exists for that year"))
        case CreateAlbumResult.Failed          => serviceFailure
      }.recoverWith { case _: MessageBodyFailure => BadRequest(invalidYear) }

    private def get(id: AlbumId) =
      albums.get(id).flatMap:
        case found: GetAlbumResult.Found => Ok(found)
        case GetAlbumResult.NotFound     => albumNotFound
        case GetAlbumResult.Failed       => serviceFailure

    private def rename(id: AlbumId, request: Request[F]) =
      given Decoder[AlbumYear] = Decoder.decodeString.emap(canonicalYear).prepare(_.downField("title"))
      request.as[AlbumYear].flatMap { proposed =>
        albums.get(id).flatMap:
          case GetAlbumResult.Found(album) if album.year.compare(proposed) === 0 => Ok(album)
          case GetAlbumResult.Found(_)                                           => BadRequest(invalidTitle)
          case GetAlbumResult.NotFound                                           => albumNotFound
          case GetAlbumResult.Failed                                             => serviceFailure
      }.recoverWith { case _: MessageBodyFailure => BadRequest(invalidTitle) }

    private def changeYear(id: AlbumId, request: Request[F]) =
      request.as[AlbumYear].flatMap(albums.changeYear(id, _)).flatMap {
        case ChangeYearResult.Changed(album)  => Ok(Map("year" -> Json.fromInt(album.year), "title" -> Json.fromString(renderTitle(album.year))))
        case ChangeYearResult.HasChapters     => BadRequest(Map("message" -> "An album with chapters cannot change year"))
        case ChangeYearResult.YearAlreadyUsed => BadRequest(Map("message" -> "An album already exists for that year"))
        case ChangeYearResult.NotFound        => albumNotFound
        case ChangeYearResult.Failed          => serviceFailure
      }.recoverWith { case _: MessageBodyFailure => BadRequest(invalidYear) }

    private def delete(id: AlbumId) =
      albums.delete(id).flatMap:
        case DeleteAlbumResult.Deleted  => NoContent()
        case DeleteAlbumResult.NotFound => albumNotFound
        case DeleteAlbumResult.Failed   => serviceFailure

    private def albumNotFound  = NotFound(Map("code" -> "album_not_found"))
    private def serviceFailure = InternalServerError(Map("code" -> "service_failure"))
    private val invalidTitle   = Map("message" -> "Album titles must match Journey of the Shepherds followed by the album year")
    private val invalidYear    = Map("message" -> "Choose a year from 1900 to 2100")

    private def canonicalYear(title: String) =
      title.trim match
        case s"Journey of the Shepherds $value" =>
          value.toIntOption.toRight("Expected a canonical album title").flatMap(_.refineEither[AlbumYearConstraint])
            .filterOrElse(year => title.trim === renderTitle(year), "Expected a canonical album title")
        case _ => "Expected a canonical album title".asLeft[AlbumYear]

    private def renderTitle(year: AlbumYear) = s"Journey of the Shepherds $year"

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

    private given Decoder[AlbumYear] = Decoder.decodeJsonNumber.emap(_.toInt.toRight("Expected an integer year"))
      .emap(_.refineEither[AlbumYearConstraint]).prepare(_.downField("year"))

    private given Encoder[Album] = Encoder.forProduct4("id", "year", "title", "createdAt"): album =>
      (album.id.value.toString, album.year: Int, renderTitle(album.year), album.createdAt.toString)

    private given Encoder[GetAlbumResult.Found] = Encoder.forProduct5("id", "year", "title", "synopsis", "createdAt"): found =>
      val album = found.album
      (album.id.value.toString, album.year: Int, renderTitle(album.year), album.synopsis, album.createdAt.toString)

    private given Encoder[AlbumPage] = Encoder.forProduct5("albums", "page", "pageSize", "totalAlbums", "totalPages"): page =>
      (page.albums, page.page, page.pageSize, page.totalAlbums, page.totalPages)
