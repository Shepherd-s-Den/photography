package photography.usecases.gear

import cats.syntax.all.*
import photography.domain.gear.*
import photography.usecases.gear.GearCompatibility.*

trait GearCompatibility:
  def validate(catalog: GearCatalog, selection: GearSelection): ValidationResult

object GearCompatibility:
  def make: GearCompatibility = new LiveGearCompatibility

  final private class LiveGearCompatibility extends GearCompatibility:
    override def validate(catalog: GearCatalog, selection: GearSelection): ValidationResult =
      val unique  = selection.map(_.camera).distinct.size === selection.size
      val checked = Either.cond(unique, (), Failure.DuplicateCamera) >>
        selection.traverse_(entry => validateCamera(catalog, entry))
      checked.fold(ValidationResult.Rejected(_), _ => ValidationResult.Accepted)

    private def validateCamera(catalog: GearCatalog, entry: CameraSelection) =
      catalog.cameras.find(_.id.value === entry.camera.value).toRight(Failure.UnknownCamera).flatMap: camera =>
        (camera.kind, entry.use) match
          case (CameraKind.FixedLens, CameraUse.FixedLens)                            => ().asRight[Failure]
          case (_: CameraKind.InterchangeableLens, CameraUse.Interchangeable(lenses)) =>
            val unique = lenses.toVector.distinct.size === lenses.length
            Either.cond(unique, (), Failure.DuplicateLens) >> lenses.traverse_(id => validateLens(camera.kind, lenses = catalog.lenses, id))
          case _ => Failure.CameraKindMismatch.asLeft[Unit]

    private def validateLens(kind: CameraKind, lenses: Vector[Lens], id: LensId) =
      lenses.find(_.id.value === id.value).toRight(Failure.UnknownLens)
        .flatMap(lens => Either.cond(kind.accepts(lens), (), Failure.IncompatibleLens))

  enum ValidationResult derives CanEqual:
    case Accepted
    case Rejected(reason: Failure)

  enum Failure derives CanEqual:
    case DuplicateCamera, UnknownCamera, CameraKindMismatch, DuplicateLens, UnknownLens, IncompatibleLens
