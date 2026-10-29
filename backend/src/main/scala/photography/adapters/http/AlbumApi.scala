package photography.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import io.circe.{Decoder, Encoder}
import io.github.iltotore.iron.*
import org.http4s.{HttpRoutes, MessageBodyFailure, Query, QueryParamDecoder, QueryParameterValue, Request}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import photography.domain.album.{Album, AlbumId, AlbumTitle, AlbumYear, AlbumYearConstraint}
import io.github.iltotore.iron.constraint.all.*
import photography.usecases.AlbumManager
import photography.usecases.AlbumManager.*

trait AlbumApi[F[_]]:
  def routes: HttpRoutes[F]

object AlbumApi:
  def make[F[_]: Async](albums: AlbumManager[F]): AlbumApi[F] =
    new LiveAlbumApi(albums)

  final private class LiveAlbumApi[F[_]: Async](albums: AlbumManager[F]) extends AlbumApi[F] with Http4sDsl[F]:
    override def routes: HttpRoutes[F] =
      HttpRoutes.of[F] {
        case request @ GET -> Root / "api" / "albums"                    => list(request.uri.query)
        case request @ POST -> Root / "api" / "albums"                   => create(request)
        case GET -> Root / "api" / "albums" / AlbumIdVar(id)             => get(id)
        case request @ PATCH -> Root / "api" / "albums" / AlbumIdVar(id) => rename(id, request)
        case DELETE -> Root / "api" / "albums" / AlbumIdVar(id)          => delete(id)
        case (GET | PATCH | DELETE) -> Root / "api" / "albums" / _       => respondAlbumNotFound
      }

    private def list(query: Query) = query match
      case Page(page) =>
        albums.list(page = page).flatMap:
          case ListAlbumsResult.Listed(selected) => Ok(selected)
          case ListAlbumsResult.Failed           => respondServiceFailure
      case _ => BadRequest(Map("message" -> "Supply page once as a 32-bit integer"))

    private def create(request: Request[F]) =
      request.as[(AlbumYear, AlbumTitle)].flatMap { case (year, title) => albums.create(year, title) }.flatMap {
        case CreateAlbumResult.Created(album)  => Created(album)
        case CreateAlbumResult.YearAlreadyUsed => BadRequest(Map("message" -> "An album already exists for that year"))
        case CreateAlbumResult.Failed          => respondServiceFailure
      }.recoverWith { case _: MessageBodyFailure => BadRequest(invalidYear) }

    private def get(id: AlbumId) =
      albums.get(id).flatMap:
        case found: GetAlbumResult.Found => Ok(found)
        case GetAlbumResult.NotFound     => respondAlbumNotFound
        case GetAlbumResult.Failed       => respondServiceFailure

    private def rename(id: AlbumId, request: Request[F]) =
      request.as[AlbumTitle].flatMap(title => albums.rename(id, title)).flatMap {
        case RenameResult.Renamed(album) => Ok(album)
        case RenameResult.NotFound       => respondAlbumNotFound
        case RenameResult.Failed         => respondServiceFailure
      }.recoverWith { case _: MessageBodyFailure => BadRequest(invalidTitle) }

    private def delete(id: AlbumId) =
      albums.delete(id).flatMap:
        case DeleteAlbumResult.Deleted  => NoContent()
        case DeleteAlbumResult.NotFound => respondAlbumNotFound
        case DeleteAlbumResult.Failed   => respondServiceFailure

    private def respondAlbumNotFound  = NotFound(Map("code" -> "album_not_found"))
    private def respondServiceFailure = InternalServerError(Map("code" -> "service_failure"))
    private val invalidTitle          = Map("message" -> "Supply a non-blank album title")
    private val invalidYear           = Map("message" -> "Choose a year from 1900 to 2100")

    private object Page:
      def unapply(query: Query): Option[Int] =
        query.pairs.collect { case ("page", value) => value } match
          case Vector()            => 1.some
          case Vector(Some(value)) => QueryParamDecoder[Int].decode(QueryParameterValue(value)).toOption
          case _                   => None

    private object AlbumIdVar:
      def unapply(value: String): Option[AlbumId] =
        UUIDVar.unapply(value).filter(_.toString.equalsIgnoreCase(value)).map(AlbumId(_))

    private given Decoder[AlbumYear] = Decoder.decodeJsonNumber.emap(_.toInt.toRight("Expected an integer year"))
      .emap(_.refineEither[AlbumYearConstraint]).prepare(_.downField("year"))

    private given Decoder[AlbumTitle] = Decoder.decodeString.emap(_.trim.refineEither[Not[Empty] & Not[Blank]]).prepare(_.downField("title"))

    private given Decoder[(AlbumYear, AlbumTitle)] = Decoder[AlbumYear].emap: year =>
      s"Journey of the Shepherds $year".refineEither[Not[Empty] & Not[Blank]].map(title => (year, title))

    private given Encoder[Album] = Encoder.forProduct4("id", "year", "title", "createdAt"): album =>
      (album.id.value.toString, album.year: Int, album.title: String, album.createdAt.toString)

    private given Encoder[GetAlbumResult.Found] = Encoder.forProduct5("id", "year", "title", "synopsis", "createdAt"): found =>
      val album = found.album
      (album.id.value.toString, album.year: Int, album.title: String, album.synopsis, album.createdAt.toString)

    private given Encoder[AlbumPage] = Encoder.forProduct5("albums", "page", "pageSize", "totalAlbums", "totalPages"): page =>
      (page.albums, page.page, page.pageSize, page.totalAlbums, page.totalPages)
