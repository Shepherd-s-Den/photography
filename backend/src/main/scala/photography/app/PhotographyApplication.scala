package photography.app

import cats.effect.{Async, Resource}
import fs2.io.net.Network
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import photography.adapters.http.HealthRoutes

import com.comcast.ip4s.Port

object PhotographyApplication:

  def make[F[_]: Async: Network](config: AppConfig): Resource[F, Server] =
    for
      resources <- AppResourcesBuilder.make(config).build[F]
      _         <- Resource.eval(resources.flyway.migrate)
      server    <- startServer(config.http)
    yield server

  private def startServer[F[_]: Async: Network](config: HttpServerConfig): Resource[F, Server] =
    Port.fromInt(config.port) match
      case Some(port) =>
        EmberServerBuilder
          .default[F]
          .withHost(config.host)
          .withPort(port)
          .withHttpApp(HealthRoutes.routes[F].orNotFound)
          .build
      case None =>
        Resource.eval(
          Async[F].raiseError[Server](new IllegalArgumentException("HTTP port must be in 1..65535"))
        )
