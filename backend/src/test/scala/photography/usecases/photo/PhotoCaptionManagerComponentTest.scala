package photography.usecases.photo

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Mutex
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.capabilities.Transaction
import photography.domain.album.*
import photography.ports.photo.PhotoRepository
import photography.usecases.photo.PhotoCaptionManager.*
import java.util.UUID

final class PhotoCaptionManagerComponentTest extends CatsEffectSuite:
  private val logger = Slf4jLogger.getLogger[IO]
  private val photo  = PhotoId(new UUID(0L, 1L))

  test("should save exact captions and clear once through the photo owner without import or read work"):
    val failure = new IllegalStateException("Caption write rejected")
    val cases   = Vector(
      PhotoRepository.SaveCaptionResult.Saved           -> SaveResult.Saved,
      PhotoRepository.SaveCaptionResult.PhotoNotFound   -> SaveResult.PhotoNotFound,
      PhotoRepository.SaveCaptionResult.Failed(failure) -> SaveResult.Failed,
      PhotoRepository.SaveCaptionResult.Uncertain       -> SaveResult.Uncertain
    )
    cases.traverse_ { case (response, expected) =>
      val refs           = Refs()
      val expectedErrors = response match
        case PhotoRepository.SaveCaptionResult.Failed(cause) => Vector(cause, cause, cause)
        case _                                               => Vector.empty[Throwable]
      for
        manager      <- buildManager(refs, response.pure[IO])
        saved        <- manager.save(photo, text = "日本\nVacanță")
        cleared      <- manager.save(photo, text = "")
        whitespace   <- manager.save(photo, text = " ")
        writes       <- refs.writes.get
        transactions <- refs.transactions.get
        errors       <- refs.errors.get
      yield
        assertEquals(Vector(saved, cleared, whitespace), Vector(expected, expected, expected))
        assertEquals(writes, Vector((photo, "日本\nVacanță".some), (photo, None), (photo, " ".some)))
        assertEquals(transactions, 3)
        assertEquals(errors, expectedErrors)
    }

  test("should expose uncertain commit acknowledgement without reading or retrying"):
    val refs = Refs()
    for
      manager <- buildManager(refs, IO.raiseError[PhotoRepository.SaveCaptionResult](new IllegalStateException("Lost acknowledgement")))
      result  <- manager.save(photo, text = "Saved?")
      writes  <- refs.writes.get
    yield
      assertEquals(result, SaveResult.Uncertain)
      assertEquals(writes, Vector((photo, "Saved?".some)))

  test("should finish the caption transaction handoff before honoring cancellation"):
    TestControl.executeEmbed:
      val refs = Refs()
      for
        entered       <- Deferred[IO, Unit]
        release       <- Deferred[IO, Unit]
        committed     <- Deferred[IO, Unit]
        cancelStarted <- Deferred[IO, Unit]
        manager <- buildManager(refs, entered.complete(()).void >> release.get >> committed.complete(()).as(PhotoRepository.SaveCaptionResult.Saved))
        worker  <- manager.save(photo, text = "Caption").start
        _       <- entered.get
        cancellation <- (cancelStarted.complete(()).void >> worker.cancel).start
        _            <- cancelStarted.get >> IO.cede
        _            <- release.complete(())
        _            <- cancellation.join
        completed    <- committed.tryGet
      yield assertEquals(completed, ().some)

  final private case class Refs():
    val errors: Ref[IO, Vector[Throwable]]                 = Ref.unsafe(Vector.empty)
    val writes: Ref[IO, Vector[(PhotoId, Option[String])]] = Ref.unsafe(Vector.empty)
    val transactions: Ref[IO, Int]                         = Ref.unsafe(0)

  private def buildManager(refs: Refs, response: IO[PhotoRepository.SaveCaptionResult]) =
    given Logger[IO] = buildLogger(refs)
    val repository   = new PhotoRepository[IO]:
      override def saveCaption(id: PhotoId, maybeCaption: Option[String]): IO[PhotoRepository.SaveCaptionResult] =
        refs.writes.update(_ :+ (id, maybeCaption)) >> response
      override def listChapter(id: ChapterId): IO[PhotoRepository.ListResult] =
        IO.raiseError[PhotoRepository.ListResult](new AssertionError("Unexpected photo read"))
      override def save(value: Photo): IO[PhotoRepository.WriteResult] =
        IO.raiseError[PhotoRepository.WriteResult](new AssertionError("Unexpected import save"))
      override def remove(id: PhotoId): IO[PhotoRepository.WriteResult] =
        IO.raiseError[PhotoRepository.WriteResult](new AssertionError("Unexpected removal"))
      override def recordWrite(intent: PhotoWriteIntent): IO[PhotoRepository.RecordWriteResult] =
        IO.raiseError[PhotoRepository.RecordWriteResult](new AssertionError("Unexpected journal write"))
      override def completeWrite(id: PhotoWriteIntentId): IO[PhotoRepository.CompleteWriteResult] =
        IO.raiseError[PhotoRepository.CompleteWriteResult](new AssertionError("Unexpected journal completion"))
      override def listUnfinishedWrites: IO[PhotoRepository.ListUnfinishedWritesResult] =
        IO.raiseError[PhotoRepository.ListUnfinishedWritesResult](new AssertionError("Unexpected journal read"))
    given Transaction[IO, IO] with
      override def run[A](program: IO[A]): IO[A]     = refs.transactions.update(_ + 1) >> program
      override def resolve[A](program: IO[A]): IO[A] = IO.raiseError[A](new AssertionError("Unexpected reconciliation read"))
    Mutex[IO].map: mutex =>
      given Mutex[IO] = mutex
      PhotoCaptionManager.make[IO, IO](repository)

  private def buildLogger(refs: Refs) =
    new Logger[IO]:
      override def error(cause: Throwable)(message: => String): IO[Unit] = refs.errors.update(_ :+ cause) >> logger.error(cause)(message)
      override def warn(cause: Throwable)(message: => String): IO[Unit]  = logger.warn(cause)(message)
      override def info(cause: Throwable)(message: => String): IO[Unit]  = logger.info(cause)(message)
      override def debug(cause: Throwable)(message: => String): IO[Unit] = logger.debug(cause)(message)
      override def trace(cause: Throwable)(message: => String): IO[Unit] = logger.trace(cause)(message)
      override def error(message: => String): IO[Unit]                   = logger.error(message)
      override def warn(message: => String): IO[Unit]                    = logger.warn(message)
      override def info(message: => String): IO[Unit]                    = logger.info(message)
      override def debug(message: => String): IO[Unit]                   = logger.debug(message)
      override def trace(message: => String): IO[Unit]                   = logger.trace(message)
