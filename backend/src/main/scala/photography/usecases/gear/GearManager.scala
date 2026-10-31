package photography.usecases.gear

import cats.effect.kernel.MonadCancelThrow
import cats.effect.std.Mutex
import cats.syntax.all.*
import photography.capabilities.IdGenerator
import photography.domain.album.{AlbumId, ChapterId}
import photography.domain.gear.*
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
  def readChapter(album: AlbumId, chapter: ChapterId): F[ChapterReadResult]
  def replaceChapter(album: AlbumId, chapter: ChapterId, selection: GearSelection): F[ChapterReplaceResult]

object GearManager:
  def make[F[_]: MonadCancelThrow: Mutex: IdGenerator](repository: GearRepository[F]): GearManager[F] = new LiveGearManager(repository)

  final private class LiveGearManager[F[_]: MonadCancelThrow: Mutex: IdGenerator](repository: GearRepository[F]) extends GearManager[F]:
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
          repository.readCameraReferences(camera.id).flatMap:
            case GearRepository.CameraReferencesResult.NotFound     => CameraUpdateResult.NotFound.pure[F]
            case GearRepository.CameraReferencesResult.Failed       => CameraUpdateResult.Failed.pure[F]
            case GearRepository.CameraReferencesResult.Found(usage) =>
              if preservesSelections(camera.kind, usage) then
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
          repository.readLensReferences(lens.id).flatMap:
            case GearRepository.LensReferencesResult.NotFound       => LensUpdateResult.NotFound.pure[F]
            case GearRepository.LensReferencesResult.Failed         => LensUpdateResult.Failed.pure[F]
            case GearRepository.LensReferencesResult.Found(cameras) =>
              if cameras.forall(_.accepts(lens.mount, coverage = lens.coverage)) then
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

    override def readChapter(album: AlbumId, chapter: ChapterId): F[ChapterReadResult] =
      repository.readChapter(album, chapter).map:
        case GearRepository.ChapterReadResult.Found(selection) => ChapterReadResult.Found(selection = selection)
        case GearRepository.ChapterReadResult.NotFound         => ChapterReadResult.NotFound
        case GearRepository.ChapterReadResult.Failed           => ChapterReadResult.Failed

    override def replaceChapter(album: AlbumId, chapter: ChapterId, selection: GearSelection): F[ChapterReplaceResult] =
      summon[Mutex[F]].lock.surround:
        MonadCancelThrow[F].uncancelable: _ =>
          repository.readCatalog.flatMap:
            case GearRepository.CatalogReadResult.Failed         => ChapterReplaceResult.Failed.pure[F]
            case GearRepository.CatalogReadResult.Found(current) =>
              validateSelection(current, selection = selection) match
                case Left(reason) => ChapterReplaceResult.Rejected(reason).pure[F]
                case Right(_)     =>
                  repository.replaceChapter(album, chapter, selection = selection).map:
                    case GearRepository.ChapterWriteResult.Saved     => ChapterReplaceResult.Saved
                    case GearRepository.ChapterWriteResult.NotFound  => ChapterReplaceResult.NotFound
                    case GearRepository.ChapterWriteResult.Failed    => ChapterReplaceResult.Failed
                    case GearRepository.ChapterWriteResult.Uncertain => ChapterReplaceResult.Uncertain

    private def preservesSelections(kind: CameraKind, usage: GearRepository.Usage) = usage match
      case GearRepository.Usage.Unused    => true
      case GearRepository.Usage.FixedLens => kind match
          case CameraKind.FixedLens              => true
          case _: CameraKind.InterchangeableLens => false
      case GearRepository.Usage.Interchangeable(lenses) => lenses.forall(lens => kind.accepts(lens.mount, coverage = lens.coverage))

    private def validateSelection(catalog: GearCatalog, selection: GearSelection) =
      val unique = selection.map(_.camera).distinct.size === selection.size
      Either.cond(unique, (), Failure.DuplicateCamera) >> selection.traverse_(entry => validateCamera(catalog, entry))

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
        .flatMap(lens => Either.cond(kind.accepts(lens.mount, coverage = lens.coverage), (), Failure.IncompatibleLens))

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

  enum ChapterReadResult:
    case Found(selection: GearSelection)
    case NotFound, Failed

  enum ChapterReplaceResult:
    case Saved, NotFound, Failed, Uncertain
    case Rejected(reason: Failure)

  enum Failure derives CanEqual:
    case DuplicateCamera, UnknownCamera, CameraKindMismatch, DuplicateLens, UnknownLens, IncompatibleLens
