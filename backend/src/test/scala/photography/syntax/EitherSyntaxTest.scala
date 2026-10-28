package photography.syntax

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import photography.syntax.EitherSyntax.*
import photography.syntax.EitherSyntax.Pure.*
import java.util.concurrent.atomic.AtomicInteger

final class EitherSyntaxTest extends CatsEffectSuite:
  test("should preserve both alternatives when lifting pure or effectful Either values"):
    Vector(7.asRight[String], "rejected".asLeft[Int]).traverse_ { value =>
      for
        pureResult      <- value.eitherT[IO].value
        effectfulResult <- value.pure[IO].eitherT.value
      yield
        assertEquals(pureResult, value)
        assertEquals(effectfulResult, value)
    }

  test("should suspend and repeat lifted effects while preserving their failures"):
    val executions = new AtomicInteger(0)
    val work       = IO(executions.incrementAndGet()).lift[String]
    val error      = new IllegalStateException("Effect failed")
    val failed     = IO.raiseError[Int](error).lift[String]

    for
      before  <- IO(executions.get())
      first   <- work.value
      second  <- work.value
      failure <- failed.value.attempt
    yield
      assertEquals(before, 0)
      assertEquals(first, 1.asRight[String])
      assertEquals(second, 2.asRight[String])
      assertEquals(failure, error.asLeft[Either[String, Int]])
