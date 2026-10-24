package photography.adapters.http

import cats.effect.Async
import cats.data.{Kleisli, OptionT}
import cats.syntax.all.*
import org.http4s.{HttpApp, HttpRoutes, MediaType, Method, Request, Response, StaticFile, Status}
import org.http4s.headers.`Content-Type`
import org.http4s.server.middleware.DefaultHead
import org.http4s.server.staticcontent.ResourceServiceBuilder

trait StaticSite[F[_]]:
  def app: HttpApp[F]

object StaticSite:
  def make[F[_]: Async](api: HttpRoutes[F], assetRoot: String): StaticSite[F] =
    new LiveStaticSite(api, assetRoot)

final private class LiveStaticSite[F[_]: Async](api: HttpRoutes[F], assetRoot: String) extends StaticSite[F]:
  override val app: HttpApp[F] = (api <+> missingApi <+> DefaultHead(frontend)).orNotFound

  private def missingApi = HttpRoutes.of[F]:
    case request if request.pathInfo.segments.headOption.exists(_.decoded() === "api") =>
      Response[F](Status.NotFound).withEntity("""{"code":"not_found"}""").putHeaders(`Content-Type`(MediaType.application.json)).pure[F]

  private def frontend =
    val resources = ResourceServiceBuilder[F](assetRoot).toRoutes
    Kleisli { (request: Request[F]) =>
      val segments = request.pathInfo.segments.map(_.decoded())
      val reserved = segments.headOption.contains("health") || segments.toList === List("assets")
      if request.method =!= Method.GET || reserved then OptionT.none[F, Response[F]]
      else resources(request).orElse(entryPoint(request))
    }

  private def entryPoint(request: Request[F]) =
    val segments   = request.pathInfo.segments.map(_.decoded())
    val navigation = !segments.exists(_.contains(".")) && !segments.headOption.contains("assets")
    if navigation then StaticFile.fromResource(s"$assetRoot/index.html", request.some) else OptionT.none[F, Response[F]]
