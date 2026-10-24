package photography.app.config

import cats.effect.IO
import munit.CatsEffectSuite

final class AppConfigSeamIntegrationTest extends CatsEffectSuite:

  test("should load the default album page size of six"):
    AppConfig.load[IO].map(config => assertEquals(config.albums.pageSize, 6))
