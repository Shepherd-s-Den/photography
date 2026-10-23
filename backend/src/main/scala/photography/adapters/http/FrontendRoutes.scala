package photography.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import org.http4s.*
import org.http4s.server.staticcontent.ResourceServiceBuilder

object FrontendRoutes:
  def routes[F[_]: Async]: HttpRoutes[F] =
    routesWithClassLoader(getClass.getClassLoader)

  private[http] def routesWithClassLoader[F[_]: Async](classLoader: ClassLoader): HttpRoutes[F] =
    val resources = ResourceServiceBuilder[F]("/frontend")
      .withClassLoader(Some(classLoader))
      .toRoutes

    HttpRoutes.of[F]:
      case request if request.method === Method.GET && !isApiPath(request.uri.path) =>
        resources(request).getOrElseF:
          if isSpaPath(request.uri.path) then
            StaticFile.fromResource("/frontend/index.html", Some(request), classloader = Some(classLoader))
              .getOrElseF(Async[F].pure(Response[F](Status.NotFound)))
          else Async[F].pure(Response[F](Status.NotFound))

  private def isSpaPath(path: Uri.Path) =
    !path.segments.exists(_.decoded().contains("."))

  private def isApiPath(path: Uri.Path) =
    path.segments.headOption.exists(_.decoded() === "api")
