package photography.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import fs2.Stream
import org.http4s.*
import org.typelevel.ci.CIString

object FrontendRoutes:
  def routes[F[_]: Async]: HttpRoutes[F] =
    HttpRoutes.of[F]:
      case request if request.method === Method.GET && !isApiPath(request.uri.path) =>
        val path       = request.uri.path.segments.map(_.decoded()).mkString("/")
        val maybeAsset =
          if path.split('/').contains("..") then None
          else Some(if path.isEmpty || !path.contains(".") then "index.html" else path)
        maybeAsset.traverse(assetPath => load[F](assetPath).map(assetPath -> _)).flatMap:
          case None | Some((_, None))         => Async[F].pure(Response[F](Status.NotFound))
          case Some((assetPath, Some(bytes))) =>
            val contentType = Header.Raw(CIString("Content-Type"), mediaType(assetPath))
            Async[F].pure(
              Response[F](
                status = Status.Ok,
                headers = Headers(contentType),
                body = Stream.emits(bytes).covary[F]
              )
            )

  private def load[F[_]: Async](path: String): F[Option[Array[Byte]]] =
    Async[F].blocking(Option(getClass.getResourceAsStream(s"/$path"))).flatMap:
      case None        => Option.empty[Array[Byte]].pure[F]
      case Some(input) =>
        Async[F].blocking:
          try input.readAllBytes()
          finally input.close()
        .map(_.some)

  private def mediaType(path: String): String =
    if path.endsWith(".html") then "text/html; charset=utf-8"
    else if path.endsWith(".js") then "text/javascript; charset=utf-8"
    else if path.endsWith(".css") then "text/css; charset=utf-8"
    else if path.endsWith(".svg") then "image/svg+xml"
    else if path.endsWith(".png") then "image/png"
    else if path.endsWith(".webp") then "image/webp"
    else if path.endsWith(".woff2") then "font/woff2"
    else "application/octet-stream"

  private def isApiPath(path: Uri.Path): Boolean =
    path.segments.headOption.exists(_.decoded() === "api")
