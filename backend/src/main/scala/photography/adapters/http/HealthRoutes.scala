package photography.adapters.http

import cats.effect.Async
import org.http4s.{HttpRoutes, MediaType, Response, Status}
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`

object HealthRoutes:

  def routes[F[_]: Async]: HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    HttpRoutes.of[F]:
      case GET -> Root / "health" =>
        Async[F].pure(
          Response[F](Status.Ok)
            .withEntity("""{"status":"ok"}""")
            .putHeaders(`Content-Type`(MediaType.application.json))
        )
