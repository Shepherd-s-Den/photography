package photography.app

import cats.effect.IO
import munit.CatsEffectSuite

final class AppConfigSeamIntegrationTest extends CatsEffectSuite:

  test("should successfully parse the photography config"):
    AppConfig.load[IO].map(_ => ())
