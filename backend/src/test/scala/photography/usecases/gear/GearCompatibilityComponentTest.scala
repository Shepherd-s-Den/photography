package photography.usecases.gear

import cats.data.NonEmptyVector
import io.github.iltotore.iron.autoRefine
import munit.FunSuite
import photography.domain.gear.*
import photography.usecases.gear.GearCompatibility.*
import java.util.UUID

final class GearCompatibilityComponentTest extends FunSuite:
  private val sony: LensMount  = "Sony E"
  private val nikon: LensMount = "Nikon Z"
  private val first            =
    Camera(CameraId(new UUID(0L, 1L)), code = "one", friendlyName = "First", CameraKind.InterchangeableLens(sony, SensorFormat.ApsC))
  private val second        = first.copy(id = CameraId(new UUID(0L, 2L)), code = "two")
  private val fixed         = Camera(CameraId(new UUID(0L, 3L)), code = "fixed", friendlyName = "Fixed", CameraKind.FixedLens)
  private val lens          = Lens(LensId(new UUID(0L, 4L)), code = "lens", friendlyName = "Lens", sony, SensorFormat.FullFrame)
  private val use           = CameraSelection(first.id, CameraUse.Interchangeable(NonEmptyVector.one(lens.id)))
  private val compatibility = GearCompatibility.make

  test("should allow empty gear, fixed cameras and the same lens selected for multiple compatible bodies in selection order"):
    val catalog    = GearCatalog(cameras = Vector(first, second, fixed), lenses = Vector(lens))
    val selections = Vector(
      Vector.empty[CameraSelection],
      Vector(CameraSelection(fixed.id, CameraUse.FixedLens)),
      Vector(use, use.copy(camera = second.id), CameraSelection(fixed.id, CameraUse.FixedLens))
    )

    val actual = selections.map(compatibility.validate(catalog, _))

    assertEquals(actual, selections.map(_ => ValidationResult.Accepted))

  test("should require matching mounts and sufficient coverage for every sensor format"):
    val cases = Vector(
      (sony, SensorFormat.ApsC, sony, SensorFormat.ApsC, true),
      (sony, SensorFormat.ApsC, sony, SensorFormat.FullFrame, true),
      (sony, SensorFormat.FullFrame, sony, SensorFormat.ApsC, false),
      (sony, SensorFormat.FullFrame, sony, SensorFormat.FullFrame, true),
      (nikon, SensorFormat.ApsC, sony, SensorFormat.FullFrame, false),
      (nikon, SensorFormat.FullFrame, sony, SensorFormat.FullFrame, false)
    )

    cases.foreach { case (mount, sensor, lensMount, coverage, accepted) =>
      val camera    = first.copy(kind = CameraKind.InterchangeableLens(mount, sensor))
      val candidate = lens.copy(mount = lensMount, coverage = coverage)
      val expected  = if accepted then ValidationResult.Accepted else ValidationResult.Rejected(Failure.IncompatibleLens)
      val actual    = compatibility.validate(GearCatalog(cameras = Vector(camera), lenses = Vector(candidate)), selection = Vector(use))

      assertEquals(actual, expected)
    }

  test("should reject unknown, duplicate and wrong-kind selections without reserving lenses globally"):
    val catalog       = GearCatalog(cameras = Vector(first, fixed), lenses = Vector(lens))
    val unknownCamera = CameraId(new UUID(0L, 90L))
    val unknownLens   = LensId(new UUID(0L, 91L))
    val cases         = Vector(
      Vector(use, use)                                                                       -> Failure.DuplicateCamera,
      Vector(use.copy(camera = unknownCamera))                                               -> Failure.UnknownCamera,
      Vector(use.copy(use = CameraUse.FixedLens))                                            -> Failure.CameraKindMismatch,
      Vector(use.copy(camera = fixed.id))                                                    -> Failure.CameraKindMismatch,
      Vector(use.copy(use = CameraUse.Interchangeable(NonEmptyVector.of(lens.id, lens.id)))) -> Failure.DuplicateLens,
      Vector(use.copy(use = CameraUse.Interchangeable(NonEmptyVector.one(unknownLens))))     -> Failure.UnknownLens
    )

    cases.foreach { case (selection, failure) =>
      assertEquals(compatibility.validate(catalog, selection = selection), ValidationResult.Rejected(failure))
    }
