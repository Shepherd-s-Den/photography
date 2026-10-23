package photography.domain.layout

import cats.data.NonEmptyList
import photography.domain.album.ChapterId

import scala.annotation.tailrec

// Single owner of the album spread-shape invariant: the first spread is an AlbumStart,
// followed by chapter blocks, each a ChapterStart followed by PhotosOnly spreads of that chapter.
opaque type AlbumSpreads = NonEmptyList[Spread]
object AlbumSpreads:
  def make(spreads: NonEmptyList[Spread]): Option[AlbumSpreads] =
    spreads.head match
      case _: Spread.AlbumStart => Option.when(chapterBlocksAreValid(spreads.tail))(spreads)
      case _                    => None

  def initial(start: Spread.AlbumStart): AlbumSpreads = NonEmptyList.one(start)

  extension (spreads: AlbumSpreads) def value: NonEmptyList[Spread] = spreads

  @tailrec
  private def chapterBlocksAreValid(spreads: List[Spread]): Boolean =
    spreads match
      case Nil                                              => true
      case Spread.ChapterStart(_, chapter, _, _, _) :: rest =>
        chapterBlocksAreValid(rest.dropWhile(isPhotoOf(chapter)))
      case _ => false

  private def isPhotoOf(chapter: ChapterId)(spread: Spread): Boolean =
    spread match
      case Spread.PhotosOnly(_, `chapter`, _, _, _, _) => true
      case _                                           => false
