package photography.app.config

import cats.effect.IO
import munit.CatsEffectSuite
import pureconfig.ConfigSource

final class AppConfigSeamIntegrationTest extends CatsEffectSuite:

  test("should load the mock application.conf from test resources"):
    AppConfig.load[IO](ConfigSource.resources("app-config-seam/application.conf")).map: config =>
      assertEquals(config.albums.photoRoot: String, "/library/photos")

  test("should reject a relative photo root before constructing application managers"):
    val source = ConfigSource.string("photography.albums.photo-root = \"relative/photos\"")
      .withFallback(ConfigSource.resources("app-config-seam/application.conf"))

    AppConfig.load[IO](source).attempt.map(result => assert(result.isLeft))
