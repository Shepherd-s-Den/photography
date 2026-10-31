package photography.adapters.http

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.github.iltotore.iron.*
import munit.CatsEffectSuite
import org.http4s.{HttpApp, MediaType, Method, Request, Status, Uri}
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import photography.domain.gear.*
import photography.usecases.gear.GearManager
import photography.usecases.gear.GearManager.*
import java.util.UUID

final class GearApiSeamIntegrationTest extends CatsEffectSuite:
  private val camera     = Camera(CameraId(value = new UUID(0L, 1L)), code = " code ", friendlyName = "\nName ", CameraKind.FixedLens)
  private val lens       = Lens(LensId(value = new UUID(0L, 2L)), code = "lens", friendlyName = "Lens", LensMount.SonyE, SensorFormat.FullFrame)
  private val cameraPath = s"/api/cameras/${camera.id.value}"
  private val lensPath   = s"/api/lenses/${lens.id.value}"
  private val fixedInput = """{"code":" code ","name":"\nName ","kind":"fixed_lens"}"""
  private val lensInput  = """{"code":"lens","name":"Lens","mount":"sony_e","coverage":"full_frame"}"""

  test("should publish ordered catalogs with every camera optic and lens enum and explicit empty success"):
    val cameras = Vector(camera) ++ LensMount.values.toVector.flatMap: mount =>
      SensorFormat.values.toVector.map: sensor =>
        val id                   = CameraId(value = new UUID(1L, (mount.ordinal * 2 + sensor.ordinal).toLong))
        val code: CameraExifCode = s"body-${mount.ordinal}-${sensor.ordinal}".refineUnsafe
        camera.copy(id = id, code = code, kind = CameraKind.InterchangeableLens(mount, sensor))
    val lenses = LensMount.values.toVector.flatMap: mount =>
      SensorFormat.values.toVector.map: coverage =>
        val id                 = LensId(value = new UUID(2L, (mount.ordinal * 2 + coverage.ordinal).toLong))
        val code: LensExifCode = s"lens-${mount.ordinal}-${coverage.ordinal}".refineUnsafe
        lens.copy(id = id, code = code, mount = mount, coverage = coverage)
    val catalog  = GearCatalog(cameras = cameras, lenses = lenses)
    val refs     = Refs()
    val server   = buildGearApi(refs, catalogResult = CatalogResult.Found(catalog))
    val expected = Json.obj("cameras" -> Json.arr(cameras.map(encodeCamera)*), "lenses" -> Json.arr(lenses.map(encodeLens)*))

    for
      response <- send(server, Method.GET, path = "/api/gear", body = "")
      empty    <- send(buildGearApi(Refs()), Method.GET, path = "/api/gear", body = "")
      calls    <- refs.calls.get
    yield
      assertEquals(response, (Status.Ok, expected.noSpaces, MediaType.application.json.some, "no-store".some))
      assertEquals(empty, (Status.Ok, """{"cameras":[],"lenses":[]}""", MediaType.application.json.some, "no-store".some))
      assertEquals(calls, expected = Vector(Call.Read))

  test("should create and completely edit typed optics without trimming or reading after acknowledgement"):
    val cameras = Vector(camera) ++ LensMount.values.toVector.flatMap: mount =>
      SensorFormat.values.toVector.map(sensor => camera.copy(kind = CameraKind.InterchangeableLens(mount, sensor)))
    val lenses = LensMount.values.toVector.flatMap: mount =>
      SensorFormat.values.toVector.map(coverage => lens.copy(mount = mount, coverage = coverage))

    (cameras.map(_.asLeft[Lens]) ++ lenses.map(_.asRight[Camera])).traverse_ { entry =>
      val refs   = Refs()
      val server = entry.fold(
        value => buildGearApi(refs, cameraCreate = CameraCreateResult.Created(value)),
        value => buildGearApi(refs, lensCreate = LensCreateResult.Created(value))
      )
      val (path, input, expected, expectedCalls) = entry match
        case Left(value) =>
          val json = encodeCamera(value)
          (
            cameraPath,
            json.mapObject(_.remove(key = "id")),
            json,
            Vector(Call.CreateCamera(value.code, value.friendlyName, value.kind), Call.UpdateCamera(value))
          )
        case Right(value) =>
          val json = encodeLens(value)
          (
            lensPath,
            json.mapObject(_.remove(key = "id")),
            json,
            Vector(Call.CreateLens(value.code, value.friendlyName, value.mount, value.coverage), Call.UpdateLens(value))
          )
      val collectionPath     = entry.fold(_ => "/api/cameras", _ => "/api/lenses")
      val misleadingIdentity = input.deepMerge(Json.obj("id" -> Json.fromString(value = new UUID(0L, 99L).toString)))

      for
        created <- send(server, Method.POST, path = collectionPath, body = input.noSpaces)
        updated <- send(server, Method.PUT, path = path, body = misleadingIdentity.noSpaces)
        calls   <- refs.calls.get
      yield
        assertEquals(created, (Status.Created, expected.noSpaces, MediaType.application.json.some, None))
        assertEquals(updated, (Status.NoContent, "", None, None))
        assertEquals(calls, expectedCalls)
    }

  test("should translate every catalog mutation result without retry or a postwrite read"):
    val creations = Vector(
      (CameraCreateResult.Conflict, LensCreateResult.Conflict, Status.Conflict, "catalog_conflict"),
      (CameraCreateResult.Failed, LensCreateResult.Failed, Status.InternalServerError, "service_failure"),
      (CameraCreateResult.Uncertain, LensCreateResult.Uncertain, Status.InternalServerError, "mutation_uncertain")
    )
    val updates = Vector(
      (CameraUpdateResult.Updated, LensUpdateResult.Updated, Status.NoContent, ""),
      (CameraUpdateResult.NotFound, LensUpdateResult.NotFound, Status.NotFound, "not_found"),
      (CameraUpdateResult.Conflict, LensUpdateResult.Conflict, Status.Conflict, "catalog_conflict"),
      (CameraUpdateResult.InvalidatesSelection, LensUpdateResult.InvalidatesSelection, Status.Conflict, "invalidates_selection"),
      (CameraUpdateResult.Failed, LensUpdateResult.Failed, Status.InternalServerError, "service_failure"),
      (CameraUpdateResult.Uncertain, LensUpdateResult.Uncertain, Status.InternalServerError, "mutation_uncertain")
    )
    val deletions = Vector(
      (CameraDeleteResult.Deleted, LensDeleteResult.Deleted, Status.NoContent, ""),
      (CameraDeleteResult.NotFound, LensDeleteResult.NotFound, Status.NotFound, "not_found"),
      (CameraDeleteResult.Used, LensDeleteResult.Used, Status.Conflict, "used"),
      (CameraDeleteResult.Failed, LensDeleteResult.Failed, Status.InternalServerError, "service_failure"),
      (CameraDeleteResult.Uncertain, LensDeleteResult.Uncertain, Status.InternalServerError, "mutation_uncertain")
    )

    val checkCreations = creations.traverse_ { case (cameraResult, lensResult, status, code) =>
      val refs          = Refs()
      val server        = buildGearApi(refs, cameraCreate = cameraResult, lensCreate = lensResult)
      val expectedCalls = Vector(
        Call.CreateCamera(camera.code, camera.friendlyName, camera.kind),
        Call.CreateLens(lens.code, lens.friendlyName, lens.mount, lens.coverage)
      )

      for
        cameraResponse <- send(server, Method.POST, path = "/api/cameras", body = fixedInput)
        lensResponse   <- send(server, Method.POST, path = "/api/lenses", body = lensInput)
        calls          <- refs.calls.get
      yield
        assertEquals(cameraResponse, encodeErrorResponse(status, code = code))
        assertEquals(lensResponse, encodeErrorResponse(status, code = code))
        assertEquals(calls, expectedCalls)
    }
    val checkUpdates = updates.traverse_ { case (cameraResult, lensResult, status, code) =>
      val refs   = Refs()
      val server = buildGearApi(refs, cameraUpdate = cameraResult, lensUpdate = lensResult)

      for
        cameraResponse <- send(server, Method.PUT, path = cameraPath, body = fixedInput)
        lensResponse   <- send(server, Method.PUT, path = lensPath, body = lensInput)
        calls          <- refs.calls.get
      yield
        assertEquals(cameraResponse, encodeMutationResponse(status, code = code, resource = "camera"))
        assertEquals(lensResponse, encodeMutationResponse(status, code = code, resource = "lens"))
        assertEquals(calls, expected = Vector(Call.UpdateCamera(camera), Call.UpdateLens(lens)))
    }
    val checkDeletions = deletions.traverse_ { case (cameraResult, lensResult, status, code) =>
      val refs   = Refs()
      val server = buildGearApi(refs, cameraDelete = cameraResult, lensDelete = lensResult)

      for
        cameraResponse <- send(server, Method.DELETE, path = cameraPath, body = "")
        lensResponse   <- send(server, Method.DELETE, path = lensPath, body = "")
        calls          <- refs.calls.get
      yield
        assertEquals(cameraResponse, encodeMutationResponse(status, code = code, resource = "camera"))
        assertEquals(lensResponse, encodeMutationResponse(status, code = code, resource = "lens"))
        assertEquals(calls, expected = Vector(Call.DeleteCamera(camera.id), Call.DeleteLens(lens.id)))
    }

    checkCreations >> checkUpdates >> checkDeletions

  test("should reject malformed bodies and contradictory fixed optics before calling the application"):
    val common = Vector("{", "[]", "null", "{}", """{"code":"","name":"Name"}""", """{"code":1,"name":"Name"}""", """{"code":"code","name":null}""")
    val fixedJson          = encodeCamera(camera).mapObject(_.remove(key = "id"))
    val lensJsonInput      = encodeLens(lens).mapObject(_.remove(key = "id"))
    val invalidFixedFields = Vector("code", "name", "kind").flatMap { field =>
      Vector(fixedJson.mapObject(_.remove(key = field))) ++ Vector(Json.Null, Json.fromInt(value = 1), Json.fromString(value = "")).map(value =>
        fixedJson.mapObject(_.add(key = field, value = value))
      )
    }.map(_.noSpaces)
    val invalidLensFields = Vector("code", "name", "mount", "coverage").flatMap { field =>
      Vector(lensJsonInput.mapObject(_.remove(key = field))) ++ Vector(Json.Null, Json.fromInt(value = 1), Json.fromString(value = "")).map(value =>
        lensJsonInput.mapObject(_.add(key = field, value = value))
      )
    }.map(_.noSpaces)
    val invalidCameras = common ++ invalidFixedFields ++ Vector(
      """{"code":"code","name":"Name","kind":"unknown"}""",
      """{"code":"code","name":"Name","kind":1}""",
      """{"code":"code","name":"","kind":"fixed_lens"}""",
      """{"code":"code","name":"Name","kind":"fixed_lens","mount":null}""",
      """{"code":"code","name":"Name","kind":"fixed_lens","sensorFormat":"aps_c"}""",
      """{"code":"code","name":"Name","kind":"interchangeable_lens"}""",
      """{"code":"code","name":"Name","kind":"interchangeable_lens","sensorFormat":"aps_c"}""",
      """{"code":"code","name":"Name","kind":"interchangeable_lens","mount":null,"sensorFormat":"aps_c"}""",
      """{"code":"code","name":"Name","kind":"interchangeable_lens","mount":"sony_e"}""",
      """{"code":"code","name":"Name","kind":"interchangeable_lens","mount":"sony_e","sensorFormat":null}""",
      """{"code":"code","name":"Name","kind":"interchangeable_lens","mount":"bad","sensorFormat":"aps_c"}""",
      """{"code":"code","name":"Name","kind":"interchangeable_lens","mount":1,"sensorFormat":"aps_c"}""",
      """{"code":"code","name":"Name","kind":"interchangeable_lens","mount":"sony_e","sensorFormat":false}""",
      """{"code":"code","name":"Name","kind":"interchangeable_lens","mount":"sony_e","sensorFormat":"bad"}"""
    )
    val invalidLenses = common ++ invalidLensFields ++ Vector(
      """{"code":"code","name":"","mount":"sony_e","coverage":"aps_c"}""",
      """{"code":"code","name":"Name","mount":"sony_e"}""",
      """{"code":"code","name":"Name","mount":null,"coverage":"aps_c"}""",
      """{"code":"code","name":"Name","mount":"bad","coverage":"aps_c"}""",
      """{"code":"code","name":"Name","mount":"sony_e","coverage":1}""",
      """{"code":"code","name":"Name","mount":"sony_e","coverage":"bad"}"""
    )
    val requests = Vector(Method.POST, Method.PUT).flatMap: method =>
      invalidCameras.map(body => (method, if method === Method.POST then "/api/cameras" else cameraPath, body)) ++
        invalidLenses.map(body => (method, if method === Method.POST then "/api/lenses" else lensPath, body))
    val commonMessages = Vector(
      "Supply a valid JSON gear object",
      "DecodingFailure at .code: Missing required field",
      "DecodingFailure at .code: Missing required field",
      "DecodingFailure at .code: Missing required field",
      "DecodingFailure at .code: !(Should be empty)",
      "DecodingFailure at .code: Got value '1' with wrong type, expecting string",
      "DecodingFailure at .name: Got value 'null' with wrong type, expecting string"
    )
    val fixedFieldMessages = Vector("code", "name", "kind").flatMap: field =>
      val emptyMessage =
        if field === "kind" then "DecodingFailure at : Choose fixed_lens or interchangeable_lens"
        else s"DecodingFailure at .$field: !(Should be empty)"
      Vector(
        s"DecodingFailure at .$field: Missing required field",
        s"DecodingFailure at .$field: Got value 'null' with wrong type, expecting string",
        s"DecodingFailure at .$field: Got value '1' with wrong type, expecting string",
        emptyMessage
      )
    val cameraMessages = commonMessages ++ fixedFieldMessages ++ Vector(
      "DecodingFailure at : Choose fixed_lens or interchangeable_lens",
      "DecodingFailure at .kind: Got value '1' with wrong type, expecting string",
      "DecodingFailure at .name: !(Should be empty)",
      "DecodingFailure at : Fixed-lens cameras must omit mount and sensorFormat",
      "DecodingFailure at : Fixed-lens cameras must omit mount and sensorFormat",
      "DecodingFailure at .mount: Missing required field",
      "DecodingFailure at .mount: Missing required field",
      "DecodingFailure at .mount: Got value 'null' with wrong type, expecting string",
      "DecodingFailure at .sensorFormat: Missing required field",
      "DecodingFailure at .sensorFormat: Got value 'null' with wrong type, expecting string",
      "DecodingFailure at .mount: Choose sony_e or nikon_z",
      "DecodingFailure at .mount: Got value '1' with wrong type, expecting string",
      "DecodingFailure at .sensorFormat: Got value 'false' with wrong type, expecting string",
      "DecodingFailure at .sensorFormat: Choose aps_c or full_frame"
    )
    val lensFieldMessages = Vector("code", "name", "mount", "coverage").flatMap: field =>
      val emptyMessage = field match
        case "mount"    => "DecodingFailure at .mount: Choose sony_e or nikon_z"
        case "coverage" => "DecodingFailure at .coverage: Choose aps_c or full_frame"
        case _          => s"DecodingFailure at .$field: !(Should be empty)"
      Vector(
        s"DecodingFailure at .$field: Missing required field",
        s"DecodingFailure at .$field: Got value 'null' with wrong type, expecting string",
        s"DecodingFailure at .$field: Got value '1' with wrong type, expecting string",
        emptyMessage
      )
    val lensMessages = commonMessages ++ lensFieldMessages ++ Vector(
      "DecodingFailure at .name: !(Should be empty)",
      "DecodingFailure at .coverage: Missing required field",
      "DecodingFailure at .mount: Got value 'null' with wrong type, expecting string",
      "DecodingFailure at .mount: Choose sony_e or nikon_z",
      "DecodingFailure at .coverage: Got value '1' with wrong type, expecting string",
      "DecodingFailure at .coverage: Choose aps_c or full_frame"
    )
    val expectedResponses = Vector(Method.POST, Method.PUT).flatMap: _ =>
      (cameraMessages ++ lensMessages).map: message =>
        (Status.BadRequest, Json.obj("message" -> Json.fromString(value = message)).noSpaces, MediaType.application.json.some, None)
    val refs   = Refs()
    val server = buildGearApi(refs)

    for
      responses <- requests.traverse { case (method, path, body) => send(server, method, path = path, body = body) }
      calls     <- refs.calls.get
    yield
      assertEquals(responses, expectedResponses)
      assertEquals(calls, expected = Vector.empty[Call])

  test("should reject noncanonical route identities and keep read failure distinct from empty success"):
    val refs     = Refs()
    val server   = buildGearApi(refs, catalogResult = CatalogResult.Failed)
    val ids      = Vector("bad", "1-1-1-1-1", "00000000-0000-0000-0000-00000000000A")
    val requests = Vector(Method.PUT, Method.DELETE).flatMap(method =>
      ids.flatMap(id => Vector((method, s"/api/cameras/$id", "camera"), (method, s"/api/lenses/$id", "lens")))
    )
    val readMissingTargets = requests.traverse { case (method, path, resource) =>
      send(server, method, path = path, body = "{}").map(response =>
        (response, encodeErrorResponse(Status.NotFound, code = s"${resource}_not_found"))
      )
    }

    for
      responses <- readMissingTargets
      failed    <- send(server, Method.GET, path = "/api/gear", body = "")
      calls     <- refs.calls.get
    yield
      responses.foreach { case (actual, expected) => assertEquals(actual, expected) }
      assertEquals(failed, encodeErrorResponse(Status.InternalServerError, code = "service_failure"))
      assertEquals(calls, expected = Vector(Call.Read))

  private enum Call:
    case Read
    case CreateCamera(code: CameraExifCode, name: CameraName, kind: CameraKind)
    case UpdateCamera(value: Camera)
    case DeleteCamera(id: CameraId)
    case CreateLens(code: LensExifCode, name: LensName, mount: LensMount, coverage: SensorFormat)
    case UpdateLens(value: Lens)
    case DeleteLens(id: LensId)

  final private case class Refs():
    val calls: Ref[IO, Vector[Call]] = Ref.unsafe(a = Vector.empty[Call])

  // scalafix:off DisableSyntax.defaultArgs
  private def buildGearApi(
      refs: Refs,
      catalogResult: CatalogResult = CatalogResult.Found(GearCatalog(cameras = Vector.empty[Camera], lenses = Vector.empty[Lens])),
      cameraCreate: CameraCreateResult = CameraCreateResult.Created(camera),
      cameraUpdate: CameraUpdateResult = CameraUpdateResult.Updated,
      cameraDelete: CameraDeleteResult = CameraDeleteResult.Deleted,
      lensCreate: LensCreateResult = LensCreateResult.Created(lens),
      lensUpdate: LensUpdateResult = LensUpdateResult.Updated,
      lensDelete: LensDeleteResult = LensDeleteResult.Deleted
  ) =
    val manager = new GearManager[IO]:
      override def readCatalog: IO[CatalogResult] = refs.calls.update(_ :+ Call.Read).as(catalogResult)
      override def createCamera(code: CameraExifCode, name: CameraName, kind: CameraKind): IO[CameraCreateResult] =
        refs.calls.update(_ :+ Call.CreateCamera(code, name, kind)).as(cameraCreate)
      override def updateCamera(value: Camera): IO[CameraUpdateResult] = refs.calls.update(_ :+ Call.UpdateCamera(value)).as(cameraUpdate)
      override def deleteCamera(id: CameraId): IO[CameraDeleteResult]  = refs.calls.update(_ :+ Call.DeleteCamera(id)).as(cameraDelete)
      override def createLens(code: LensExifCode, name: LensName, mount: LensMount, coverage: SensorFormat): IO[LensCreateResult] =
        refs.calls.update(_ :+ Call.CreateLens(code, name, mount, coverage)).as(lensCreate)
      override def updateLens(value: Lens): IO[LensUpdateResult] = refs.calls.update(_ :+ Call.UpdateLens(value)).as(lensUpdate)
      override def deleteLens(id: LensId): IO[LensDeleteResult]  = refs.calls.update(_ :+ Call.DeleteLens(id)).as(lensDelete)
      override def validateSelection(catalog: GearCatalog, selection: GearSelection): ValidationResult =
        fail(message = "HTTP must not validate business selections")
    GearApi.make(manager).routes.orNotFound
  // scalafix:on DisableSyntax.defaultArgs

  private def send(server: HttpApp[IO], method: Method, path: String, body: String) =
    val request = Request[IO](method, Uri.unsafeFromString(s = path)).withEntity(b = body).putHeaders(`Content-Type`(MediaType.application.json))

    for
      response <- server.run(request)
      text     <- response.as[String]
    yield (
      response.status,
      text,
      response.contentType.map(_.mediaType),
      response.headers.get(CIString(value = "Cache-Control")).map(_.head.value)
    )

  private def encodeMutationResponse(status: Status, code: String, resource: String) =
    if status === Status.NoContent then (status, "", None, None)
    else encodeErrorResponse(status, code = if code === "not_found" || code === "used" then s"${resource}_$code" else code)

  private def encodeErrorResponse(status: Status, code: String) =
    (status, Json.obj("code" -> Json.fromString(value = code)).noSpaces, MediaType.application.json.some, None)

  private def encodeCamera(value: Camera) =
    val optics = value.kind match
      case CameraKind.FixedLens                          => Vector("kind" -> Json.fromString(value = "fixed_lens"))
      case CameraKind.InterchangeableLens(mount, sensor) =>
        Vector(
          "kind"         -> Json.fromString(value = "interchangeable_lens"),
          "mount"        -> Json.fromString(value = encodeMount(mount)),
          "sensorFormat" -> Json.fromString(value = encodeSensor(sensor))
        )
    Json.obj((Vector(
      "id"   -> Json.fromString(value = value.id.value.toString),
      "code" -> Json.fromString(value = value.code),
      "name" -> Json.fromString(value = value.friendlyName)
    ) ++ optics)*)

  private def encodeLens(value: Lens) =
    Json.obj(
      "id"       -> Json.fromString(value = value.id.value.toString),
      "code"     -> Json.fromString(value = value.code),
      "name"     -> Json.fromString(value = value.friendlyName),
      "mount"    -> Json.fromString(value = encodeMount(value.mount)),
      "coverage" -> Json.fromString(value = encodeSensor(value.coverage))
    )

  private def encodeMount(value: LensMount) = value match
    case LensMount.SonyE  => "sony_e"
    case LensMount.NikonZ => "nikon_z"

  private def encodeSensor(value: SensorFormat) = value match
    case SensorFormat.ApsC      => "aps_c"
    case SensorFormat.FullFrame => "full_frame"
