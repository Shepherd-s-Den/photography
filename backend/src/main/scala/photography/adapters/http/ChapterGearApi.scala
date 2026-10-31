package photography.adapters.http

import cats.data.NonEmptyVector
import cats.effect.Async
import cats.syntax.all.*
import io.circe.{Decoder, Json}
import org.http4s.{CacheDirective, HttpRoutes, MessageBodyFailure, Request}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Cache-Control`
import photography.domain.album.{AlbumId, ChapterId}
import photography.domain.gear.*
import photography.usecases.chapter.ChapterManager
import photography.usecases.chapter.ChapterManager.*
import photography.usecases.gear.GearManager
import java.util.UUID

trait ChapterGearApi[F[_]]:
  def routes: HttpRoutes[F]

object ChapterGearApi:
  def make[F[_]: Async](chapters: ChapterManager[F]): ChapterGearApi[F] = new LiveChapterGearApi(chapters)

  final private class LiveChapterGearApi[F[_]: Async](chapters: ChapterManager[F]) extends ChapterGearApi[F] with Http4sDsl[F]:
    override def routes: HttpRoutes[F] =
      HttpRoutes.of[F] {
        case GET -> Root / "api" / "albums" / CanonicalId(album) / "chapters" / CanonicalId(chapter) / "gear" =>
          read(AlbumId(value = album), ChapterId(value = chapter))
        case request @ PUT -> Root / "api" / "albums" / CanonicalId(album) / "chapters" / CanonicalId(chapter) / "gear" =>
          replace(AlbumId(value = album), ChapterId(value = chapter), request)
        case (GET | PUT) -> Root / "api" / "albums" / CanonicalId(_) / "chapters" / _ / "gear" => chapterNotFound
        case (GET | PUT) -> Root / "api" / "albums" / _ / "chapters" / _ / "gear"              => NotFound(Map("code" -> "album_not_found"))
      }

    private def read(album: AlbumId, chapter: ChapterId) =
      chapters.readGear(album, chapter).flatMap:
        case GearReadResult.Found(selection) =>
          Ok(encodeSelection(selection = selection)).map(_.putHeaders(`Cache-Control`(CacheDirective.`no-store`)))
        case GearReadResult.NotFound => chapterNotFound
        case GearReadResult.Failed   => serviceFailure

    private def replace(album: AlbumId, chapter: ChapterId, request: Request[F]) =
      decode(request).flatMap:
        case Left(message)    => BadRequest(Map("message" -> message))
        case Right(selection) =>
          chapters.replaceGear(album, chapter, selection = selection).flatMap:
            case GearReplaceResult.Saved            => NoContent()
            case GearReplaceResult.NotFound         => chapterNotFound
            case GearReplaceResult.Rejected(reason) => BadRequest(Map("message" -> explain(reason)))
            case GearReplaceResult.Failed           => serviceFailure
            case GearReplaceResult.Uncertain        => InternalServerError(Map("code" -> "mutation_uncertain"))

    private def decode(request: Request[F]) =
      request.as[Json].map(value => selectionDecoder.decodeJson(value).leftMap(_.getMessage))
        .recover { case _: MessageBodyFailure => "Supply a valid JSON gear selection".asLeft[GearSelection] }

    private def chapterNotFound = NotFound(Map("code" -> "chapter_not_found"))
    private def serviceFailure  = InternalServerError(Map("code" -> "service_failure"))

    private object CanonicalId:
      def unapply(value: String): Option[UUID] =
        Either.catchOnly[IllegalArgumentException](UUID.fromString(value)).toOption.filter(_.toString === value)

    private val idDecoder           = Decoder.decodeString.emap(value => CanonicalId.unapply(value).toRight("Gear IDs must be canonical UUIDs"))
    private given Decoder[CameraId] = idDecoder.map(value => CameraId(value = value))
    private given Decoder[LensId]   = idDecoder.map(value => LensId(value = value))
    private given Decoder[CameraSelection] = Decoder.instance: cursor =>
      for
        camera <- cursor.get[CameraId]("cameraId")
        lenses <- cursor.get[Vector[LensId]]("lensIds")
      yield CameraSelection(
        camera,
        NonEmptyVector.fromVector(lenses).fold[CameraUse](CameraUse.FixedLens)(values =>
          CameraUse.Interchangeable(lenses = values)
        )
      )

    private val selectionDecoder = Decoder.decodeVector[CameraSelection].prepare(_.downField("cameras"))

    private def encodeSelection(selection: GearSelection) =
      val cameras = selection.map: entry =>
        val lenses = entry.use match
          case CameraUse.FixedLens               => Vector.empty[LensId]
          case CameraUse.Interchangeable(values) => values.toVector
        Json.obj(
          "cameraId" -> Json.fromString(entry.camera.value.toString),
          "lensIds"  -> Json.arr(lenses.map(id => Json.fromString(id.value.toString))*)
        )
      Json.obj("cameras" -> Json.arr(cameras*))

    private def explain(reason: GearManager.Failure) = reason match
      case GearManager.Failure.DuplicateCamera    => "Select each camera only once"
      case GearManager.Failure.UnknownCamera      => "Select a camera from the current catalog"
      case GearManager.Failure.CameraKindMismatch => "Select lenses only for interchangeable cameras"
      case GearManager.Failure.DuplicateLens      => "Select each lens only once per camera"
      case GearManager.Failure.UnknownLens        => "Select a lens from the current catalog"
      case GearManager.Failure.IncompatibleLens   => "Select lenses with a compatible mount and coverage"
