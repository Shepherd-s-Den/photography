package photography.adapters.http

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.jsonDecoder
import photography.domain.album.*
import photography.domain.layout.SpreadId
import photography.ports.album.{AlbumResources, CountryRepository}
import photography.ports.album.AlbumResources.*
import java.nio.file.Path
import java.time.{Instant, LocalDate}
import java.util.UUID

final class AlbumResourcesApiSeamIntegrationTest extends CatsEffectSuite:
  private val album    = AlbumId(new UUID(0L, 1L))
  private val chapter  = ChapterId(new UUID(0L, 2L))
  private val country  = Country(CountryId(new UUID(0L, 3L)), "RO", "Romania", AbsoluteLocation(Path.of("/flags/ro.svg")))
  private val metadata = Metadata(album, 2024, "Year", "Synopsis", Instant.EPOCH)
  private val home     =
    ChapterMetadata(chapter, "Home", Vector(country.id), LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), "/photos/Home", "Description")
  private val members = Vector(Member(SpreadId(new UUID(0L, 4L)), None), Member(SpreadId(new UUID(0L, 5L)), Some(chapter)))
  private val base    = s"/api/albums/${album.value}"

  test("authoritative resources expose metadata, ordered chapter metadata and composition independently"):
    val resources =
      makeResources(metadata.some.asRight[Failure], Vector(home, home.copy(name = "Vacation")).some.asRight[Failure], members.some.asRight[Failure])
    val routes = AlbumResourcesApi.make(resources, catalog(CountryRepository.ListResult.Listed(NonEmptyList.one(country))), "/photos").orNotFound
    Vector(base, s"$base/chapters", s"$base/composition", "/api/countries", "/api/photo-source").traverse: path =>
      routes.run(Request[IO](Method.GET, Uri.unsafeFromString(path))).flatMap: response =>
        response.as[Json].map: value =>
          assertEquals(response.status, Status.Ok)
          assert(!value.noSpaces.contains("photoInventories"))
          value
    .map: values =>
      values.toList match
        case album :: chapters :: composition :: _ :: source :: Nil =>
          assertEquals(album.hcursor.get[String]("synopsis").toOption, "Synopsis".some)
          assertEquals(chapters.hcursor.downField("chapters").downArray.get[String]("kind").toOption, "home".some)
          assertEquals(chapters.hcursor.downField("chapters").downArray.get[String]("end").toOption, "2024-12-31".some)
          assertEquals(composition.hcursor.downField("spreads").downArray.get[Option[String]]("chapter").toOption, none[String].some)
          assertEquals(source.hcursor.get[String]("photoRoot").toOption, "/photos".some)
        case _ => fail("Missing resource results")

  test("absence and storage failure remain resource-specific without acquiring unrelated resources"):
    Vector(false, true).traverse_ { fail =>
      val unavailable = Failure.Unavailable
      val resources   = makeResources(
        if fail then unavailable.asLeft[Option[Metadata]] else none[Metadata].asRight[Failure],
        if fail then unavailable.asLeft[Option[Vector[ChapterMetadata]]] else none[Vector[ChapterMetadata]].asRight[Failure],
        if fail then unavailable.asLeft[Option[Vector[Member]]] else none[Vector[Member]].asRight[Failure]
      )
      val routes = AlbumResourcesApi.make(resources, catalog(CountryRepository.ListResult.Failed), "/photos").orNotFound
      Vector(base, s"$base/chapters", s"$base/composition", "/api/countries").traverse_ { path =>
        routes.run(Request[IO](Method.GET, Uri.unsafeFromString(path))).map: response =>
          assertEquals(response.status, if fail || path === "/api/countries" then Status.InternalServerError else Status.NotFound)
      }
    }

  test("noncanonical ownership IDs never reach repository reads"):
    val resources =
      makeResources(none[Metadata].asRight[Failure], none[Vector[ChapterMetadata]].asRight[Failure], none[Vector[Member]].asRight[Failure])
    val routes = AlbumResourcesApi.make(resources, catalog(CountryRepository.ListResult.Failed), "/photos").orNotFound
    Vector("bad", "1-1-1-1-1").traverse_ { id =>
      Vector("", "/chapters", "/composition").traverse_ { suffix =>
        routes.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/api/albums/$id$suffix"))).flatMap: response =>
          response.as[Json].map: body =>
            assertEquals(response.status, Status.NotFound)
            assertEquals(body, Json.obj("code" -> Json.fromString("album_not_found")))
      }
    }

  private def makeResources(
      albumResult: Either[Failure, Option[Metadata]],
      chapterResult: Either[Failure, Option[Vector[ChapterMetadata]]],
      compositionResult: Either[Failure, Option[Vector[Member]]]
  ): AlbumResources[IO] = new AlbumResources[IO]:
    override def metadata(id: AlbumId)    = IO(assertEquals(id.value, album.value)).as(albumResult)
    override def chapters(id: AlbumId)    = IO(assertEquals(id.value, album.value)).as(chapterResult)
    override def composition(id: AlbumId) = IO(assertEquals(id.value, album.value)).as(compositionResult)

  private def catalog(result: CountryRepository.ListResult): CountryRepository[IO] = new CountryRepository[IO]:
    override def list = result.pure[IO]
