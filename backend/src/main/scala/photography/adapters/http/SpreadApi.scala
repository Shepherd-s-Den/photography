package photography.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.http4s.dsl.impl.OptionalQueryParamDecoderMatcher
import org.http4s.{HttpRoutes, MessageBodyFailure, Request}
import photography.usecases.spread.SpreadManager
import photography.usecases.spread.SpreadManager.{AddResult, ChangeTemplateResult, Conflict as SpreadConflict, DeleteResult, Direction, MoveResult}
import java.util.UUID
import io.circe.{Decoder, DecodingFailure, Json}
import photography.domain.album.{AlbumId, ChapterId, PhotoId}
import photography.domain.layout.*

trait SpreadApi[F[_]]:
  def routes: HttpRoutes[F]

object SpreadApi:
  def make[F[_]: Async](spreads: SpreadManager[F]): SpreadApi[F] = new LiveSpreadApi(spreads)

  final private class LiveSpreadApi[F[_]: Async](spreads: SpreadManager[F]) extends SpreadApi[F] with Http4sDsl[F]:
    override def routes: HttpRoutes[F] =
      HttpRoutes.of[F] {
        case GET -> Root / "api" / "albums" / CanonicalId(album) / "spreads" / CanonicalId(spread) / "placements" =>
          spreads.readPlacements(AlbumId(album), SpreadId(spread)).flatMap:
            case SpreadManager.PlacementReadResult.Found(value)   => Ok(encodeSpread(value))
            case SpreadManager.PlacementReadResult.AlbumNotFound  => NotFound(Map("code" -> "album_not_found"))
            case SpreadManager.PlacementReadResult.SpreadNotFound => NotFound(Map("code" -> "spread_not_found"))
            case SpreadManager.PlacementReadResult.Failed         => InternalServerError(Map("code" -> "service_failure"))
        case request @ POST -> Root / "api" / "albums" / CanonicalId(album) / "spreads" / CanonicalId(spread) / "placements" =>
          decodePlacement(request, buildSpreadDecoder).flatMap:
            case Left(message) => BadRequest(Map("message" -> message))
            case Right(edit)   => spreads.editPlacements(AlbumId(album), SpreadId(spread), edit).flatMap(encodeSpreadResult)
        case request @ POST -> Root / "api" / "albums" / CanonicalId(album) / "chapters" / CanonicalId(chapter) / "spreads" =>
          add(AlbumId(album), ChapterId(chapter), request)
        case request @ POST -> Root / "api" / "albums" / CanonicalId(album) / "spreads" / CanonicalId(spread) / "move" =>
          move(AlbumId(album), SpreadId(spread), request)
        case request @ PUT -> Root / "api" / "albums" / CanonicalId(album) / "spreads" / CanonicalId(spread) / "template" :?
            ConfirmPlacements(maybeConfirm) =>
          changeTemplate(AlbumId(album), SpreadId(spread), maybeConfirm = maybeConfirm, request)
        case DELETE -> Root / "api" / "albums" / CanonicalId(album) / "spreads" / CanonicalId(spread) :? ConfirmPlacements(maybeConfirm) =>
          delete(AlbumId(album), SpreadId(spread), maybeConfirm = maybeConfirm)
      }

    private def add(album: AlbumId, chapter: ChapterId, request: Request[F]) =
      decode(request, afterDecoder).flatMap:
        case Left(message) => BadRequest(Map("message" -> message))
        case Right(after)  =>
          spreads.add(album, chapter, after).flatMap:
            case AddResult.Added(id)          => Created(Map("id" -> id.value.toString))
            case AddResult.Rejected(conflict) => BadRequest(Map("message" -> explain(conflict)))
            case AddResult.AlbumNotFound      => albumNotFound
            case AddResult.ChapterNotFound    => notFound("chapter_not_found")
            case AddResult.SpreadNotFound     => notFound("spread_not_found")
            case AddResult.Failed             => serviceFailure

    private def move(album: AlbumId, spread: SpreadId, request: Request[F]) =
      decode(request, directionDecoder).flatMap:
        case Left(message)    => BadRequest(Map("message" -> message))
        case Right(direction) =>
          spreads.move(album, spread, direction).flatMap:
            case MoveResult.Moved(id)          => Ok(Map("id" -> id.value.toString))
            case MoveResult.Rejected(conflict) => BadRequest(Map("message" -> explain(conflict)))
            case MoveResult.AlbumNotFound      => albumNotFound
            case MoveResult.SpreadNotFound     => notFound("spread_not_found")
            case MoveResult.Failed             => serviceFailure

    private def changeTemplate(album: AlbumId, spread: SpreadId, maybeConfirm: Option[String], request: Request[F]) =
      decode(request, templateDecoder).map(_.flatMap(template => parseConfirmation(maybeValue = maybeConfirm).map((template, _)))).flatMap:
        case Left(message)              => BadRequest(Map("message" -> message))
        case Right((template, confirm)) =>
          spreads.changeTemplate(album, spread, template, confirmPlacements = confirm).flatMap:
            case ChangeTemplateResult.Changed(id, layout) =>
              Ok(Json.obj("id" -> Json.fromString(id.value.toString), "templateId" -> Json.fromString(layout.value.toString)))
            case ChangeTemplateResult.Occupied(placements) => Conflict(encodeOccupied(placements))
            case ChangeTemplateResult.Rejected(conflict)   => BadRequest(Map("message" -> explain(conflict)))
            case ChangeTemplateResult.AlbumNotFound        => albumNotFound
            case ChangeTemplateResult.SpreadNotFound       => notFound("spread_not_found")
            case ChangeTemplateResult.Failed               => serviceFailure

    private def delete(album: AlbumId, spread: SpreadId, maybeConfirm: Option[String]) =
      parseConfirmation(maybeValue = maybeConfirm) match
        case Left(message)  => BadRequest(Map("message" -> message))
        case Right(confirm) =>
          spreads.delete(album, spread, confirmPlacements = confirm).flatMap:
            case DeleteResult.Deleted              => NoContent()
            case DeleteResult.Occupied(placements) => Conflict(encodeOccupied(placements))
            case DeleteResult.Rejected(conflict)   => BadRequest(Map("message" -> explain(conflict)))
            case DeleteResult.AlbumNotFound        => albumNotFound
            case DeleteResult.SpreadNotFound       => notFound("spread_not_found")
            case DeleteResult.Failed               => serviceFailure

    private def parseConfirmation(maybeValue: Option[String]) =
      maybeValue.traverse {
        case "true"  => true.asRight[String]
        case "false" => false.asRight[String]
        case _       => "confirmPlacements must be true or false".asLeft[Boolean]
      }.map(_.contains(true))

    private def decode[A](request: Request[F], decoder: Decoder[A]) =
      request.as[Json].map(value => decoder.decodeJson(value).leftMap(_.message))
        .recover { case _: MessageBodyFailure => "Supply a valid JSON spread request".asLeft[A] }

    private val afterDecoder = Decoder.decodeString
      .emap(value => CanonicalId.unapply(value).map(SpreadId(_)).toRight("Spread IDs must be canonical UUIDs"))
      .prepare(_.downField("afterSpreadId"))

    private val templateDecoder = Decoder.decodeString
      .emap(value => CanonicalId.unapply(value).map(SpreadTemplateId(_)).toRight("Template IDs must be canonical UUIDs"))
      .prepare(_.downField("templateId"))

    private val directionDecoder = Decoder.decodeString
      .emap(parseDirection)
      .prepare(_.downField("direction"))

    private def parseDirection(value: String) = value match
      case "earlier" => Direction.Earlier.asRight[String]
      case "later"   => Direction.Later.asRight[String]
      case _         => "Choose earlier or later".asLeft[Direction]

    private def encodeOccupied(placements: Int) =
      Json.obj("code" -> Json.fromString("spread_occupied"), "placements" -> Json.fromInt(placements))

    private def explain(conflict: SpreadConflict) = conflict match
      case SpreadConflict.AtCapacity      => "An album can contain at most 65 interior spreads"
      case SpreadConflict.AddTarget       => "Add a spread after a chapter opening or photo spread"
      case SpreadConflict.ContextMismatch => "Choose a layout for this spread's context"
      case SpreadConflict.OpeningMove     => "Openings cannot be moved"
      case SpreadConflict.OpeningDelete   => "Openings cannot be deleted"
      case SpreadConflict.NoNeighbour     => "Photo spreads can only move within their chapter"
      case SpreadConflict.UnknownTemplate => "Choose a layout from the catalog"

    private def albumNotFound          = NotFound(Map("code" -> "album_not_found"))
    private def notFound(code: String) = NotFound(Map("code" -> code))
    private def serviceFailure         = InternalServerError(Map("code" -> "service_failure"))

    private object ConfirmPlacements extends OptionalQueryParamDecoderMatcher[String]("confirmPlacements")

    private object CanonicalId:
      def unapply(value: String): Option[UUID] =
        Either.catchOnly[IllegalArgumentException](UUID.fromString(value)).toOption.filter(_.toString === value)

    private def encodeSpreadResult(result: SpreadManager.PlacementResult) = result match
      case SpreadManager.PlacementResult.Saved(value)     => Ok(encodeSpread(value))
      case SpreadManager.PlacementResult.Rejected(reason) => Conflict(Map("code" -> explainSpreadFailure(reason)))
      case SpreadManager.PlacementResult.AlbumNotFound    => NotFound(Map("code" -> "album_not_found"))
      case SpreadManager.PlacementResult.SpreadNotFound   => NotFound(Map("code" -> "spread_not_found"))
      case SpreadManager.PlacementResult.Failed           => InternalServerError(Map("code" -> "service_failure"))

    private def explainSpreadFailure(reason: SpreadManager.Failure) = reason match
      case SpreadManager.Failure.PhotoNotInCollection => "photo_not_in_collection"
      case SpreadManager.Failure.PhotoAlreadyUsed     => "photo_already_used"
      case SpreadManager.Failure.SlotNotFound         => "slot_not_found"
      case SpreadManager.Failure.PlacementNotFound    => "placement_not_found"
      case SpreadManager.Failure.InvalidTransform     => "invalid_transform"

    private def decodePlacement[A](request: Request[F], decoder: Decoder[A]) =
      request.as[Json].map(value => decoder.decodeJson(value).leftMap(_.getMessage))
        .recover { case _: MessageBodyFailure => "Supply a valid JSON placement request".asLeft[A] }

    private def buildSpreadDecoder = Decoder.instance[SpreadManager.Edit]: cursor =>
      cursor.get[String]("operation").flatMap:
        case "place" => (PlacementJson.decodeId(cursor, field = "slotId"), PlacementJson.decodeId(cursor, field = "photoId")).mapN { (slot, photo) =>
            SpreadManager.Edit.Place(PhotoSpreadSlotId(slot), PhotoId(photo))
          }
        case "remove" => PlacementJson.decodeId(cursor, field = "placementId").map(id => SpreadManager.Edit.Remove(SpreadPlacementId(id)))
        case "adjust" =>
          (
            PlacementJson.decodeId(cursor, field = "placementId"),
            cursor.get[PhotoTransform]("transform")(using PlacementJson.buildTransformDecoder)
          ).mapN { (id, transform) =>
            SpreadManager.Edit.Adjust(SpreadPlacementId(id), transform)
          }
        case "move" => (PlacementJson.decodeId(cursor, field = "placementId"), PlacementJson.decodeId(cursor, field = "slotId")).mapN {
            (source, slot) =>
              SpreadManager.Edit.Move(SpreadPlacementId(source), PhotoSpreadSlotId(slot))
          }
        case _ => DecodingFailure("Choose a placement operation", cursor.history).asLeft[SpreadManager.Edit]

    private def encodeSpread(spread: Spread) =
      val placements = spread.placements.map: value =>
        PlacementJson.encodePlacement(id = value.id.value, slot = value.slot.value, value.photo, value.transform)
          .deepMerge(Json.obj("captionCorner" -> Json.fromString(encodeCorner(value.captionCorner))))
      Json.obj("id" -> Json.fromString(spread.id.value.toString), "placements" -> Json.arr(placements*))

    private def encodeCorner(corner: CaptionCorner) = corner match
      case CaptionCorner.TopLeft     => "top_left"
      case CaptionCorner.TopRight    => "top_right"
      case CaptionCorner.BottomLeft  => "bottom_left"
      case CaptionCorner.BottomRight => "bottom_right"
