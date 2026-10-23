package photography.adapters.http

import cats.syntax.all.*
import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.dsl.io.*

final class FrontendRoutesSeamIntegrationTest extends CatsEffectSuite:
  test("should serve the dashboard entry point at the root"):
    val app = FrontendRoutes.routes[IO].orNotFound

    app.run(Request[IO](Method.GET, Uri.unsafeFromString("/"))).flatMap: response =>
      response.as[String].map: body =>
        assertEquals(response.status, Status.Ok)
        assert(body.contains("""src="/assets/index-"""))

  test("should serve the frontend entry point for an album deep link"):
    val app = FrontendRoutes.routes[IO].orNotFound

    app.run(Request[IO](Method.GET, Uri.unsafeFromString("/albums/00000000-0000-4000-8000-000000000001"))).flatMap: response =>
      response.as[String].map: body =>
        assertEquals(response.status, Status.Ok)
        assert(body.contains("""src="/assets/index-"""))
        assertEquals(response.contentType.map(_.mediaType.subType), Some("html"))

  test("should leave API paths for the JSON routes"):
    val api = HttpRoutes.of[IO]:
      case GET -> Root / "api" / "albums" => Ok("""{"albums":[]}""")
    val app = (FrontendRoutes.routes[IO] <+> api).orNotFound

    app.run(Request[IO](Method.GET, Uri.unsafeFromString("/api/albums"))).flatMap: response =>
      response.as[String].map: body =>
        assertEquals(response.status, Status.Ok)
        assertEquals(body, """{"albums":[]}""")

  test("should return not found for missing asset files"):
    val app = FrontendRoutes.routes[IO].orNotFound

    app.run(Request[IO](Method.GET, Uri.unsafeFromString("/assets/missing.js"))).map: response =>
      assertEquals(response.status, Status.NotFound)

  test("should not expose backend classpath resources"):
    val app   = FrontendRoutes.routes[IO].orNotFound
    val paths = Vector("/application.conf", "/db/V1__create_photography_schema.sql", "/META-INF/MANIFEST.MF")

    paths.traverse_ { path =>
      app.run(Request[IO](Method.GET, Uri.unsafeFromString(path))).map: response =>
        assertEquals(response.status, Status.NotFound)
    }

  test("should return not found when the built frontend entry point is missing"):
    val classLoader = new ClassLoader(ClassLoader.getPlatformClassLoader) {}
    val app         = FrontendRoutes.routesWithClassLoader[IO](classLoader).orNotFound

    app.run(Request[IO](Method.GET, Uri.unsafeFromString("/"))).map: response =>
      assertEquals(response.status, Status.NotFound)

  test("should serve the built frontend JavaScript asset"):
    val app = FrontendRoutes.routes[IO].orNotFound

    app.run(Request[IO](Method.GET, Uri.unsafeFromString("/"))).flatMap: response =>
      response.as[String].flatMap: body =>
        val assetPath = """src="([^"]+)"""".r.findFirstMatchIn(body).map(_.group(1))
          .getOrElse(fail("expected the built frontend entry to reference its JavaScript asset"))

        app.run(Request[IO](Method.GET, Uri.unsafeFromString(assetPath))).map: assetResponse =>
          assertEquals(assetResponse.status, Status.Ok)
          assertEquals(assetResponse.contentType.map(_.mediaType.subType), Some("javascript"))

  test("should reject non-GET frontend requests and block traversal paths"):
    val app = FrontendRoutes.routes[IO].orNotFound

    for
      postResponse      <- app.run(Request[IO](Method.POST, Uri.unsafeFromString("/")))
      traversalResponse <- app.run(Request[IO](Method.GET, Uri.unsafeFromString("/assets/%2e%2e/index.html")))
    yield
      assertEquals(postResponse.status, Status.NotFound)
      assertEquals(traversalResponse.status, Status.BadRequest)
