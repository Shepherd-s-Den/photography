package photography.capabilities

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite

final class IdGeneratorSeamIntegrationTest extends CatsEffectSuite:

  test("should produce a distinct id on each call"):
    (IdGenerator.make[IO].nextId, IdGenerator.make[IO].nextId).mapN((first, second) => assertNotEquals(first, second))
