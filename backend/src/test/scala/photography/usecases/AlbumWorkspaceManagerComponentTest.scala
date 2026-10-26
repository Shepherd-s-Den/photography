package photography.usecases

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.CountryRepository
import photography.usecases.AlbumManager.*
import photography.usecases.AlbumWorkspaceManager.GetResult

import java.nio.file.Path
import java.time.Instant
import java.util.UUID

final class AlbumWorkspaceManagerComponentTest extends CatsEffectSuite:
  private val album =
    val box     = SlotBox(x = 0.0, y = 0.0, width = 100.0, height = 100.0)
    val text    = SpreadSlot.Text(TextSpreadSlotId(new UUID(0L, 3L)), box)
    val opening = Spread.AlbumStart(
      SpreadId(new UUID(0L, 2L)),
      SpreadTemplateId(new UUID(0L, 4L)),
      photoSlots = Vector.empty[SpreadSlot.Photo],
      text,
      placements = Vector.empty[SpreadPlacement]
    )
    Album(AlbumId(new UUID(0L, 1L)), year = 2026, title = "Our year", synopsis = "", Instant.EPOCH, opening, chapters = Vector.empty[Chapter])
  private val countries = NonEmptyList.one(
    Country(CountryId(new UUID(0L, 5L)), code = "RO", name = "Romania", AbsoluteLocation(Path.of("/flags/ro.svg")))
  )

  test("should assemble the complete album and selectable countries with the configured photo root"):
    val manager  = buildManager(GetAlbumResult.Found(album), CountryRepository.ListResult.Listed(countries))
    val expected = GetResult.Found(album, countries, photoRoot = "/photos")

    manager.get(album.id).map(result => assertEquals(result, expected))

  test("should preserve missing albums and propagate album or catalog failures"):
    val scenarios = Vector(
      (GetAlbumResult.NotFound, CountryRepository.ListResult.Failed, GetResult.AlbumNotFound),
      (GetAlbumResult.Failed, CountryRepository.ListResult.Listed(countries), GetResult.Failed),
      (GetAlbumResult.Found(album), CountryRepository.ListResult.Failed, GetResult.Failed)
    )

    scenarios.traverse_ { case (albumResult, countryResult, expected) =>
      buildManager(albumResult, countryResult).get(album.id).map(result => assertEquals(result, expected))
    }

  private def buildManager(albumResult: GetAlbumResult, countryResult: CountryRepository.ListResult) =
    val albums = new AlbumManager[IO]:
      override def get(id: AlbumId): IO[GetAlbumResult]                              = albumResult.pure[IO]
      override def list(page: Int): IO[ListAlbumsResult]                             = ListAlbumsResult.Failed.pure[IO]
      override def create(year: AlbumYear, title: AlbumTitle): IO[CreateAlbumResult] = CreateAlbumResult.Failed.pure[IO]
      override def rename(id: AlbumId, title: AlbumTitle): IO[RenameResult]          = RenameResult.Failed.pure[IO]
      override def delete(id: AlbumId): IO[DeleteAlbumResult]                        = DeleteAlbumResult.Failed.pure[IO]
    val catalog = new CountryRepository[IO]:
      override def list: IO[CountryRepository.ListResult] = countryResult.pure[IO]
    AlbumWorkspaceManager.make(albums, catalog, photoRoot = "/photos")
