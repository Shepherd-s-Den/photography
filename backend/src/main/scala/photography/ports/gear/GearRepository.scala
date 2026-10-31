package photography.ports.gear

import cats.data.NonEmptyVector
import photography.domain.album.{AlbumId, ChapterId}
import photography.domain.gear.*

trait GearRepository[F[_]]:
  def createCamera(camera: Camera): F[GearRepository.CameraCreateResult]
  def readCameraReferences(id: CameraId): F[GearRepository.CameraReferencesResult]
  def updateCamera(camera: Camera): F[GearRepository.CameraUpdateResult]
  def deleteCamera(id: CameraId): F[GearRepository.CameraDeleteResult]
  def createLens(lens: Lens): F[GearRepository.LensCreateResult]
  def readLensReferences(id: LensId): F[GearRepository.LensReferencesResult]
  def updateLens(lens: Lens): F[GearRepository.LensUpdateResult]
  def deleteLens(id: LensId): F[GearRepository.LensDeleteResult]
  def readCatalog: F[GearRepository.CatalogReadResult]
  def readChapter(album: AlbumId, chapter: ChapterId): F[GearRepository.ChapterReadResult]
  def replaceChapter(album: AlbumId, chapter: ChapterId, selection: GearSelection): F[GearRepository.ChapterWriteResult]

object GearRepository:
  enum CameraCreateResult:
    case Created, Conflict, Failed, Uncertain

  enum CameraReferencesResult:
    case Found(usage: Usage)
    case NotFound, Failed

  enum Usage:
    case Unused, FixedLens
    case Interchangeable(lenses: NonEmptyVector[Lens])

  enum CameraUpdateResult:
    case Updated, NotFound, Conflict, Failed, Uncertain

  enum CameraDeleteResult:
    case Deleted, NotFound, Used, Failed, Uncertain

  enum LensCreateResult:
    case Created, Conflict, Failed, Uncertain

  enum LensReferencesResult:
    case Found(cameras: Vector[CameraKind.InterchangeableLens])
    case NotFound, Failed

  enum LensUpdateResult:
    case Updated, NotFound, Conflict, Failed, Uncertain

  enum LensDeleteResult:
    case Deleted, NotFound, Used, Failed, Uncertain

  enum CatalogReadResult:
    case Found(catalog: GearCatalog)
    case Failed

  enum ChapterReadResult:
    case Found(selection: GearSelection)
    case NotFound, Failed

  enum ChapterWriteResult:
    case Saved, NotFound, Failed, Uncertain
