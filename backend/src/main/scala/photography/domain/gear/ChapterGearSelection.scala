package photography.domain.gear

import cats.data.NonEmptyVector
import photography.domain.album.ChapterId

enum CameraUse:
  case FixedLens
  case Interchangeable(lenses: NonEmptyVector[LensId])

final case class CameraSelection(camera: CameraId, use: CameraUse)

type GearSelection = Vector[CameraSelection]

final case class GearCatalog(cameras: Vector[Camera], lenses: Vector[Lens])

final case class ChapterGearSelection(chapter: ChapterId, selection: GearSelection)
