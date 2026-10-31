package photography.ports.gear

import photography.domain.album.{AlbumId, ChapterId}
import photography.domain.gear.GearSelection

trait ChapterGearRepository[F[_]]:
  def read(album: AlbumId, chapter: ChapterId): F[ChapterGearRepository.ReadResult]
  def replace(album: AlbumId, chapter: ChapterId, selection: GearSelection): F[ChapterGearRepository.WriteResult]

object ChapterGearRepository:
  enum ReadResult:
    case Found(selection: GearSelection)
    case NotFound, Failed

  enum WriteResult:
    case Saved, NotFound, Failed, Uncertain
