package photography.adapters.system

import cats.effect.IO
import munit.CatsEffectSuite
import photography.capabilities.Clock

import java.time.Instant

final class LiveClockComponentTest extends CatsEffectSuite:

  test("should read a current instant from the live clock"):
    Clock.make[IO].now.map(now => assert(now.isAfter(Instant.parse("2020-01-01T00:00:00Z"))))
