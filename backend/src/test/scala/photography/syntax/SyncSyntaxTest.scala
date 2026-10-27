package photography.syntax

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import photography.syntax.SyncSyntax.*
import java.util.concurrent.atomic.AtomicInteger

final class SyncSyntaxTest extends CatsEffectSuite:
  test("should suspend blocking work and repeat it for each execution"):
    val executions = new AtomicInteger(0)
    val work       = executions.incrementAndGet().blocking[IO]

    for
      before <- IO(executions.get())
      first  <- work
      second <- work
    yield assertEquals((before, first, second), (0, 1, 2))

  test("should capture blocking failures in the effect"):
    val work = require(requirement = false, message = "expected").blocking[IO]

    work.attempt.map: actual =>
      assertEquals(actual.leftMap(_.getMessage), "requirement failed: expected".asLeft[Unit])
