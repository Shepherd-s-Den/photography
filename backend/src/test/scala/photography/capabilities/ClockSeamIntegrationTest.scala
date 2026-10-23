package photography.capabilities

import cats.effect.IO
import munit.CatsEffectSuite

import java.time.Instant

final class ClockSeamIntegrationTest extends CatsEffectSuite:

  test("should read the current instant from the live clock"):
    val before = Instant.now()
    Clock.make[IO].now.map(now => assert(!now.isBefore(before) && !now.isAfter(Instant.now())))
