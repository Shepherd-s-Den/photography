package photography.app.config

import munit.CatsEffectSuite

final class AppConfigSeamIntegrationTest extends CatsEffectSuite:

  test("should load application configuration"):
    AppConfig.make.map(_ => ())
