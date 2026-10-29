package photography.adapters.http

import cats.data.NonEmptyList
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.jawn.parse
import io.github.iltotore.iron.*
import munit.CatsEffectSuite
import org.http4s.{CacheDirective, HttpApp, MediaType, Method, Request, Status, Uri}
import org.http4s.headers.`Content-Type`
import org.http4s.headers.`Cache-Control`
import org.http4s.circe.jsonDecoder
import photography.domain.album.*
import photography.domain.layout.*
import photography.usecases.{AlbumManager, AlbumWorkspaceManager}
import photography.usecases.ChapterManager
import photography.usecases.AlbumManager.*
import photography.usecases.AlbumWorkspaceManager.GetResult
import photography.usecases.ChapterManager.{CreateResult, Period, UpdateResult}
import photography.usecases.ChapterPhotoImport.{ExecutionOutcome, Scheduling, Status as ImportStatus}
import photography.usecases.{PhotoInventory, PhotoManager, PhotoWriteRecovery}

import java.nio.file.Path
import java.time.{Instant, LocalDate, LocalDateTime}
import java.util.UUID

final class AlbumWorkspaceApiSeamIntegrationTest extends CatsEffectSuite:
  private val country      = Country(CountryId(new UUID(0L, 8L)), code = "RO", name = "Romania", AbsoluteLocation(Path.of("/flags/ro.svg")))
  private val otherCountry = country.copy(id = CountryId(new UUID(0L, 9L)), code = "JP", name = "Japan")
  private val box          = SlotBox(x = 10.0, y = 20.0, width = 30.0, height = 40.0)
  private val text         = SpreadSlot.Text(TextSpreadSlotId(new UUID(0L, 6L)), box)
  private val photo        = SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(0L, 7L)), box, bleed = Set.empty)
  private val template     = SpreadTemplateId(new UUID(0L, 4L))
  private val chapter      =
    val destination = Destination(DestinationId(new UUID(0L, 10L)), name = "Coast", countries = NonEmptyList.one(country))
    val opening     =
      Spread.ChapterStart(SpreadId(new UUID(0L, 3L)), template, photoSlots = Vector(photo), text, placements = Vector.empty[SpreadPlacement])
    val photos =
      Spread.PhotosOnly(SpreadId(new UUID(0L, 5L)), template, photoSlots = NonEmptyList.one(photo), placements = Vector.empty[SpreadPlacement])
    Chapter(
      ChapterId(new UUID(0L, 2L)),
      destination,
      VisitPeriod(LocalDate.of(2024, 2, 29), days = 2),
      description = "Sea air",
      sourceFolder = "/photos/coast",
      opening,
      spreads = Vector(photos)
    )
  private val album =
    val emptyPhotos     = Vector.empty[SpreadSlot.Photo]
    val emptyPlacements = Vector.empty[SpreadPlacement]
    val opening         = Spread.AlbumStart(SpreadId(new UUID(0L, 11L)), template, photoSlots = emptyPhotos, text, placements = emptyPlacements)
    Album(AlbumId(new UUID(0L, 1L)), year = 2024, title = "Our year", synopsis = "", Instant.EPOCH, opening, chapters = Vector(chapter))

  private val emptyInventories           = Map.empty[ChapterId, AlbumWorkspaceManager.ChapterPhotos]
  private val workspace: GetResult.Found =
    GetResult.Found(album, NonEmptyList.of(country, otherCountry), photoRoot = "/photos", photoInventories = emptyInventories)

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
        {"id":"${album.opening.id.value}","chapter":null,"kind":"album_start","templateId":"${template.value}","slots":[
          {"id":"${text.id.value}","kind":"text","x":10.0,"y":20.0,"width":30.0,"height":40.0}]},
        {"id":"${chapter.opening.id.value}","chapter":"${chapter.id.value}","kind":"chapter_start","templateId":"${template.value}","slots":[
          {"id":"${photo.id.value}","kind":"photo","x":10.0,"y":20.0,"width":30.0,"height":40.0},
          {"id":"${text.id.value}","kind":"text","x":10.0,"y":20.0,"width":30.0,"height":40.0}]},
        {"id":"${new UUID(0L, 5L)}","chapter":"${chapter.id.value}","kind":"photos_only","templateId":"${template.value}","slots":[
          {"id":"${photo.id.value}","kind":"photo","x":10.0,"y":20.0,"width":30.0,"height":40.0}]}],
      "countries":[{"id":"${country.id.value}","name":"Romania","code":"RO"},{"id":"${otherCountry.id.value}","name":"Japan","code":"JP"}],
      "photoInventories":{}}""")

    send(server, Method.GET, s"$albumPath/workspace").map(response => assertEquals(response, ApiResponse(Status.Ok, expected)))

  test("should expose an empty chapter list and the saved full-year Home dates"):
    val homeChapter = chapter.copy(
      destination = chapter.destination.copy(name = "Home"),
      period = VisitPeriod(LocalDate.of(2024, 1, 1), days = 366)
    )
    val emptyAlbum      = album.copy(chapters = Vector.empty[Chapter])
    val homeAlbum       = album.copy(chapters = Vector(homeChapter))
    val selectedCountry = NonEmptyList.one(country)
    val emptyWorkspace  = workspace.copy(album = emptyAlbum, countries = selectedCountry, photoRoot = "/custom")
    val homeWorkspace   = workspace.copy(album = homeAlbum, countries = selectedCountry)
    val emptyServer     = buildApi(Refs(), workspaceResult = emptyWorkspace)
    val homeServer      = buildApi(Refs(), workspaceResult = homeWorkspace)

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
    val createdId        = json(s"""{"id":"${chapter.id.value}","scheduling":"queued"}""")
    val updatedId        = json(s"""{"id":"${chapter.id.value}","scheduling":"not-requested"}""")
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
      assertEquals(createdVacation, ApiResponse(Status.Created, createdId))
      assertEquals(createdHome, ApiResponse(Status.Created, createdId))
      assertEquals(updatedVacation, ApiResponse(Status.Ok, updatedId))
      assertEquals(updatedHome, ApiResponse(Status.Ok, updatedId))
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

  test("should adapt chapter-addressed inventory and Refresh with explicit missing and failure outcomes"):
    val empty     = PhotoInventory.Inventory(photos = Vector.empty[Photo], placed = Set.empty[PhotoId])
    val execution = ExecutionOutcome(ImportStatus.Idle, retirementIssues = Vector.empty[PhotoWriteRecovery.Issue])
    val found     = AlbumWorkspaceManager.ChapterResult.Found(AlbumWorkspaceManager.ChapterPhotos(empty, execution))
    val scenarios = Vector(
      (found, ChapterManager.RefreshResult.Scheduled(Scheduling.Queued), Status.Ok, Status.Accepted),
      (found, ChapterManager.RefreshResult.Scheduled(Scheduling.Coalesced), Status.Ok, Status.Accepted),
      (AlbumWorkspaceManager.ChapterResult.ChapterNotFound, ChapterManager.RefreshResult.ChapterNotFound, Status.NotFound, Status.NotFound),
      (AlbumWorkspaceManager.ChapterResult.Failed, ChapterManager.RefreshResult.Failed, Status.InternalServerError, Status.InternalServerError),
      (found, ChapterManager.RefreshResult.Scheduled(Scheduling.Failed), Status.Ok, Status.InternalServerError),
      (found, ChapterManager.RefreshResult.Scheduled(Scheduling.NotRequested), Status.Ok, Status.InternalServerError)
    )

    scenarios.traverse_ { case (inventory, refresh, getStatus, postStatus) =>
      val server = buildApi(Refs(), chapterResult = inventory, refreshResult = refresh)
      val path   = s"/api/chapters/${chapter.id.value}/photos"

      for
        read      <- send(server, Method.GET, path)
        scheduled <- send(server, Method.POST, s"$path/refresh")
        malformed <- send(server, Method.POST, "/api/chapters/bad/photos/refresh")
        missing   <- send(server, Method.GET, "/api/chapters/bad/photos")
      yield
        assertEquals((read.status, scheduled.status), (getStatus, postStatus))
        assertEquals(malformed, ApiResponse(Status.NotFound, json("""{"code":"chapter_not_found"}""")))
        assertEquals(missing, malformed)
    }

  test("should expose safe processing feedback and preserve saved scheduling outcomes"):
    val empty          = PhotoInventory.Inventory(photos = Vector.empty[Photo], placed = Set.empty[PhotoId])
    val completed      = PhotoManager.RefreshResult(unsupportedCount = 2, issues = Vector(PhotoManager.Issue.Undecodable(filename = "bad.jpg")))
    val idleProcessing = json("""{"state":"idle","unsupportedCount":0,"skippedCount":0,"issues":[]}""")
    val launchFailed   = json("""{"state":"failed","unsupportedCount":0,"skippedCount":0,
      "issues":[{"stage":"scheduling","code":"scheduling_failed","filename":null}]}""")
    val uncertain = json("""{"state":"failed","unsupportedCount":0,"skippedCount":0,
      "issues":[{"stage":"persistence","code":"persistence_failed","filename":null}]}""")
    val skipped = json("""{"state":"completed","unsupportedCount":2,"skippedCount":1,
      "issues":[{"stage":"read","code":"undecodable","filename":"bad.jpg"}]}""")
    val statuses = Vector(
      (ImportStatus.Idle, idleProcessing),
      (ImportStatus.Queued, idleProcessing.deepMerge(json("""{"state":"queued"}"""))),
      (ImportStatus.Running, idleProcessing.deepMerge(json("""{"state":"running"}"""))),
      (ImportStatus.LaunchFailed, launchFailed),
      (ImportStatus.Uncertain, uncertain),
      (ImportStatus.Finished(completed), skipped)
    )

    statuses.traverse_ { case (status, expected) =>
      val execution = ExecutionOutcome(status, retirementIssues = Vector.empty[PhotoWriteRecovery.Issue])
      val found     = AlbumWorkspaceManager.ChapterResult.Found(AlbumWorkspaceManager.ChapterPhotos(empty, execution))
      val server    = buildApi(Refs(), chapterResult = found)

      send(server, Method.GET, s"/api/chapters/${chapter.id.value}/photos").map: response =>
        assertEquals(response.body.hcursor.get[Json]("processing"), expected.asRight)
        assertEquals(response.body.hcursor.downField("counts").get[Int]("total"), 0.asRight)
    } >> Scheduling.values.toVector.traverse_ { scheduling =>
      val server   = buildApi(Refs(), createResult = CreateResult.Created(chapter.id, scheduling), updateResult = UpdateResult.Updated(scheduling))
      val expected = scheduling match
        case Scheduling.Queued       => "queued"
        case Scheduling.Coalesced    => "coalesced"
        case Scheduling.NotRequested => "not-requested"
        case Scheduling.Failed       => "failed"

      for
        created <- send(server, Method.POST, s"$albumPath/chapters", vacation)
        updated <- send(server, Method.PUT, chapterPath, vacation)
      yield
        assertEquals((created.status, updated.status), (Status.Created, Status.Ok))
        assertEquals(created.body.hcursor.get[String]("scheduling"), expected.asRight)
        assertEquals(updated.body.hcursor.get[String]("scheduling"), expected.asRight)
    }

  test("should encode completed photos and every failure without exposing source paths or write identities"):
    val location = ThumbnailLocation(new UUID(0L, 80L))
    val intent   = PhotoWriteIntentId(new UUID(0L, 81L))
    val issues   = Vector(
      PhotoManager.Issue.Discovery,
      PhotoManager.Issue.Read(filename = "one.jpg"),
      PhotoManager.Issue.Generation(filename = "one.jpg"),
      PhotoManager.Issue.Undecodable(filename = "one.jpg"),
      PhotoManager.Issue.Unstable(filename = "one.jpg"),
      PhotoManager.Issue.Storage,
      PhotoManager.Issue.Cleanup(location),
      PhotoManager.Issue.Persistence,
      PhotoManager.Issue.Unexpected,
      PhotoManager.Issue.WriteReferences(intent),
      PhotoManager.Issue.WriteCompletion(intent)
    )
    val cleanup = Vector(
      PhotoWriteRecovery.Issue.Listing,
      PhotoWriteRecovery.Issue.References(intent),
      PhotoWriteRecovery.Issue.Cleanup(intent, location),
      PhotoWriteRecovery.Issue.Completion(intent)
    )
    val expectedIssues = Vector(
      ("discovery", "unreadable_source", Json.Null),
      ("read", "unreadable_source", Json.fromString("one.jpg")),
      ("generation", "encoding_failed", Json.fromString("one.jpg")),
      ("read", "undecodable", Json.fromString("one.jpg")),
      ("read", "unstable", Json.fromString("one.jpg")),
      ("storage", "storage_failed", Json.Null),
      ("cleanup", "cleanup_failed", Json.Null)
    ) ++ Vector.fill(6)(("persistence", "persistence_failed", Json.Null)) ++
      Vector(("cleanup", "cleanup_failed", Json.Null), ("persistence", "persistence_failed", Json.Null))
    val expectedFeedback = expectedIssues.map { case (stage, code, filename) =>
      Json.obj("stage" -> Json.fromString(stage), "code" -> Json.fromString(code), "filename" -> filename)
    }
    val expectedProcessing = Json.obj(
      "state"            -> Json.fromString("failed"),
      "unsupportedCount" -> Json.fromInt(3),
      "skippedCount"     -> Json.fromInt(2),
      "issues"           -> Json.arr(expectedFeedback*)
    )

    Vector(false, true).traverse_ { annotated =>
      val maybeDate    = Option.when(annotated)(LocalDateTime.of(2026, 1, 2, 3, 4))
      val maybeCaption = Option.when(annotated)("Caption")
      val metadata     = PhotoMetadata(hash = "source-hash", maybeCapturedLocal = maybeDate, PhotoDimensions(width = 20, height = 30))
      val thumbnail    = Thumbnail(location, version = "version", ThumbnailDimensions(width = 10, height = 15), ThumbnailQuality.Standard, bytes = 1)
      val photo        = Photo(
        PhotoId(new UUID(0L, 82L)),
        chapter.id,
        OriginalPhotoLocation(source = "/private/originals", filename = "one.jpg"),
        metadata,
        thumbnail,
        maybeCaption = maybeCaption
      )
      val inventory = PhotoInventory.Inventory(photos = Vector(photo), placed = Set(photo.id))
      val execution =
        ExecutionOutcome(ImportStatus.Finished(PhotoManager.RefreshResult(unsupportedCount = 3, issues = issues)), retirementIssues = cleanup)
      val snapshot = AlbumWorkspaceManager.ChapterPhotos(inventory, execution)
      val server   = buildApi(
        Refs(),
        chapterResult = AlbumWorkspaceManager.ChapterResult.Found(snapshot),
        workspaceResult = workspace.copy(photoInventories = Map(chapter.id -> snapshot))
      )
      val expectedPhoto = json(s"""{"id":"${photo.id.value}","filename":"one.jpg","capturedLocal":${maybeDate.fold("null")(date => s"\"$date\"")},
        "width":20,"height":30,"caption":${maybeCaption.fold("null")(caption => s"\"$caption\"")},
        "thumbnail":{"url":"/thumbnails/${location.value}/version.jpg","width":10,"height":15}}""")

      for
        read      <- send(server, Method.GET, s"/api/chapters/${chapter.id.value}/photos")
        workspace <- send(server, Method.GET, s"$albumPath/workspace")
      yield
        assertEquals(read.body.hcursor.get[Vector[Json]]("photos"), Vector(expectedPhoto).asRight)
        assertEquals(read.body.hcursor.get[Json]("processing"), expectedProcessing.asRight)
        assertEquals(workspace.body.hcursor.downField("photoInventories").get[Json](chapter.id.value.toString), read.body.asRight)
        assert(!read.body.noSpaces.contains("/private") && !read.body.noSpaces.contains(intent.value.toString))
    }

  final private case class Refs():
    val calls: Ref[IO, Vector[String]] = Ref.unsafe(Vector.empty)

  final private case class ApiResponse(status: Status, body: Json)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildApi(
      refs: Refs,
      workspaceResult: GetResult = workspace,
      albumResult: GetAlbumResult = GetAlbumResult.Found(album),
      createResult: CreateResult = CreateResult.Created(chapter.id, Scheduling.Queued),
      updateResult: UpdateResult = UpdateResult.Updated(Scheduling.NotRequested),
      chapterResult: AlbumWorkspaceManager.ChapterResult = AlbumWorkspaceManager.ChapterResult.Failed,
      refreshResult: ChapterManager.RefreshResult = ChapterManager.RefreshResult.Failed
  ) =
    val workspaces = new AlbumWorkspaceManager[IO]:
      override def get(id: AlbumId): IO[GetResult]                                     = workspaceResult.pure[IO]
      override def listChapter(id: ChapterId): IO[AlbumWorkspaceManager.ChapterResult] = chapterResult.pure[IO]
    val albums = new AlbumManager[IO]:
      override def get(id: AlbumId): IO[GetAlbumResult]                              = albumResult.pure[IO]
      override def list(page: Int): IO[ListAlbumsResult]                             = ListAlbumsResult.Failed.pure[IO]
      override def create(year: AlbumYear, title: AlbumTitle): IO[CreateAlbumResult] = CreateAlbumResult.Failed.pure[IO]
      override def rename(id: AlbumId, title: AlbumTitle): IO[RenameResult]          = RenameResult.Failed.pure[IO]
      override def delete(id: AlbumId): IO[DeleteAlbumResult]                        = DeleteAlbumResult.Failed.pure[IO]
    val chapters = new ChapterManager[IO]:
      override def refresh(chapter: ChapterId): IO[ChapterManager.RefreshResult] = refreshResult.pure[IO]
      override def create(id: AlbumId, name: DestinationName, countries: NonEmptyList[CountryId], period: Period, folder: PhotoSource) =
        refs.calls.update(_ :+ s"create:${id.value}:$name|$period|$folder|${countries.toList.map(_.value).mkString(",")}").as(createResult)
      override def update(
          id: AlbumId,
          target: ChapterId,
          name: DestinationName,
          countries: NonEmptyList[CountryId],
          period: Period,
          folder: PhotoSource
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
      if method === Method.GET && response.status === Status.Ok && (path.endsWith("/workspace") || path.endsWith("/photos")) then
        assertEquals(response.headers.get[`Cache-Control`], `Cache-Control`(CacheDirective.`no-store`).some)
      ApiResponse(response.status, content)
  // scalafix:on DisableSyntax.defaultArgs

  private def json(value: String)     = parse(value).fold(error => fail(error.message), identity)
  private def hasFeedback(body: Json) =
    body.asObject.exists(_.keys.toSet === Set("message")) && body.hcursor.get[String]("message").toOption.exists(_.nonEmpty)
