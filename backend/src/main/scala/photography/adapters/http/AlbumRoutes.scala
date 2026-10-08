package photography.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import org.http4s.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`
import photography.app.album.{AlbumService, AlbumServiceFailure}
import photography.domain.album.AlbumId

import java.util.UUID
import java.util.regex.Pattern

object AlbumRoutes:

  private val uuidPattern =
    Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

  def routes[F[_]: Async](service: AlbumService[F]): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    HttpRoutes.of[F]:
      case request @ GET -> Root / "api" / "albums" =>
        val requestedPage = request.uri.query.params.get("page") match
          case None        => 1.asRight[Unit]
          case Some(value) => value.toIntOption.toRight(())
        requestedPage match
          case Left(_)     => jsonResponse(Status.BadRequest, validationError("Page must be an integer."))
          case Right(page) =>
            service.list(page).flatMap:
              case Right(result) => jsonResponse(Status.Ok, AlbumJson.page(result))
              case Left(failure) => failureResponse(failure)

      case request @ POST -> Root / "api" / "albums" =>
        readTitle(request).flatMap:
          case Left(_)      => jsonResponse(Status.BadRequest, validationError("Request body must contain a title."))
          case Right(title) =>
            service.create(title).flatMap:
              case Right(result) => jsonResponse(Status.Created, AlbumJson.summary(result))
              case Left(failure) => failureResponse(failure)

      case request @ PATCH -> Root / "api" / "albums" / rawId =>
        parseId(rawId) match
          case None     => failureResponse(AlbumServiceFailure.AlbumNotFound)
          case Some(id) =>
            readTitle(request).flatMap:
              case Left(_)      => jsonResponse(Status.BadRequest, validationError("Request body must contain a title."))
              case Right(title) =>
                service.rename(id, title).flatMap:
                  case Right(result) => jsonResponse(Status.Ok, AlbumJson.summary(result))
                  case Left(failure) => failureResponse(failure)

      case GET -> Root / "api" / "albums" / rawId =>
        parseId(rawId) match
          case None     => failureResponse(AlbumServiceFailure.AlbumNotFound)
          case Some(id) =>
            service.get(id).flatMap:
              case Right(result) => jsonResponse(Status.Ok, AlbumJson.composition(result))
              case Left(failure) => failureResponse(failure)

      case DELETE -> Root / "api" / "albums" / rawId =>
        parseId(rawId) match
          case None     => failureResponse(AlbumServiceFailure.AlbumNotFound)
          case Some(id) =>
            service.delete(id).flatMap:
              case Right(_)      => Response[F](Status.NoContent).pure[F]
              case Left(failure) => failureResponse(failure)

  private def readTitle[F[_]: Async](request: Request[F]): F[Either[Unit, String]] =
    request.as[String].attempt.map:
      case Left(_)     => ().asLeft[String]
      case Right(body) =>
        parse(body).toOption
          .flatMap(_.hcursor.get[String]("title").toOption)
          .toRight(())

  private def parseId(rawValue: String): Option[AlbumId] =
    val matcher = uuidPattern.matcher(rawValue)
    Option.when(matcher.matches())(AlbumId(UUID.fromString(rawValue)))

  private def failureResponse[F[_]: Async](failure: AlbumServiceFailure): F[Response[F]] =
    failure match
      case AlbumServiceFailure.InvalidTitle =>
        jsonResponse(Status.BadRequest, validationError("Title must not be blank."))
      case AlbumServiceFailure.AlbumNotFound =>
        jsonResponse(Status.NotFound, error("album_not_found", "Album not found."))
      case AlbumServiceFailure.ServiceFailure =>
        jsonResponse(Status.InternalServerError, error("service_failure", "The request could not be completed."))

  private def validationError(message: String): Json =
    Json.obj("message" -> Json.fromString(message))

  private def error(code: String, message: String): Json =
    Json.obj(
      "code"    -> Json.fromString(code),
      "message" -> Json.fromString(message)
    )

  private def jsonResponse[F[_]: Async](status: Status, body: Json): F[Response[F]] =
    Response[F](status)
      .withEntity(body.noSpaces)
      .putHeaders(`Content-Type`(MediaType.application.json))
      .pure[F]
