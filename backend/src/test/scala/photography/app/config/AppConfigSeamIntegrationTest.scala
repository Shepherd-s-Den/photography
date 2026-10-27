package photography.app.config

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import pureconfig.ConfigSource

final class AppConfigSeamIntegrationTest extends CatsEffectSuite:

  test("should load the mock application.conf from test resources"):
    AppConfig.load[IO](ConfigSource.resources("app-config-seam/application.conf")).map: config =>
      assertEquals(config.albums.photoRoot: String, "/library/photos")

  test("should reject relative roots before constructing application services"):
    Vector("albums.photo-root" -> "relative/photos", "thumbnails.root" -> "relative/thumbnails").traverse_ { case (key, path) =>
      val source = ConfigSource.string(s"""photography.$key = "$path"""")
        .withFallback(ConfigSource.resources("app-config-seam/application.conf"))

      AppConfig.load[IO](source).attempt.map(result => assert(result.isLeft, clue = key))
    }
