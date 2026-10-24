package photography.app.config

import cats.effect.IO
import munit.CatsEffectSuite
import pureconfig.ConfigSource

final class AppConfigSeamIntegrationTest extends CatsEffectSuite:

  test("should load the mock application.conf from test resources"):
    AppConfig.load[IO](ConfigSource.resources("app-config-seam/application.conf")).void
