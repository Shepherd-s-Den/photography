package photography.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import org.http4s.{CacheDirective, HttpRoutes, MessageBodyFailure, Request}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Cache-Control`
import photography.domain.gear.*
import photography.usecases.gear.GearManager
import photography.usecases.gear.GearManager.*
import java.util.UUID

trait GearApi[F[_]]:
  def routes: HttpRoutes[F]

object GearApi:
  def make[F[_]: Async](gear: GearManager[F]): GearApi[F] = new LiveGearApi(gear)

  final private class LiveGearApi[F[_]: Async](gear: GearManager[F]) extends GearApi[F] with Http4sDsl[F]:
    override def routes: HttpRoutes[F] =
      HttpRoutes.of[F] {
        case GET -> Root / "api" / "gear"                                => readCatalog
        case request @ POST -> Root / "api" / "cameras"                  => createCamera(request)
        case request @ POST -> Root / "api" / "lenses"                   => createLens(request)
        case request @ PUT -> Root / "api" / "cameras" / CanonicalId(id) => updateCamera(CameraId(value = id), request)
        case request @ PUT -> Root / "api" / "lenses" / CanonicalId(id)  => updateLens(LensId(value = id), request)
        case DELETE -> Root / "api" / "cameras" / CanonicalId(id)        => deleteCamera(CameraId(value = id))
        case DELETE -> Root / "api" / "lenses" / CanonicalId(id)         => deleteLens(LensId(value = id))
        case (PUT | DELETE) -> Root / "api" / "cameras" / _              => cameraNotFound
        case (PUT | DELETE) -> Root / "api" / "lenses" / _               => lensNotFound
      }

    private def readCatalog =
      gear.readCatalog.flatMap:
        case CatalogResult.Found(catalog) => Ok(catalog).map(_.putHeaders(`Cache-Control`(CacheDirective.`no-store`)))
        case CatalogResult.Failed         => serviceFailure

    private def createCamera(request: Request[F]) =
      decode(request, cameraDecoder).flatMap:
        case Left(message)             => BadRequest(Map("message" -> message))
        case Right((code, name, kind)) =>
          gear.createCamera(code, name, kind).flatMap:
            case CameraCreateResult.Created(camera) => Created(camera)
            case CameraCreateResult.Conflict        => catalogConflict
            case CameraCreateResult.Failed          => serviceFailure
            case CameraCreateResult.Uncertain       => mutationUncertain

    private def updateCamera(id: CameraId, request: Request[F]) =
      decode(request, cameraDecoder).flatMap:
        case Left(message)             => BadRequest(Map("message" -> message))
        case Right((code, name, kind)) =>
          gear.updateCamera(Camera(id, code, name, kind)).flatMap:
            case CameraUpdateResult.Updated              => NoContent()
            case CameraUpdateResult.NotFound             => cameraNotFound
            case CameraUpdateResult.Conflict             => catalogConflict
            case CameraUpdateResult.InvalidatesSelection => invalidatesSelection
            case CameraUpdateResult.Failed               => serviceFailure
            case CameraUpdateResult.Uncertain            => mutationUncertain

    private def deleteCamera(id: CameraId) =
      gear.deleteCamera(id).flatMap:
        case CameraDeleteResult.Deleted   => NoContent()
        case CameraDeleteResult.NotFound  => cameraNotFound
        case CameraDeleteResult.Used      => Conflict(Map("code" -> "camera_used"))
        case CameraDeleteResult.Failed    => serviceFailure
        case CameraDeleteResult.Uncertain => mutationUncertain

    private def createLens(request: Request[F]) =
      decode(request, lensDecoder).flatMap:
        case Left(message)                        => BadRequest(Map("message" -> message))
        case Right((code, name, mount, coverage)) =>
          gear.createLens(code, name, mount, coverage).flatMap:
            case LensCreateResult.Created(lens) => Created(lens)
            case LensCreateResult.Conflict      => catalogConflict
            case LensCreateResult.Failed        => serviceFailure
            case LensCreateResult.Uncertain     => mutationUncertain

    private def updateLens(id: LensId, request: Request[F]) =
      decode(request, lensDecoder).flatMap:
        case Left(message)                        => BadRequest(Map("message" -> message))
        case Right((code, name, mount, coverage)) =>
          gear.updateLens(Lens(id, code, name, mount, coverage)).flatMap:
            case LensUpdateResult.Updated              => NoContent()
            case LensUpdateResult.NotFound             => lensNotFound
            case LensUpdateResult.Conflict             => catalogConflict
            case LensUpdateResult.InvalidatesSelection => invalidatesSelection
            case LensUpdateResult.Failed               => serviceFailure
            case LensUpdateResult.Uncertain            => mutationUncertain

    private def deleteLens(id: LensId) =
      gear.deleteLens(id).flatMap:
        case LensDeleteResult.Deleted   => NoContent()
        case LensDeleteResult.NotFound  => lensNotFound
        case LensDeleteResult.Used      => Conflict(Map("code" -> "lens_used"))
        case LensDeleteResult.Failed    => serviceFailure
        case LensDeleteResult.Uncertain => mutationUncertain

    private def decode[A](request: Request[F], decoder: Decoder[A]) =
      request.as[Json].map(value => decoder.decodeJson(value).leftMap(_.getMessage))
        .recover { case _: MessageBodyFailure => "Supply a valid JSON gear object".asLeft[A] }

    private def cameraNotFound       = NotFound(Map("code" -> "camera_not_found"))
    private def lensNotFound         = NotFound(Map("code" -> "lens_not_found"))
    private def catalogConflict      = Conflict(Map("code" -> "catalog_conflict"))
    private def invalidatesSelection = Conflict(Map("code" -> "invalidates_selection"))
    private def serviceFailure       = InternalServerError(Map("code" -> "service_failure"))
    private def mutationUncertain    = InternalServerError(Map("code" -> "mutation_uncertain"))

    private object CanonicalId:
      def unapply(value: String): Option[UUID] =
        Either.catchOnly[IllegalArgumentException](UUID.fromString(value)).toOption.filter(_.toString === value)

    private val textDecoder          = Decoder.decodeString.emap(_.refineEither[Not[Empty]])
    private given Decoder[LensMount] = Decoder.decodeString.emap:
      case "sony_e"  => LensMount.SonyE.asRight[String]
      case "nikon_z" => LensMount.NikonZ.asRight[String]
      case _         => "Choose sony_e or nikon_z".asLeft[LensMount]

    private given Decoder[SensorFormat] = Decoder.decodeString.emap:
      case "aps_c"      => SensorFormat.ApsC.asRight[String]
      case "full_frame" => SensorFormat.FullFrame.asRight[String]
      case _            => "Choose aps_c or full_frame".asLeft[SensorFormat]

    private val kindDecoder = Decoder.instance[CameraKind]: cursor =>
      cursor.get[String]("kind").flatMap:
        case "fixed_lens" =>
          val contradictory = cursor.downField("mount").succeeded || cursor.downField("sensorFormat").succeeded
          Either.cond(!contradictory, CameraKind.FixedLens, DecodingFailure("Fixed-lens cameras must omit mount and sensorFormat", cursor.history))
        case "interchangeable_lens" =>
          for
            mount  <- cursor.get[LensMount]("mount")
            sensor <- cursor.get[SensorFormat]("sensorFormat")
          yield CameraKind.InterchangeableLens(mount, sensor)
        case _ => DecodingFailure("Choose fixed_lens or interchangeable_lens", cursor.history).asLeft[CameraKind]

    private val cameraDecoder = Decoder.instance[(CameraExifCode, CameraName, CameraKind)]: cursor =>
      for
        code <- cursor.get[CameraExifCode]("code")(using textDecoder)
        name <- cursor.get[CameraName]("name")(using textDecoder)
        kind <- kindDecoder(cursor)
      yield (code, name, kind)

    private val lensDecoder = Decoder.instance[(LensExifCode, LensName, LensMount, SensorFormat)]: cursor =>
      for
        code     <- cursor.get[LensExifCode]("code")(using textDecoder)
        name     <- cursor.get[LensName]("name")(using textDecoder)
        mount    <- cursor.get[LensMount]("mount")
        coverage <- cursor.get[SensorFormat]("coverage")
      yield (code, name, mount, coverage)

    private given Encoder[Camera] = Encoder.instance: camera =>
      val optics = camera.kind match
        case CameraKind.FixedLens                          => Vector("kind" -> Json.fromString("fixed_lens"))
        case CameraKind.InterchangeableLens(mount, sensor) =>
          Vector(
            "kind"         -> Json.fromString("interchangeable_lens"),
            "mount"        -> Json.fromString(encodeMount(mount)),
            "sensorFormat" -> Json.fromString(encodeSensor(sensor))
          )
      val identity = Vector(
        "id"   -> Json.fromString(camera.id.value.toString),
        "code" -> Json.fromString(camera.code),
        "name" -> Json.fromString(camera.friendlyName)
      )
      Json.obj((identity ++ optics)*)

    private given Encoder[Lens] = Encoder.forProduct5("id", "code", "name", "mount", "coverage"): lens =>
      (lens.id.value.toString, lens.code: String, lens.friendlyName: String, encodeMount(lens.mount), encodeSensor(lens.coverage))

    private given Encoder[GearCatalog] = Encoder.forProduct2("cameras", "lenses")(catalog => (catalog.cameras, catalog.lenses))

    private def encodeMount(mount: LensMount) = mount match
      case LensMount.SonyE  => "sony_e"
      case LensMount.NikonZ => "nikon_z"

    private def encodeSensor(sensor: SensorFormat) = sensor match
      case SensorFormat.ApsC      => "aps_c"
      case SensorFormat.FullFrame => "full_frame"
