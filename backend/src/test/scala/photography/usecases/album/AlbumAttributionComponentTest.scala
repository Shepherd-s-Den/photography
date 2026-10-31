package photography.usecases.album

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.collection.MinLength
import munit.FunSuite
import photography.domain.album.*
import photography.usecases.album.AlbumAttribution.*

final class AlbumAttributionComponentTest extends FunSuite:
  private val all  = Set(Author.Cosmin, Author.Mara, Author.Iacob).refineUnsafe[MinLength[1]]
  private val pair = Set(Author.Cosmin, Author.Mara).refineUnsafe[MinLength[1]]

  test("should initialize new albums independently of year and retain historical attribution"):
    val attribution = AlbumAttribution.make
    val cases       = Vector(
      Origin.NewAlbum                   -> all,
      Origin.ExistingAlbum(year = 1900) -> pair,
      Origin.ExistingAlbum(year = 2025) -> pair,
      Origin.ExistingAlbum(year = 2026) -> all,
      Origin.ExistingAlbum(year = 2100) -> all
    )

    val actual = cases.map { case (origin, _) => attribution.initialize(origin) }

    assertEquals(actual, cases.map { case (_, authors) => InitializationResult.Initialized(authors) })
