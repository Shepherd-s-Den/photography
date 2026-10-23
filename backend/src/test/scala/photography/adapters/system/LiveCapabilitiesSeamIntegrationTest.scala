package photography.adapters.system

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import photography.app.capabilities.{Clock, IdGenerator}

import java.time.Instant

final class LiveCapabilitiesSeamIntegrationTest extends CatsEffectSuite:

  test("should generate distinct ids from the live id generator"):
    (IdGenerator.make[IO].nextId, IdGenerator.make[IO].nextId).mapN((first, second) => assertNotEquals(first, second))

  test("should read a current instant from the live clock"):
    Clock.make[IO].now.map(now => assert(now.isAfter(Instant.parse("2020-01-01T00:00:00Z"))))
