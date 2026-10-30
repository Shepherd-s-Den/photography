package photography.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import io.circe.{Decoder, DecodingFailure, HCursor, Json}
import io.github.iltotore.iron.*
import org.http4s.{HttpRoutes, MessageBodyFailure, Request}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import photography.domain.album.{AlbumId, PhotoId}
import photography.domain.layout.*
import photography.ports.cover.CoverRepository.Composition
import photography.usecases.cover.CoverManager
import photography.usecases.spread.SpreadManager
import java.util.UUID

object PhotoPlacementApi:
  def make[F[_]: Async](spreads: SpreadManager[F], covers: CoverManager[F]): HttpRoutes[F] =
    new LivePhotoPlacementApi(spreads, covers).routes

  final private class LivePhotoPlacementApi[F[_]: Async](spreads: SpreadManager[F], covers: CoverManager[F]) extends Http4sDsl[F]:
    def routes: HttpRoutes[F] = HttpRoutes.of[F] {
      case GET -> Root / "api" / "albums" / CanonicalId(album) / "spreads" / CanonicalId(spread) / "placements" =>
        spreads.readPlacements(AlbumId(album), SpreadId(spread)).flatMap:
          case SpreadManager.PlacementReadResult.Found(value)   => Ok(encodeSpread(value))
          case SpreadManager.PlacementReadResult.AlbumNotFound  => NotFound(Map("code" -> "album_not_found"))
          case SpreadManager.PlacementReadResult.SpreadNotFound => NotFound(Map("code" -> "spread_not_found"))
          case SpreadManager.PlacementReadResult.Failed         => InternalServerError(Map("code" -> "service_failure"))
      case request @ POST -> Root / "api" / "albums" / CanonicalId(album) / "spreads" / CanonicalId(spread) / "placements" =>
        decode(request, buildSpreadDecoder).flatMap:
          case Left(message) => BadRequest(Map("message" -> message))
          case Right(edit)   => spreads.editPlacements(AlbumId(album), SpreadId(spread), edit).flatMap(encodeSpreadResult)
      case GET -> Root / "api" / "albums" / CanonicalId(album) / "cover" / "placements" =>
        covers.read(AlbumId(album)).flatMap:
          case CoverManager.ReadResult.Found(value)  => Ok(encodeCover(value))
          case CoverManager.ReadResult.CoverNotFound => NotFound(Map("code" -> "cover_not_found"))
          case CoverManager.ReadResult.Failed        => InternalServerError(Map("code" -> "service_failure"))
      case request @ POST -> Root / "api" / "albums" / CanonicalId(album) / "cover" / "placements" =>
        decode(request, buildCoverDecoder).flatMap:
          case Left(message) => BadRequest(Map("message" -> message))
          case Right(edit)   => covers.edit(AlbumId(album), edit).flatMap(encodeCoverResult)
    }

    private def encodeSpreadResult(result: SpreadManager.PlacementResult) = result match
      case SpreadManager.PlacementResult.Saved(value)     => Ok(encodeSpread(value))
      case SpreadManager.PlacementResult.Rejected(reason) => Conflict(Map("code" -> explainSpreadFailure(reason)))
      case SpreadManager.PlacementResult.AlbumNotFound    => NotFound(Map("code" -> "album_not_found"))
      case SpreadManager.PlacementResult.SpreadNotFound   => NotFound(Map("code" -> "spread_not_found"))
      case SpreadManager.PlacementResult.Failed           => InternalServerError(Map("code" -> "service_failure"))

    private def encodeCoverResult(result: CoverManager.Result) = result match
      case CoverManager.Result.Saved(value)     => Ok(encodeCover(value))
      case CoverManager.Result.Rejected(reason) => Conflict(Map("code" -> explainCoverFailure(reason)))
      case CoverManager.Result.CoverNotFound    => NotFound(Map("code" -> "cover_not_found"))
      case CoverManager.Result.Failed           => InternalServerError(Map("code" -> "service_failure"))

    private def explainSpreadFailure(reason: SpreadManager.Failure) = reason match
      case SpreadManager.Failure.PhotoNotInCollection => "photo_not_in_collection"
      case SpreadManager.Failure.PhotoAlreadyUsed     => "photo_already_used"
      case SpreadManager.Failure.SlotNotFound         => "slot_not_found"
      case SpreadManager.Failure.PlacementNotFound    => "placement_not_found"
      case SpreadManager.Failure.InvalidTransform     => "invalid_transform"

    private def explainCoverFailure(reason: CoverManager.Failure) = reason match
      case CoverManager.Failure.PhotoNotInCollection => "photo_not_in_collection"
      case CoverManager.Failure.PhotoAlreadyUsed     => "photo_already_used"
      case CoverManager.Failure.SlotNotFound         => "slot_not_found"
      case CoverManager.Failure.PlacementNotFound    => "placement_not_found"
      case CoverManager.Failure.InvalidTransform     => "invalid_transform"

    private def decode[A](request: Request[F], decoder: Decoder[A]) =
      request.as[Json].map(value => decoder.decodeJson(value).leftMap(_.getMessage))
        .recover { case _: MessageBodyFailure => "Supply a valid JSON placement request".asLeft[A] }

    private def buildSpreadDecoder = Decoder.instance[SpreadManager.Edit]: cursor =>
      cursor.get[String]("operation").flatMap:
        case "place" => (decodeId(cursor, field = "slotId"), decodeId(cursor, field = "photoId")).mapN { (slot, photo) =>
            SpreadManager.Edit.Place(PhotoSpreadSlotId(slot), PhotoId(photo))
          }
        case "remove" => decodeId(cursor, field = "placementId").map(id => SpreadManager.Edit.Remove(SpreadPlacementId(id)))
        case "adjust" =>
          (decodeId(cursor, field = "placementId"), cursor.get[PhotoTransform]("transform")(using buildTransformDecoder)).mapN { (id, transform) =>
            SpreadManager.Edit.Adjust(SpreadPlacementId(id), transform)
          }
        case "move" => (decodeId(cursor, field = "placementId"), decodeId(cursor, field = "slotId")).mapN { (source, slot) =>
            SpreadManager.Edit.Move(SpreadPlacementId(source), PhotoSpreadSlotId(slot))
          }
        case _ => DecodingFailure("Choose a placement operation", cursor.history).asLeft[SpreadManager.Edit]

    private def buildCoverDecoder = Decoder.instance[CoverManager.Edit]: cursor =>
      cursor.get[String]("operation").flatMap:
        case "place" => (decodeId(cursor, field = "slotId"), decodeId(cursor, field = "photoId")).mapN { (slot, photo) =>
            CoverManager.Edit.Place(CoverSlotId(slot), PhotoId(photo))
          }
        case "remove" => decodeId(cursor, field = "placementId").map(id => CoverManager.Edit.Remove(CoverPlacementId(id)))
        case "adjust" =>
          (decodeId(cursor, field = "placementId"), cursor.get[PhotoTransform]("transform")(using buildTransformDecoder)).mapN { (id, transform) =>
            CoverManager.Edit.Adjust(CoverPlacementId(id), transform)
          }
        case "move" => (decodeId(cursor, field = "placementId"), decodeId(cursor, field = "slotId")).mapN { (source, slot) =>
            CoverManager.Edit.Move(CoverPlacementId(source), CoverSlotId(slot))
          }
        case _ => DecodingFailure("Choose a placement operation", cursor.history).asLeft[CoverManager.Edit]

    private def buildTransformDecoder = Decoder.instance[PhotoTransform]: cursor =>
      for
        x        <- cursor.get[InDesignPoints]("x")(using buildPointDecoder)
        y        <- cursor.get[InDesignPoints]("y")(using buildPointDecoder)
        zoom     <- cursor.get[Zoom]("zoom")(using buildZoomDecoder)
        mirrored <- cursor.get[Boolean]("mirrored")
      yield PhotoTransform(Shift(x = x, y = y), mirrored = mirrored, zoom = zoom)

    private def buildPointDecoder = Decoder.decodeDouble.emap[InDesignPoints](_.refineEither)
    private def buildZoomDecoder  = Decoder.decodeDouble.emap[Zoom](_.refineEither)

    private def decodeId(cursor: HCursor, field: String) =
      cursor.get[String](field).flatMap(value => CanonicalId.unapply(value).toRight(DecodingFailure("IDs must be canonical UUIDs", cursor.history)))

    private def encodeSpread(spread: Spread) =
      val placements = spread.placements.map: value =>
        encodePlacement(id = value.id.value, slot = value.slot.value, value.photo, value.transform)
          .deepMerge(Json.obj("captionCorner" -> Json.fromString(encodeCorner(value.captionCorner))))
      Json.obj("id" -> Json.fromString(spread.id.value.toString), "placements" -> Json.arr(placements*))

    private def encodeCorner(corner: CaptionCorner) = corner match
      case CaptionCorner.TopLeft     => "top_left"
      case CaptionCorner.TopRight    => "top_right"
      case CaptionCorner.BottomLeft  => "bottom_left"
      case CaptionCorner.BottomRight => "bottom_right"

    private def encodeCover(value: Composition) =
      val placements =
        value.placements.map(placement => encodePlacement(id = placement.id.value, slot = placement.slot.value, placement.photo, placement.transform))
      val spine = value.cover.spineOwner match
        case SpineOwner.Front => "front"
        case SpineOwner.Back  => "back"
      Json.obj(
        "frontSlotId" -> Json.fromString(value.cover.front.id.value.toString),
        "backSlotId"  -> Json.fromString(value.cover.back.id.value.toString),
        "spineOwner"  -> Json.fromString(spine),
        "placements"  -> Json.arr(placements*)
      )

    private def encodePlacement(id: UUID, slot: UUID, photo: PhotoId, transform: PhotoTransform) =
      val adjustment = Json.obj(
        "x"        -> Json.fromDoubleOrNull(transform.shift.x),
        "y"        -> Json.fromDoubleOrNull(transform.shift.y),
        "zoom"     -> Json.fromDoubleOrNull(transform.zoom),
        "mirrored" -> Json.fromBoolean(transform.mirrored)
      )
      Json.obj(
        "id"        -> Json.fromString(id.toString),
        "slotId"    -> Json.fromString(slot.toString),
        "photoId"   -> Json.fromString(photo.value.toString),
        "transform" -> adjustment
      )

    private object CanonicalId:
      def unapply(value: String): Option[UUID] =
        Either.catchOnly[IllegalArgumentException](UUID.fromString(value)).toOption.filter(_.toString === value)
