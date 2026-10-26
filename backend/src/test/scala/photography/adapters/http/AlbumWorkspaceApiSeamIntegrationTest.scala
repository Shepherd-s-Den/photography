package photography.adapters.http

import cats.data.NonEmptyList
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.jawn.parse
import io.github.iltotore.iron.*
import munit.CatsEffectSuite
import org.http4s.{HttpApp, MediaType, Method, Request, Status, Uri}
import org.http4s.headers.`Content-Type`
import org.http4s.circe.jsonDecoder
import photography.domain.album.*
import photography.domain.layout.*
import photography.usecases.{AlbumManager, AlbumWorkspaceManager, ChapterManager}
import photography.usecases.AlbumManager.*
import photography.usecases.AlbumWorkspaceManager.GetResult
import photography.usecases.ChapterManager.{CreateResult, Period, UpdateResult}

import java.nio.file.Path
import java.time.{Instant, LocalDate}
import java.util.UUID

final class AlbumWorkspaceApiSeamIntegrationTest extends CatsEffectSuite:
  private val country      = Country(CountryId(new UUID(0L, 8L)), code = "RO", name = "Romania", AbsoluteLocation(Path.of("/flags/ro.svg")))
  private val otherCountry = country.copy(id = CountryId(new UUID(0L, 9L)), code = "JP", name = "Japan")
  private val box          = SlotBox(x = 10.0, y = 20.0, width = 30.0, height = 40.0)
  private val text         = SpreadSlot.Text(TextSpreadSlotId(new UUID(0L, 6L)), box)
  private val photo        = SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(0L, 7L)), box, bleed = Set.empty)
  private val template     = SpreadTemplateId(new UUID(0L, 4L))
  private val chapter      =
    val destination = Destination(DestinationId(new UUID(0L, 10L)), name = "Coast", NonEmptyList.one(country))
    val opening     = Spread.ChapterStart(SpreadId(new UUID(0L, 3L)), template, Vector(photo), text, placements = Vector.empty[SpreadPlacement])
    val photos      = Spread.PhotosOnly(SpreadId(new UUID(0L, 5L)), template, NonEmptyList.one(photo), placements = Vector.empty[SpreadPlacement])
    Chapter(
      ChapterId(new UUID(0L, 2L)),
      destination,
      VisitPeriod(LocalDate.of(2024, 2, 29), days = 2),
      description = "Sea air",
      sourceFolder = "/photos/coast",
      opening,
      Vector(photos)
    )
  private val album =
    val emptyPhotos     = Vector.empty[SpreadSlot.Photo]
    val emptyPlacements = Vector.empty[SpreadPlacement]
    val opening         = Spread.AlbumStart(SpreadId(new UUID(0L, 11L)), template, photoSlots = emptyPhotos, text, placements = emptyPlacements)
    Album(AlbumId(new UUID(0L, 1L)), year = 2024, title = "Our year", synopsis = "", Instant.EPOCH, opening, Vector(chapter))
  private val albumPath   = s"/api/albums/${album.id.value}"
  private val chapterPath = s"$albumPath/chapters/${chapter.id.value}"
  private val vacation    =
    s"""{"kind":"vacation","name":"  Coast  ","countries":["${country.id.value}"],"start":"2024-02-29","end":"2024-03-01","sourceFolder":"/photos/coast"}"""
  private val home = s"""{"kind":"home","countries":["${country.id.value}"],"sourceFolder":"/photos/Home"}"""

  test("should return exact workspace metadata, catalog, ordered spread blocks and complete slot geometry"):
    val server   = buildApi(Refs())
    val expected = json(s"""{
      "year":2024,"photoRoot":"/photos",
      "chapters":[{"id":"${chapter.id.value}","kind":"vacation","name":"Coast","countries":["${country.id.value}"],
        "start":"2024-02-29","end":"2024-03-01","sourceFolder":"/photos/coast","description":"Sea air"}],
      "spreads":[
        {"id":"${album.opening.id.value}","chapter":null,"kind":"album_start","slots":[
          {"id":"${text.id.value}","kind":"text","x":10.0,"y":20.0,"width":30.0,"height":40.0}]},
        {"id":"${chapter.opening.id.value}","chapter":"${chapter.id.value}","kind":"chapter_start","slots":[
          {"id":"${photo.id.value}","kind":"photo","x":10.0,"y":20.0,"width":30.0,"height":40.0},
          {"id":"${text.id.value}","kind":"text","x":10.0,"y":20.0,"width":30.0,"height":40.0}]},
        {"id":"${new UUID(0L, 5L)}","chapter":"${chapter.id.value}","kind":"photos_only","slots":[
          {"id":"${photo.id.value}","kind":"photo","x":10.0,"y":20.0,"width":30.0,"height":40.0}]}],
      "countries":[{"id":"${country.id.value}","name":"Romania","code":"RO"},{"id":"${otherCountry.id.value}","name":"Japan","code":"JP"}]}""")

    send(server, Method.GET, s"$albumPath/workspace").map(response => assertEquals(response, ApiResponse(Status.Ok, expected)))

  test("should expose an empty chapter list and the saved full-year Home dates"):
    val homeChapter = chapter.copy(
      destination = chapter.destination.copy(name = "Home"),
      period = VisitPeriod(LocalDate.of(2024, 1, 1), days = 366)
    )
    val emptyAlbum  = album.copy(chapters = Vector.empty[Chapter])
    val homeAlbum   = album.copy(chapters = Vector(homeChapter))
    val emptyServer = buildApi(Refs(), workspaceResult = GetResult.Found(emptyAlbum, NonEmptyList.one(country), photoRoot = "/custom"))
    val homeServer  = buildApi(Refs(), workspaceResult = GetResult.Found(homeAlbum, NonEmptyList.one(country), photoRoot = "/photos"))

    for
      empty     <- send(emptyServer, Method.GET, s"$albumPath/workspace")
      savedHome <- send(homeServer, Method.GET, s"$albumPath/workspace")
    yield
      assertEquals(empty.body.hcursor.get[Vector[Json]]("chapters"), Vector.empty[Json].asRight)
      assertEquals(empty.body.hcursor.get[String]("photoRoot"), "/custom".asRight)
      assertEquals(savedHome.body.hcursor.downField("chapters").downArray.get[String]("kind"), "home".asRight)
      assertEquals(savedHome.body.hcursor.downField("chapters").downArray.get[String]("end"), "2024-12-31".asRight)

  test("should decode vacation and Home creation and type-specific updates into minimal manager inputs"):
    val refs             = Refs()
    val server           = buildApi(refs)
    val homeAlbum        = album.copy(chapters = Vector(chapter.copy(destination = chapter.destination.copy(name = "Home"))))
    val homeServer       = buildApi(refs, albumResult = GetAlbumResult.Found(homeAlbum))
    val expectedId       = json(s"""{"id":"${chapter.id.value}"}""")
    val expectedVacation = s"Coast|Dates(${chapter.period})|/photos/coast|${country.id.value}"
    val expectedHome     = s"Home|AlbumYear|/photos/Home|${country.id.value}"
    val createPrefix     = s"create:${album.id.value}:"
    val updatePrefix     = s"update:${album.id.value}:${chapter.id.value}:"
    val expectedCalls    = Vector(
      s"$createPrefix$expectedVacation",
      s"$createPrefix$expectedHome",
      s"$updatePrefix$expectedVacation",
      s"$updatePrefix$expectedHome"
    )

    for
      createdVacation <- send(server, Method.POST, s"$albumPath/chapters", vacation)
      createdHome     <- send(server, Method.POST, s"$albumPath/chapters", home)
      updatedVacation <- send(server, Method.PUT, chapterPath, vacation.replace("\"kind\":\"vacation\",", ""))
      updatedHome     <- send(homeServer, Method.PUT, chapterPath, home.replace("\"kind\":\"home\",", ""))
      calls           <- refs.calls.get
    yield
      assertEquals(createdVacation, ApiResponse(Status.Created, expectedId))
      assertEquals(createdHome, ApiResponse(Status.Created, expectedId))
      assertEquals(updatedVacation, ApiResponse(Status.Ok, expectedId))
      assertEquals(updatedHome, ApiResponse(Status.Ok, expectedId))
      assertEquals(calls, expectedCalls)

  test("should preserve inclusive same-day and cross-year vacation periods"):
    val periods = Vector(("2024-02-29", "2024-02-29", 1L), ("2023-12-31", "2024-01-02", 3L))

    periods.traverse_ { case (start, end, days) =>
      val refs     = Refs()
      val body     = vacation.replace("2024-02-29", start).replace("2024-03-01", end)
      val period   = VisitPeriod(LocalDate.parse(start), days = days.refineUnsafe)
      val expected = s"create:${album.id.value}:Coast|Dates($period)|/photos/coast|${country.id.value}"

      for
        response <- send(buildApi(refs), Method.POST, s"$albumPath/chapters", body)
        calls    <- refs.calls.get
      yield
        assertEquals(response.status, Status.Created)
        assertEquals(calls, Vector(expected))
    }

  test("should reject malformed chapter fields and dates with readable JSON before mutation"):
    val invalidBodies = Vector(
      "{",
      "[]",
      "{}",
      vacation.replace("\"vacation\"", "\"trip\""),
      vacation.replace("  Coast  ", "  "),
      vacation.replace("  Coast  ", "Home"),
      vacation.replace("2024-02-29", "2023-02-29"),
      vacation.replace("2024-03-01", "2024-02-28"),
      vacation.replace("2024-02-29", "2024-2-29"),
      vacation.replace("2024-02-29", "2024-02-29T00:00:00Z"),
      vacation.replace(s""""${country.id.value}"""", "\"not-a-uuid\""),
      vacation.replace(s""""${country.id.value}"""", "\"1-1-1-1-1\""),
      vacation.replace(s"""["${country.id.value}"]""", "[]"),
      vacation.replace(s"""["${country.id.value}"]""", s"""["${country.id.value}","${country.id.value}"]"""),
      vacation.replace("/photos/coast", "photos/coast"),
      vacation.replace("/photos/coast", "/photos/../coast"),
      vacation.replace("\"sourceFolder\":\"/photos/coast\"", "\"sourceFolder\":null"),
      vacation.replace("\"name\":\"  Coast  \",", ""),
      home.replace("\"countries\"", "\"missingCountries\"")
    )
    val refs   = Refs()
    val server = buildApi(refs)

    for
      responses     <- invalidBodies.traverse(body => send(server, Method.POST, s"$albumPath/chapters", body))
      invalidUpdate <- send(server, Method.PUT, chapterPath, "{}")
      calls         <- refs.calls.get
    yield
      assert((responses :+ invalidUpdate).forall(response => response.status === Status.BadRequest && hasFeedback(response.body)))
      assertEquals(calls, Vector.empty)

  test("should map missing and malformed ownership targets to the exact resource errors"):
    val refs           = Refs()
    val missingAlbum   = buildApi(refs, workspaceResult = GetResult.AlbumNotFound, albumResult = GetAlbumResult.NotFound)
    val foreignChapter = buildApi(refs, albumResult = GetAlbumResult.Found(album.copy(chapters = Vector.empty[Chapter])))
    val normal         = buildApi(refs)
    val albumError     = json("""{"code":"album_not_found"}""")
    val chapterError   = json("""{"code":"chapter_not_found"}""")

    for
      missingWorkspace <- send(missingAlbum, Method.GET, s"$albumPath/workspace")
      missingUpdate    <- send(missingAlbum, Method.PUT, chapterPath, vacation)
      foreign          <- send(foreignChapter, Method.PUT, chapterPath, vacation)
      malformedAlbums  <- Vector("bad", "1-1-1-1-1").traverse(id => send(normal, Method.GET, s"/api/albums/$id/workspace"))
      malformedCreate  <- send(normal, Method.POST, "/api/albums/bad/chapters", vacation)
      malformedUpdate  <- send(normal, Method.PUT, "/api/albums/bad/chapters/bad", vacation)
      malformedChapter <- send(normal, Method.PUT, s"$albumPath/chapters/bad", vacation)
      calls            <- refs.calls.get
    yield
      val albumResponses = Vector(missingWorkspace, missingUpdate, malformedCreate, malformedUpdate) ++ malformedAlbums
      assertEquals(albumResponses, Vector.fill(6)(ApiResponse(Status.NotFound, albumError)))
      assertEquals(Vector(foreign, malformedChapter), Vector.fill(2)(ApiResponse(Status.NotFound, chapterError)))
      assertEquals(calls, Vector.empty)

  test("should translate every manager rejection and failure without inventing persistence behavior"):
    val conflicts = ChapterConflict.values.toVector
    val refs      = Refs()

    conflicts.traverse_ { conflict =>
      val server = buildApi(refs, createResult = CreateResult.Rejected(conflict), updateResult = UpdateResult.Rejected(conflict))

      for
        created <- send(server, Method.POST, s"$albumPath/chapters", vacation)
        updated <- send(server, Method.PUT, chapterPath, vacation)
      yield
        assertEquals(Vector(created.status, updated.status), Vector.fill(2)(Status.BadRequest))
        assert(hasFeedback(created.body) && hasFeedback(updated.body))
    } *> Vector(
      (CreateResult.AlbumNotFound, UpdateResult.AlbumNotFound, Status.NotFound, "album_not_found"),
      (CreateResult.Failed, UpdateResult.Failed, Status.InternalServerError, "service_failure")
    ).traverse_ { case (createResult, updateResult, status, code) =>
      val server   = buildApi(refs, createResult = createResult, updateResult = updateResult)
      val expected = ApiResponse(status, Json.obj("code" -> Json.fromString(code)))

      for
        created <- send(server, Method.POST, s"$albumPath/chapters", vacation)
        updated <- send(server, Method.PUT, chapterPath, vacation)
      yield
        assertEquals(created, expected)
        assertEquals(updated, expected)
    }

  test("should preserve chapter disappearance and all workspace and pre-update service failures"):
    val refs         = Refs()
    val server       = buildApi(refs, workspaceResult = GetResult.Failed, albumResult = GetAlbumResult.Failed)
    val disappeared  = buildApi(refs, updateResult = UpdateResult.ChapterNotFound)
    val serviceError = ApiResponse(Status.InternalServerError, json("""{"code":"service_failure"}"""))

    for
      workspace <- send(server, Method.GET, s"$albumPath/workspace")
      update    <- send(server, Method.PUT, chapterPath, vacation)
      missing   <- send(disappeared, Method.PUT, chapterPath, vacation)
    yield
      assertEquals(workspace, serviceError)
      assertEquals(update, serviceError)
      assertEquals(missing, ApiResponse(Status.NotFound, json("""{"code":"chapter_not_found"}""")))

  final private case class Refs():
    val calls: Ref[IO, Vector[String]] = Ref.unsafe(Vector.empty)

  final private case class ApiResponse(status: Status, body: Json)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildApi(
      refs: Refs,
      workspaceResult: GetResult = GetResult.Found(album, NonEmptyList.of(country, otherCountry), photoRoot = "/photos"),
      albumResult: GetAlbumResult = GetAlbumResult.Found(album),
      createResult: CreateResult = CreateResult.Created(chapter.id),
      updateResult: UpdateResult = UpdateResult.Updated
  ) =
    val workspaces = new AlbumWorkspaceManager[IO]:
      override def get(id: AlbumId): IO[GetResult] = workspaceResult.pure[IO]
    val albums = new AlbumManager[IO]:
      override def get(id: AlbumId): IO[GetAlbumResult]                              = albumResult.pure[IO]
      override def list(page: Int): IO[ListAlbumsResult]                             = ListAlbumsResult.Failed.pure[IO]
      override def create(year: AlbumYear, title: AlbumTitle): IO[CreateAlbumResult] = CreateAlbumResult.Failed.pure[IO]
      override def rename(id: AlbumId, title: AlbumTitle): IO[RenameResult]          = RenameResult.Failed.pure[IO]
      override def delete(id: AlbumId): IO[DeleteAlbumResult]                        = DeleteAlbumResult.Failed.pure[IO]
    val chapters = new ChapterManager[IO]:
      override def create(id: AlbumId, name: DestinationName, countries: NonEmptyList[CountryId], period: Period, folder: SourceFolder) =
        refs.calls.update(_ :+ s"create:${id.value}:$name|$period|$folder|${countries.toList.map(_.value).mkString(",")}").as(createResult)
      override def update(
          id: AlbumId,
          target: ChapterId,
          name: DestinationName,
          countries: NonEmptyList[CountryId],
          period: Period,
          folder: SourceFolder
      ) =
        val call = s"update:${id.value}:${target.value}:$name|$period|$folder|${countries.toList.map(_.value).mkString(",")}"
        refs.calls.update(_ :+ call).as(updateResult)
    AlbumWorkspaceApi.make(workspaces, albums, chapters).routes.orNotFound
  // scalafix:on DisableSyntax.defaultArgs

  // scalafix:off DisableSyntax.defaultArgs
  private def send(server: HttpApp[IO], method: Method, path: String, body: String = "") =
    val request = Request[IO](method, Uri.unsafeFromString(path)).withEntity(body).putHeaders(`Content-Type`(MediaType.application.json))

    for
      response <- server.run(request)
      content  <- response.as[Json]
    yield
      assertEquals(response.contentType.map(_.mediaType), MediaType.application.json.some)
      ApiResponse(response.status, content)
  // scalafix:on DisableSyntax.defaultArgs

  private def json(value: String)     = parse(value).fold(error => fail(error.message), identity)
  private def hasFeedback(body: Json) =
    body.asObject.exists(_.keys.toSet === Set("message")) && body.hcursor.get[String]("message").toOption.exists(_.nonEmpty)
