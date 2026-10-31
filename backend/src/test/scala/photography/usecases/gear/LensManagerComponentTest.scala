package photography.usecases.gear

import cats.effect.{IO, Ref}
import cats.effect.std.Mutex
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import photography.capabilities.IdGenerator
import photography.domain.gear.*
import photography.ports.gear.LensRepository
import photography.usecases.gear.LensManager.*
import java.util.UUID

final class LensManagerComponentTest extends CatsEffectSuite:
  private val lens = Lens(LensId(new UUID(0L, 1L)), code = "lens", friendlyName = "Lens", mount = LensMount.SonyE, coverage = SensorFormat.FullFrame)
  private val fullFrame = new CameraKind.InterchangeableLens(mount = LensMount.SonyE, sensor = SensorFormat.FullFrame)

  test("should create an independently identified shared lens without a camera owner"):
    val cases = Vector(
      LensRepository.CreateResult.Created   -> CreateResult.Created(lens),
      LensRepository.CreateResult.Conflict  -> CreateResult.Conflict,
      LensRepository.CreateResult.Failed    -> CreateResult.Failed,
      LensRepository.CreateResult.Uncertain -> CreateResult.Uncertain
    )

    cases.traverse_ { case (response, expected) =>
      val refs = Refs()

      for
        manager <- buildManager(refs, createResult = response)
        result  <- manager.create(lens.code, lens.friendlyName, lens.mount, lens.coverage)
        created <- refs.created.get
      yield
        assertEquals(result, expected)
        assertEquals(created, Vector(lens))
    }

  test("should reject edits invalidating any referencing body while allowing coverage expansion and unused edits"):
    val apsC  = new CameraKind.InterchangeableLens(mount = LensMount.SonyE, sensor = SensorFormat.ApsC)
    val cases = Vector(
      (lens, Vector(fullFrame, apsC), true),
      (lens.copy(coverage = SensorFormat.ApsC), Vector(apsC, fullFrame), false),
      (lens.copy(coverage = SensorFormat.ApsC), Vector(apsC), true),
      (lens.copy(mount = LensMount.NikonZ), Vector(fullFrame), false),
      (lens.copy(mount = LensMount.NikonZ), Vector.empty[CameraKind.InterchangeableLens], true)
    )

    cases.traverse_ { case (candidate, cameras, allowed) =>
      val refs     = Refs()
      val expected = if allowed then UpdateResult.Updated else UpdateResult.InvalidatesSelection

      for
        manager <- buildManager(refs, references = LensRepository.ReferencesResult.Found(cameras = cameras))
        result  <- manager.update(candidate)
        updated <- refs.updated.get
      yield
        assertEquals(result, expected)
        assertEquals(updated, if allowed then Vector(candidate) else Vector.empty[Lens])
    }

  test("should preserve missing and failed reference reads without writing"):
    val cases = Vector(
      LensRepository.ReferencesResult.NotFound -> UpdateResult.NotFound,
      LensRepository.ReferencesResult.Failed   -> UpdateResult.Failed
    )

    cases.traverse_ { case (response, expected) =>
      val refs = Refs()

      for
        manager <- buildManager(refs, references = response)
        result  <- manager.update(lens)
        updated <- refs.updated.get
      yield
        assertEquals(result, expected)
        assertEquals(updated, Vector.empty[Lens])
    }

  test("should expose all update and independent deletion outcomes without replay"):
    val updates = Vector(
      LensRepository.UpdateResult.Updated   -> UpdateResult.Updated,
      LensRepository.UpdateResult.NotFound  -> UpdateResult.NotFound,
      LensRepository.UpdateResult.Conflict  -> UpdateResult.Conflict,
      LensRepository.UpdateResult.Failed    -> UpdateResult.Failed,
      LensRepository.UpdateResult.Uncertain -> UpdateResult.Uncertain
    )
    val deletes = Vector(
      LensRepository.DeleteResult.Deleted   -> DeleteResult.Deleted,
      LensRepository.DeleteResult.NotFound  -> DeleteResult.NotFound,
      LensRepository.DeleteResult.Used      -> DeleteResult.Used,
      LensRepository.DeleteResult.Failed    -> DeleteResult.Failed,
      LensRepository.DeleteResult.Uncertain -> DeleteResult.Uncertain
    )

    updates.traverse_ { case (response, expected) =>
      val refs = Refs()

      for
        manager <- buildManager(refs, updateResult = response)
        result  <- manager.update(lens)
        writes  <- refs.updated.get
      yield
        assertEquals(result, expected)
        assertEquals(writes, Vector(lens))
    } >> deletes.traverse_ { case (response, expected) =>
      val refs = Refs()

      for
        manager <- buildManager(refs, deleteResult = response)
        result  <- manager.delete(lens.id)
        deleted <- refs.deleted.get
      yield
        assertEquals(result, expected)
        assertEquals(deleted, Vector(lens.id))
    }

  final private case class Refs():
    val created: Ref[IO, Vector[Lens]]   = Ref.unsafe(Vector.empty)
    val updated: Ref[IO, Vector[Lens]]   = Ref.unsafe(Vector.empty)
    val deleted: Ref[IO, Vector[LensId]] = Ref.unsafe(Vector.empty)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildManager(
      refs: Refs,
      createResult: LensRepository.CreateResult = LensRepository.CreateResult.Created,
      references: LensRepository.ReferencesResult = LensRepository.ReferencesResult.Found(cameras = Vector(fullFrame)),
      updateResult: LensRepository.UpdateResult = LensRepository.UpdateResult.Updated,
      deleteResult: LensRepository.DeleteResult = LensRepository.DeleteResult.Deleted
  ) =
    given IdGenerator[IO] with
      override def generateId: IO[UUID] = lens.id.value.pure[IO]
    val repository = new LensRepository[IO]:
      override def create(value: Lens): IO[LensRepository.CreateResult]            = refs.created.update(_ :+ value).as(createResult)
      override def readReferences(id: LensId): IO[LensRepository.ReferencesResult] = IO(assertEquals(id, lens.id)).as(references)
      override def update(value: Lens): IO[LensRepository.UpdateResult]            = refs.updated.update(_ :+ value).as(updateResult)
      override def delete(id: LensId): IO[LensRepository.DeleteResult]             = refs.deleted.update(_ :+ id).as(deleteResult)
    Mutex[IO].map { mutex =>
      given Mutex[IO] = mutex
      LensManager.make(repository)
    }
  // scalafix:on DisableSyntax.defaultArgs
