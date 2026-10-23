package photography.app.capabilities

import cats.effect.IO
import munit.CatsEffectSuite

import java.time.Instant

final class CapabilitiesSeamIntegrationTest extends CatsEffectSuite:

  test("should provide unique UUIDs from the live id generator"):
    for
      first  <- IdGenerator.make[IO].nextId
      second <- IdGenerator.make[IO].nextId
    yield assertNotEquals(first, second)

  test("should provide a current UTC instant from the live clock"):
    Clock.make[IO].now.map(now => assert(now.isAfter(Instant.parse("2020-01-01T00:00:00Z"))))
