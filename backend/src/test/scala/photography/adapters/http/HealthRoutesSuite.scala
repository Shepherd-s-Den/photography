package photography.adapters.http

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.{MediaType, Method, Request, Status, Uri}

final class HealthRoutesSuite extends CatsEffectSuite:

  test("GET /health returns JSON readiness"):
    val healthRoutes = HealthRoutes.routes[IO].orNotFound
    val request      = Request[IO](Method.GET, Uri.unsafeFromString("/health"))

    for
      response <- healthRoutes.run(request)
      body     <- response.as[String]
    yield

      assertEquals(response.status, Status.Ok)
      assertEquals(response.contentType.map(_.mediaType), Some(MediaType.application.json))
      assertEquals(body, """{"status":"ok"}""")

  test("unknown paths remain not found"):
    val healthRoutes = HealthRoutes.routes[IO].orNotFound
    val request      = Request[IO](Method.GET, Uri.unsafeFromString("/unknown"))

    healthRoutes.run(request).map: response =>
      assertEquals(response.status, Status.NotFound)
