package photography.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import io.circe.Json
import org.http4s.{CacheDirective, HttpRoutes}
import org.http4s.headers.`Cache-Control`
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import photography.domain.album.*
import photography.ports.album.{AlbumResources, CountryRepository}
import java.util.UUID

object AlbumResourcesApi:
  def make[F[_]: Async](albums: AlbumResources[F], countries: CountryRepository[F], photoRoot: PhotoSource): HttpRoutes[F] =
    new LiveAlbumResourcesApi(albums, countries, photoRoot).routes

  final private class LiveAlbumResourcesApi[F[_]: Async](albums: AlbumResources[F], countries: CountryRepository[F], photoRoot: PhotoSource)
      extends Http4sDsl[F]:
    private def found(value: Json) = Ok(value).map(_.putHeaders(`Cache-Control`(CacheDirective.`no-store`)))
    private def missing            = NotFound(Map("code" -> "album_not_found"))
    private def failed             = InternalServerError(Map("code" -> "service_failure"))
    private object CanonicalId:
      def unapply(value: String): Option[UUID] =
        Either.catchOnly[IllegalArgumentException](UUID.fromString(value)).toOption.filter(_.toString === value)
    val routes: HttpRoutes[F] = HttpRoutes.of[F] {
      case GET -> Root / "api" / "albums" / CanonicalId(id) =>
        albums.metadata(AlbumId(id)).flatMap:
          case Right(Some(value)) => found(Json.obj(
              "id"        -> Json.fromString(value.id.value.toString),
              "year"      -> Json.fromInt(value.year),
              "title"     -> Json.fromString(value.title),
              "synopsis"  -> Json.fromString(value.synopsis),
              "createdAt" -> Json.fromString(value.createdAt.toString)
            ))
          case Right(None) => missing
          case Left(_)     => failed
      case GET -> Root / "api" / "albums" / _                            => missing
      case GET -> Root / "api" / "albums" / CanonicalId(id) / "chapters" =>
        albums.chapters(AlbumId(id)).flatMap:
          case Right(Some(values)) => found(Json.obj("chapters" -> Json.arr(values.map { value =>
              Json.obj(
                "id"           -> Json.fromString(value.id.value.toString),
                "kind"         -> Json.fromString(if (value.name: String) === "Home" then "home" else "vacation"),
                "name"         -> Json.fromString(value.name),
                "countries"    -> Json.arr(value.countries.map(id => Json.fromString(id.value.toString))*),
                "start"        -> Json.fromString(value.start.toString),
                "end"          -> Json.fromString(value.end.toString),
                "sourceFolder" -> Json.fromString(value.sourceFolder),
                "description"  -> Json.fromString(value.description)
              )
            }*)))
          case Right(None) => missing
          case Left(_)     => failed
      case GET -> Root / "api" / "albums" / _ / "chapters"                  => missing
      case GET -> Root / "api" / "albums" / CanonicalId(id) / "composition" =>
        albums.composition(AlbumId(id)).flatMap:
          case Right(Some(values)) => found(Json.obj("spreads" -> Json.arr(values.map(value =>
              Json.obj(
                "id"      -> Json.fromString(value.id.value.toString),
                "chapter" -> value.chapter.fold(Json.Null)(id => Json.fromString(id.value.toString))
              )
            )*)))
          case Right(None) => missing
          case Left(_)     => failed
      case GET -> Root / "api" / "albums" / _ / "composition" => missing
      case GET -> Root / "api" / "countries"                  =>
        countries.list.flatMap:
          case CountryRepository.ListResult.Listed(values) => found(Json.obj("countries" -> Json.arr(values.toList.map { value =>
              Json.obj("id" -> Json.fromString(value.id.value.toString), "name" -> Json.fromString(value.name), "code" -> Json.fromString(value.code))
            }*)))
          case CountryRepository.ListResult.Failed => failed
      case GET -> Root / "api" / "photo-source" => found(Json.obj("photoRoot" -> Json.fromString(photoRoot)))
    }
