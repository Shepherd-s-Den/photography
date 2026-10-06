package photography.domain.gear

import cats.data.{NonEmptyMap, NonEmptySet}
import photography.domain.album.ChapterId

enum CameraUse:
  case FixedLens
  case Interchangeable(lenses: NonEmptySet[LensId])

type GearSelection = NonEmptyMap[CameraId, CameraUse]

final case class GearCatalog(
    cameras: Vector[Camera],
    lenses: Vector[Lens]
)

final case class ChapterGearSelection(chapter: ChapterId, selection: GearSelection)
