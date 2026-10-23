package photography.adapters.http

import cats.data.Validated
import cats.effect.Async
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.parser.parse
import io.circe.syntax.*
import org.http4s.*
import org.http4s.dsl.Http4sDsl
import org.http4s.dsl.impl.{OptionalValidatingQueryParamDecoderMatcher, UUIDVar}
import org.http4s.headers.`Content-Type`
import photography.app.album.{AlbumPage, AlbumService, AlbumServiceFailure}
import photography.domain.album.{Album, AlbumId}

final private case class TitleRequest(title: String)
final private case class AlbumResource(id: String, title: String, synopsis: String, createdAt: String)
final private case class AlbumPageResource(
    albums: Vector[AlbumResource],
    page: Int,
    pageSize: Int,
    totalAlbums: Long,
    totalPages: Int
)
final private case class ValidationError(message: String)
final private case class ServiceError(code: String, message: String)

object AlbumRoutes:

  private object PageQuery extends OptionalValidatingQueryParamDecoderMatcher[Int]("page")

  private given Decoder[TitleRequest]               = deriveDecoder[TitleRequest]
  private given Encoder.AsObject[AlbumResource]     = deriveEncoder[AlbumResource]
  private given Encoder.AsObject[AlbumPageResource] = deriveEncoder[AlbumPageResource]
  private given Encoder.AsObject[ValidationError]   = deriveEncoder[ValidationError]
  private given Encoder.AsObject[ServiceError]      = deriveEncoder[ServiceError]

  def routes[F[_]: Async](service: AlbumService[F]): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    HttpRoutes.of[F]:
      case GET -> Root / "api" / "albums" :? PageQuery(page) =>
        page match
          case Some(Validated.Invalid(_))   => buildJsonResponse(Status.BadRequest, buildValidationError("Page must be an integer."))
          case None                         => list(service, 1)
          case Some(Validated.Valid(value)) => list(service, value)

      case request @ POST -> Root / "api" / "albums" =>
        readTitle(request).flatMap:
          case Left(_)      => buildJsonResponse(Status.BadRequest, buildValidationError("Request body must contain a title."))
          case Right(title) =>
            service.create(title).flatMap:
              case Right(album)  => buildJsonResponse(Status.Created, buildAlbumResource(album).asJson)
              case Left(failure) => buildFailureResponse(failure)

      case request @ PATCH -> Root / "api" / "albums" / UUIDVar(idValue) =>
        readTitle(request).flatMap:
          case Left(_)      => buildJsonResponse(Status.BadRequest, buildValidationError("Request body must contain a title."))
          case Right(title) =>
            service.rename(AlbumId(idValue), title).flatMap:
              case Right(album)  => buildJsonResponse(Status.Ok, buildAlbumResource(album).asJson)
              case Left(failure) => buildFailureResponse(failure)

      case GET -> Root / "api" / "albums" / UUIDVar(idValue) =>
        service.get(AlbumId(idValue)).flatMap:
          case Right(album)  => buildJsonResponse(Status.Ok, buildAlbumResource(album).asJson)
          case Left(failure) => buildFailureResponse(failure)

      case DELETE -> Root / "api" / "albums" / UUIDVar(idValue) =>
        service.delete(AlbumId(idValue)).flatMap:
          case Right(_)      => Response[F](Status.NoContent).pure[F]
          case Left(failure) => buildFailureResponse(failure)

      case GET -> Root / "api" / "albums" / _ =>
        buildFailureResponse(AlbumServiceFailure.AlbumNotFound)

      case PATCH -> Root / "api" / "albums" / _ =>
        buildFailureResponse(AlbumServiceFailure.AlbumNotFound)

      case DELETE -> Root / "api" / "albums" / _ =>
        buildFailureResponse(AlbumServiceFailure.AlbumNotFound)

  private def list[F[_]: Async](service: AlbumService[F], page: Int) =
    service.list(page).flatMap:
      case Right(result) => buildJsonResponse(Status.Ok, buildAlbumPageResource(result).asJson)
      case Left(failure) => buildFailureResponse(failure)

  private def readTitle[F[_]: Async](request: Request[F]) =
    request.as[String].attempt.flatMap:
      case Left(_: DecodeFailure) => Async[F].pure(().asLeft[String])
      case Left(failure)          => Async[F].raiseError[Either[Unit, String]](failure)
      case Right(body)            =>
        Async[F].pure(
          parse(body).flatMap(_.as[TitleRequest]).map(_.title).leftMap(_ => ())
        )

  private def buildAlbumResource(album: Album) =
    val title: String = album.title
    AlbumResource(
      id = album.id.value.toString,
      title = title,
      synopsis = album.synopsis,
      createdAt = album.createdAt.toString
    )

  private def buildAlbumPageResource(page: AlbumPage) =
    AlbumPageResource(
      albums = page.albums.map(buildAlbumResource),
      page = page.page,
      pageSize = page.pageSize,
      totalAlbums = page.totalAlbums,
      totalPages = page.totalPages
    )

  private def buildFailureResponse[F[_]: Async](failure: AlbumServiceFailure) =
    failure match
      case AlbumServiceFailure.InvalidTitle =>
        buildJsonResponse(Status.BadRequest, buildValidationError("Title must not be blank."))
      case AlbumServiceFailure.AlbumNotFound =>
        buildJsonResponse(Status.NotFound, buildError("album_not_found", "Album not found."))
      case AlbumServiceFailure.ServiceFailure =>
        buildJsonResponse(Status.InternalServerError, buildError("service_failure", "The request could not be completed."))

  private def buildValidationError(message: String) =
    ValidationError(message).asJson

  private def buildError(code: String, message: String) =
    ServiceError(code, message).asJson

  private def buildJsonResponse[F[_]: Async](status: Status, body: Json) =
    Response[F](status)
      .withEntity(body.noSpaces)
      .putHeaders(`Content-Type`(MediaType.application.json))
      .pure[F]
