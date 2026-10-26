package photography.capabilities

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite

final class IdGeneratorSeamIntegrationTest extends CatsEffectSuite:

  test("should produce a distinct id on each call"):
    (IdGenerator.make[IO].generateId, IdGenerator.make[IO].generateId).mapN((first, second) => assertNotEquals(first, second))
