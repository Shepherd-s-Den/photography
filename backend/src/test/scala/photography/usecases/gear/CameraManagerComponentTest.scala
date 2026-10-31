package photography.usecases.gear

import cats.data.NonEmptyVector
import cats.effect.{IO, Ref}
import cats.effect.std.Mutex
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import photography.capabilities.IdGenerator
import photography.domain.gear.*
import photography.ports.gear.CameraRepository
import photography.ports.gear.CameraRepository.Usage
import photography.usecases.gear.CameraManager.*
import java.util.UUID

final class CameraManagerComponentTest extends CatsEffectSuite:
  private val apsCKind      = CameraKind.InterchangeableLens(mount = "Sony E", sensor = SensorFormat.ApsC)
  private val fullFrameKind = CameraKind.InterchangeableLens(mount = "Sony E", sensor = SensorFormat.FullFrame)
  private val nikonKind     = CameraKind.InterchangeableLens(mount = "Nikon Z", sensor = SensorFormat.ApsC)
  private val camera        = Camera(CameraId(new UUID(0L, 1L)), code = "body", friendlyName = "Body", apsCKind)
  private val lens          = Lens(LensId(new UUID(0L, 2L)), code = "lens", friendlyName = "Lens", mount = "Sony E", coverage = SensorFormat.ApsC)

  test("should create a camera with generated identity and expose repository conflicts and uncertainty"):
    val cases = Vector(
      CameraRepository.CreateResult.Created   -> CreateResult.Created(camera),
      CameraRepository.CreateResult.Conflict  -> CreateResult.Conflict,
      CameraRepository.CreateResult.Failed    -> CreateResult.Failed,
      CameraRepository.CreateResult.Uncertain -> CreateResult.Uncertain
    )

    cases.traverse_ { case (response, expected) =>
      val refs = Refs()

      for
        manager <- buildManager(refs, createResult = response)
        result  <- manager.create(camera.code, camera.friendlyName, camera.kind)
        created <- refs.created.get
      yield
        assertEquals(result, expected)
        assertEquals(created, Vector(camera))
    }

  test("should protect every saved camera use when editing kind, mount or sensor"):
    val cases = Vector(
      (camera, Usage.Interchangeable(lenses = NonEmptyVector.one(lens)), true),
      (camera.copy(kind = fullFrameKind), Usage.Interchangeable(lenses = NonEmptyVector.one(lens)), false),
      (
        camera.copy(kind = fullFrameKind),
        Usage.Interchangeable(lenses = NonEmptyVector.of(lens.copy(coverage = SensorFormat.FullFrame), lens)),
        false
      ),
      (camera, Usage.Interchangeable(lenses = NonEmptyVector.one(lens.copy(coverage = SensorFormat.FullFrame))), true),
      (camera.copy(kind = nikonKind), Usage.Interchangeable(lenses = NonEmptyVector.one(lens)), false),
      (camera.copy(kind = CameraKind.FixedLens), Usage.Interchangeable(lenses = NonEmptyVector.one(lens)), false),
      (camera.copy(kind = CameraKind.FixedLens), Usage.FixedLens, true),
      (camera, Usage.FixedLens, false),
      (camera.copy(kind = CameraKind.FixedLens), Usage.Unused, true)
    )

    cases.traverse_ { case (candidate, usage, allowed) =>
      val refs     = Refs()
      val expected = if allowed then UpdateResult.Updated else UpdateResult.InvalidatesSelection

      for
        manager <- buildManager(refs, references = CameraRepository.ReferencesResult.Found(usage))
        result  <- manager.update(candidate)
        updated <- refs.updated.get
      yield
        assertEquals(result, expected)
        assertEquals(updated, if allowed then Vector(candidate) else Vector.empty[Camera])
    }

  test("should preserve missing and failed reference reads without writing"):
    val cases = Vector(
      CameraRepository.ReferencesResult.NotFound -> UpdateResult.NotFound,
      CameraRepository.ReferencesResult.Failed   -> UpdateResult.Failed
    )

    cases.traverse_ { case (response, expected) =>
      val refs = Refs()

      for
        manager <- buildManager(refs, references = response)
        result  <- manager.update(camera)
        updated <- refs.updated.get
      yield
        assertEquals(result, expected)
        assertEquals(updated, Vector.empty[Camera])
    }

  test("should expose all update and independent deletion outcomes without repeating mutations"):
    val updates = Vector(
      CameraRepository.UpdateResult.Updated   -> UpdateResult.Updated,
      CameraRepository.UpdateResult.NotFound  -> UpdateResult.NotFound,
      CameraRepository.UpdateResult.Conflict  -> UpdateResult.Conflict,
      CameraRepository.UpdateResult.Failed    -> UpdateResult.Failed,
      CameraRepository.UpdateResult.Uncertain -> UpdateResult.Uncertain
    )
    val deletes = Vector(
      CameraRepository.DeleteResult.Deleted   -> DeleteResult.Deleted,
      CameraRepository.DeleteResult.NotFound  -> DeleteResult.NotFound,
      CameraRepository.DeleteResult.Used      -> DeleteResult.Used,
      CameraRepository.DeleteResult.Failed    -> DeleteResult.Failed,
      CameraRepository.DeleteResult.Uncertain -> DeleteResult.Uncertain
    )

    updates.traverse_ { case (response, expected) =>
      val refs = Refs()

      for
        manager <- buildManager(refs, updateResult = response)
        result  <- manager.update(camera)
        writes  <- refs.updated.get
      yield
        assertEquals(result, expected)
        assertEquals(writes, Vector(camera))
    } >> deletes.traverse_ { case (response, expected) =>
      val refs = Refs()

      for
        manager <- buildManager(refs, deleteResult = response)
        result  <- manager.delete(camera.id)
        deleted <- refs.deleted.get
      yield
        assertEquals(result, expected)
        assertEquals(deleted, Vector(camera.id))
    }

  final private case class Refs():
    val created: Ref[IO, Vector[Camera]]   = Ref.unsafe(Vector.empty)
    val updated: Ref[IO, Vector[Camera]]   = Ref.unsafe(Vector.empty)
    val deleted: Ref[IO, Vector[CameraId]] = Ref.unsafe(Vector.empty)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildManager(
      refs: Refs,
      createResult: CameraRepository.CreateResult = CameraRepository.CreateResult.Created,
      references: CameraRepository.ReferencesResult =
        CameraRepository.ReferencesResult.Found(Usage.Interchangeable(lenses = NonEmptyVector.one(lens))),
      updateResult: CameraRepository.UpdateResult = CameraRepository.UpdateResult.Updated,
      deleteResult: CameraRepository.DeleteResult = CameraRepository.DeleteResult.Deleted
  ) =
    given IdGenerator[IO] with
      override def generateId: IO[UUID] = camera.id.value.pure[IO]
    val repository = new CameraRepository[IO]:
      override def create(value: Camera): IO[CameraRepository.CreateResult]            = refs.created.update(_ :+ value).as(createResult)
      override def readReferences(id: CameraId): IO[CameraRepository.ReferencesResult] = IO(assertEquals(id, camera.id)).as(references)
      override def update(value: Camera): IO[CameraRepository.UpdateResult]            = refs.updated.update(_ :+ value).as(updateResult)
      override def delete(id: CameraId): IO[CameraRepository.DeleteResult]             = refs.deleted.update(_ :+ id).as(deleteResult)
    Mutex[IO].map { mutex =>
      given Mutex[IO] = mutex
      CameraManager.make(repository)
    }
  // scalafix:on DisableSyntax.defaultArgs
