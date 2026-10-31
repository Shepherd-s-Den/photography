package photography.usecases.gear

import cats.effect.kernel.MonadCancelThrow
import cats.effect.std.Mutex
import cats.syntax.all.*
import photography.capabilities.IdGenerator
import photography.domain.gear.*
import photography.ports.chapter.ChapterRepository
import photography.ports.gear.GearRepository
import photography.usecases.gear.GearManager.*

trait GearManager[F[_]]:
  def createCamera(code: CameraExifCode, name: CameraName, kind: CameraKind): F[CameraCreateResult]
  def updateCamera(camera: Camera): F[CameraUpdateResult]
  def deleteCamera(id: CameraId): F[CameraDeleteResult]
  def createLens(code: LensExifCode, name: LensName, mount: LensMount, coverage: SensorFormat): F[LensCreateResult]
  def updateLens(lens: Lens): F[LensUpdateResult]
  def deleteLens(id: LensId): F[LensDeleteResult]
  def readCatalog: F[CatalogResult]
  def validateSelection(catalog: GearCatalog, selection: GearSelection): ValidationResult

object GearManager:
  def make[F[_]: MonadCancelThrow: Mutex: IdGenerator](repository: GearRepository[F], chapters: ChapterRepository[F]): GearManager[F] =
    new LiveGearManager(repository, chapters)

  final private class LiveGearManager[F[_]: MonadCancelThrow: Mutex: IdGenerator](
      repository: GearRepository[F],
      chapters: ChapterRepository[F]
  ) extends GearManager[F]:
    override def createCamera(code: CameraExifCode, name: CameraName, kind: CameraKind): F[CameraCreateResult] =
      IdGenerator[F].generateId.flatMap: id =>
        val camera = Camera(CameraId(id), code, name, kind)
        repository.createCamera(camera).map:
          case GearRepository.CameraCreateResult.Created   => CameraCreateResult.Created(camera)
          case GearRepository.CameraCreateResult.Conflict  => CameraCreateResult.Conflict
          case GearRepository.CameraCreateResult.Failed    => CameraCreateResult.Failed
          case GearRepository.CameraCreateResult.Uncertain => CameraCreateResult.Uncertain

    override def updateCamera(camera: Camera): F[CameraUpdateResult] =
      summon[Mutex[F]].lock.surround:
        MonadCancelThrow[F].uncancelable: _ =>
          repository.readCatalog.flatMap:
            case GearRepository.CatalogReadResult.Failed         => CameraUpdateResult.Failed.pure[F]
            case GearRepository.CatalogReadResult.Found(catalog) =>
              if !catalog.cameras.exists(_.id.value === camera.id.value) then CameraUpdateResult.NotFound.pure[F]
              else
                val updated = catalog.copy(cameras = catalog.cameras.map(current => if current.id.value === camera.id.value then camera else current))
                chapters.readSelections.flatMap:
                  case ChapterRepository.SelectionsReadResult.Failed       => CameraUpdateResult.Failed.pure[F]
                  case ChapterRepository.SelectionsReadResult.Found(saved) =>
                    val selected = saved.flatMap(_.selection).filter(_.camera.value === camera.id.value)
                    if preservesSelections(updated, selections = selected) then
                      repository.updateCamera(camera).map:
                        case GearRepository.CameraUpdateResult.Updated   => CameraUpdateResult.Updated
                        case GearRepository.CameraUpdateResult.NotFound  => CameraUpdateResult.NotFound
                        case GearRepository.CameraUpdateResult.Conflict  => CameraUpdateResult.Conflict
                        case GearRepository.CameraUpdateResult.Failed    => CameraUpdateResult.Failed
                        case GearRepository.CameraUpdateResult.Uncertain => CameraUpdateResult.Uncertain
                    else CameraUpdateResult.InvalidatesSelection.pure[F]

    override def deleteCamera(id: CameraId): F[CameraDeleteResult] =
      summon[Mutex[F]].lock.surround:
        MonadCancelThrow[F].uncancelable: _ =>
          repository.deleteCamera(id).map:
            case GearRepository.CameraDeleteResult.Deleted   => CameraDeleteResult.Deleted
            case GearRepository.CameraDeleteResult.NotFound  => CameraDeleteResult.NotFound
            case GearRepository.CameraDeleteResult.Used      => CameraDeleteResult.Used
            case GearRepository.CameraDeleteResult.Failed    => CameraDeleteResult.Failed
            case GearRepository.CameraDeleteResult.Uncertain => CameraDeleteResult.Uncertain

    override def createLens(code: LensExifCode, name: LensName, mount: LensMount, coverage: SensorFormat): F[LensCreateResult] =
      IdGenerator[F].generateId.flatMap: id =>
        val lens = Lens(LensId(id), code, name, mount = mount, coverage = coverage)
        repository.createLens(lens).map:
          case GearRepository.LensCreateResult.Created   => LensCreateResult.Created(lens)
          case GearRepository.LensCreateResult.Conflict  => LensCreateResult.Conflict
          case GearRepository.LensCreateResult.Failed    => LensCreateResult.Failed
          case GearRepository.LensCreateResult.Uncertain => LensCreateResult.Uncertain

    override def updateLens(lens: Lens): F[LensUpdateResult] =
      summon[Mutex[F]].lock.surround:
        MonadCancelThrow[F].uncancelable: _ =>
          repository.readCatalog.flatMap:
            case GearRepository.CatalogReadResult.Failed         => LensUpdateResult.Failed.pure[F]
            case GearRepository.CatalogReadResult.Found(catalog) =>
              if !catalog.lenses.exists(_.id.value === lens.id.value) then LensUpdateResult.NotFound.pure[F]
              else
                val updated = catalog.copy(lenses = catalog.lenses.map(current => if current.id.value === lens.id.value then lens else current))
                chapters.readSelections.flatMap:
                  case ChapterRepository.SelectionsReadResult.Failed       => LensUpdateResult.Failed.pure[F]
                  case ChapterRepository.SelectionsReadResult.Found(saved) =>
                    val selected = saved.flatMap(_.selection).filter: entry =>
                      entry.use match
                        case CameraUse.FixedLens               => false
                        case CameraUse.Interchangeable(lenses) => lenses.exists(_.value === lens.id.value)
                    if preservesSelections(updated, selections = selected) then
                      repository.updateLens(lens).map:
                        case GearRepository.LensUpdateResult.Updated   => LensUpdateResult.Updated
                        case GearRepository.LensUpdateResult.NotFound  => LensUpdateResult.NotFound
                        case GearRepository.LensUpdateResult.Conflict  => LensUpdateResult.Conflict
                        case GearRepository.LensUpdateResult.Failed    => LensUpdateResult.Failed
                        case GearRepository.LensUpdateResult.Uncertain => LensUpdateResult.Uncertain
                    else LensUpdateResult.InvalidatesSelection.pure[F]

    override def deleteLens(id: LensId): F[LensDeleteResult] =
      summon[Mutex[F]].lock.surround:
        MonadCancelThrow[F].uncancelable: _ =>
          repository.deleteLens(id).map:
            case GearRepository.LensDeleteResult.Deleted   => LensDeleteResult.Deleted
            case GearRepository.LensDeleteResult.NotFound  => LensDeleteResult.NotFound
            case GearRepository.LensDeleteResult.Used      => LensDeleteResult.Used
            case GearRepository.LensDeleteResult.Failed    => LensDeleteResult.Failed
            case GearRepository.LensDeleteResult.Uncertain => LensDeleteResult.Uncertain

    override def readCatalog: F[CatalogResult] =
      repository.readCatalog.map:
        case GearRepository.CatalogReadResult.Found(current) => CatalogResult.Found(current)
        case GearRepository.CatalogReadResult.Failed         => CatalogResult.Failed

    override def validateSelection(catalog: GearCatalog, selection: GearSelection): ValidationResult =
      val unique  = selection.map(_.camera).distinct.size === selection.size
      val checked = Either.cond(unique, (), Failure.DuplicateCamera) >> selection.traverse_(entry => validateCamera(catalog, entry))
      checked.fold(ValidationResult.Rejected(_), _ => ValidationResult.Valid)

    private def preservesSelections(catalog: GearCatalog, selections: Vector[CameraSelection]) =
      selections.forall: entry =>
        validateSelection(catalog, selection = Vector(entry)) match
          case ValidationResult.Valid       => true
          case _: ValidationResult.Rejected => false

    private def validateCamera(catalog: GearCatalog, entry: CameraSelection) =
      catalog.cameras.find(_.id.value === entry.camera.value).toRight(Failure.UnknownCamera).flatMap: camera =>
        (camera.kind, entry.use) match
          case (CameraKind.FixedLens, CameraUse.FixedLens)                               => ().asRight[Failure]
          case (kind: CameraKind.InterchangeableLens, CameraUse.Interchangeable(lenses)) =>
            val unique = lenses.toVector.distinct.size === lenses.length
            Either.cond(unique, (), Failure.DuplicateLens) >> lenses.traverse_(id => validateLens(kind, lenses = catalog.lenses, id))
          case _ => Failure.CameraKindMismatch.asLeft[Unit]

    private def validateLens(kind: CameraKind.InterchangeableLens, lenses: Vector[Lens], id: LensId) =
      lenses.find(_.id.value === id.value).toRight(Failure.UnknownLens)
        .flatMap(lens => Either.cond(acceptsLens(kind, lens), (), Failure.IncompatibleLens))

    private def acceptsLens(kind: CameraKind.InterchangeableLens, lens: Lens) =
      kind.mount === lens.mount && (kind.sensor === SensorFormat.ApsC || lens.coverage === SensorFormat.FullFrame)

  enum CameraCreateResult:
    case Created(camera: Camera)
    case Conflict, Failed, Uncertain
  enum CameraUpdateResult:
    case Updated, NotFound, Conflict, InvalidatesSelection, Failed, Uncertain
  enum CameraDeleteResult:
    case Deleted, NotFound, Used, Failed, Uncertain
  enum LensCreateResult:
    case Created(lens: Lens)
    case Conflict, Failed, Uncertain
  enum LensUpdateResult:
    case Updated, NotFound, Conflict, InvalidatesSelection, Failed, Uncertain
  enum LensDeleteResult:
    case Deleted, NotFound, Used, Failed, Uncertain
  enum CatalogResult:
    case Found(catalog: GearCatalog)
    case Failed
  enum ValidationResult derives CanEqual:
    case Valid
    case Rejected(reason: Failure)
  enum Failure derives CanEqual:
    case DuplicateCamera, UnknownCamera, CameraKindMismatch, DuplicateLens, UnknownLens, IncompatibleLens
