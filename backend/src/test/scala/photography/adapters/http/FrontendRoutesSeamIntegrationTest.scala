package photography.adapters.http

import cats.syntax.all.*
import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.dsl.io.*

final class FrontendRoutesSeamIntegrationTest extends CatsEffectSuite:
  test("should serve the frontend entry point for an album deep link"):
    val app = FrontendRoutes.routes[IO].orNotFound

    app.run(Request[IO](Method.GET, Uri.unsafeFromString("/albums/00000000-0000-4000-8000-000000000001"))).flatMap: response =>
      response.as[String].map: body =>
        assertEquals(response.status, Status.Ok)
        assert(body.contains("Test frontend entry"))
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

  test("should serve assets with their matching content types"):
    val app    = FrontendRoutes.routes[IO].orNotFound
    val assets = Vector(
      "app.js"      -> "javascript",
      "app.css"     -> "css",
      "logo.svg"    -> "svg+xml",
      "thumb.png"   -> "png",
      "thumb.webp"  -> "webp",
      "font.woff2"  -> "woff2",
      "unknown.bin" -> "octet-stream"
    )

    assets.traverse_ { case (path, expectedSubtype) =>
      app.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/$path"))).map: response =>
        assertEquals(response.status, Status.Ok)
        assertEquals(response.contentType.map(_.mediaType.subType), Some(expectedSubtype))
    }

  test("should reject non-GET frontend requests and traversal paths"):
    val app = FrontendRoutes.routes[IO].orNotFound

    for
      postResponse      <- app.run(Request[IO](Method.POST, Uri.unsafeFromString("/")))
      traversalResponse <- app.run(Request[IO](Method.GET, Uri.unsafeFromString("/../index.html")))
    yield
      assertEquals(postResponse.status, Status.NotFound)
      assertEquals(traversalResponse.status, Status.NotFound)
