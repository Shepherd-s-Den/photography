package photography.ports

import photography.domain.album.{AlbumId, ChapterId}
import photography.domain.layout.Spread

/** Aggregate reads require their owner to exist and fail on malformed composition; chapter photos follow persisted order. */
trait AlbumSpreads[Db[_]]:
  def readOpening(album: AlbumId): Db[Spread.AlbumStart]
  def readChapter(chapter: ChapterId): Db[(Spread.ChapterStart, Vector[Spread.PhotosOnly])]
  def insertOpening(album: AlbumId, spread: Spread.AlbumStart): Db[Unit]
