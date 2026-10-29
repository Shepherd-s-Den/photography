package photography.adapters.http

import org.http4s.HttpRoutes

trait AlbumSpreadApi[F[_]]:
  def routes: HttpRoutes[F]
