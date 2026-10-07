package photography.adapters.http

import cats.syntax.all.*
import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.{MediaType, Method, Request, Status, Uri}

final class HealthRoutesSeamIntegrationTest extends CatsEffectSuite:

  test("should return JSON readiness for GET /health"):
    val healthRoutes = HealthRoutes.routes[IO].orNotFound
    val request      = Request[IO](Method.GET, Uri.unsafeFromString("/health"))

    for
      response <- healthRoutes.run(request)
      body     <- response.as[String]
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(response.contentType.map(_.mediaType), MediaType.application.json.some)
      assertEquals(body, """{"status":"ok"}""")

  test("should return not found for unknown paths"):
    val healthRoutes = HealthRoutes.routes[IO].orNotFound
    val request      = Request[IO](Method.GET, Uri.unsafeFromString("/unknown"))

    healthRoutes.run(request).map: response =>
      assertEquals(response.status, Status.NotFound)
