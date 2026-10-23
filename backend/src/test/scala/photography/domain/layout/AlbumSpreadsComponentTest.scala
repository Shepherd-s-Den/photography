package photography.domain.layout

import cats.data.NonEmptyList
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import munit.FunSuite
import photography.domain.album.{AlbumId, ChapterId}

import java.util.UUID

final class AlbumSpreadsComponentTest extends FunSuite:

  test("should accept an album start followed by chapter blocks of their own photo spreads"):
    val spreads = NonEmptyList.of(
      buildAlbumStart(0),
      buildChapterStart(1, chapter = 10),
      buildPhotos(2, chapter = 10),
      buildPhotos(3, chapter = 10),
      buildChapterStart(4, chapter = 11),
      buildPhotos(5, chapter = 11)
    )

    assertEquals(AlbumSpreads.make(spreads).map(_.value), Some(spreads))

  test("should reject a spread list whose first spread is not an album start"):
    assertEquals(AlbumSpreads.make(NonEmptyList.of(buildChapterStart(0, chapter = 10))).map(_.value), None)

  test("should reject photo spreads that do not follow a chapter start"):
    assertEquals(AlbumSpreads.make(NonEmptyList.of(buildAlbumStart(0), buildPhotos(1, chapter = 10))).map(_.value), None)

  test("should reject a photo spread whose chapter differs from its chapter start"):
    val spreads = NonEmptyList.of(buildAlbumStart(0), buildChapterStart(1, chapter = 10), buildPhotos(2, chapter = 11))

    assertEquals(AlbumSpreads.make(spreads).map(_.value), None)

  test("should reject a second album start after the first spread"):
    assertEquals(AlbumSpreads.make(NonEmptyList.of(buildAlbumStart(0), buildAlbumStart(1))).map(_.value), None)

  test("should build the initial composition as a single album start"):
    val start = buildAlbumStart(0)

    assertEquals(AlbumSpreads.initial(start).value, NonEmptyList.one(start: Spread))

  private def buildAlbumStart(index: Int): Spread.AlbumStart =
    Spread.AlbumStart(SpreadId(buildUuid(index)), AlbumId(buildUuid(90)), SpreadTemplateId(buildUuid(91)), Vector.empty, Vector.empty)

  private def buildChapterStart(index: Int, chapter: Int): Spread.ChapterStart =
    Spread.ChapterStart(SpreadId(buildUuid(index)), ChapterId(buildUuid(chapter)), SpreadTemplateId(buildUuid(91)), Vector.empty, Vector.empty)

  private def buildPhotos(index: Int, chapter: Int): Spread.PhotosOnly =
    Spread.PhotosOnly(
      SpreadId(buildUuid(index)),
      ChapterId(buildUuid(chapter)),
      1.refineUnsafe,
      SpreadTemplateId(buildUuid(91)),
      Vector.empty,
      Vector.empty
    )

  private def buildUuid(index: Int): UUID =
    val suffix = index.toString.reverse.padTo(12, '0').reverse
    UUID.fromString(s"00000000-0000-4000-8000-$suffix")
