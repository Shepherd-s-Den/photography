package photography.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import io.circe.{Decoder, DecodingFailure, Json}
import org.http4s.{HttpRoutes, MessageBodyFailure, Request}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import photography.domain.album.{AlbumId, PhotoId}
import photography.domain.layout.*
import photography.ports.cover.CoverRepository.Composition
import photography.usecases.cover.CoverManager

trait CoverApi[F[_]]:
  def routes: HttpRoutes[F]

object CoverApi:
  def make[F[_]: Async](covers: CoverManager[F]): CoverApi[F] = new LiveCoverApi(covers)

  final private class LiveCoverApi[F[_]: Async](covers: CoverManager[F]) extends CoverApi[F] with Http4sDsl[F]:
    override def routes: HttpRoutes[F] = HttpRoutes.of[F] {
      case GET -> Root / "api" / "albums" / PlacementJson.CanonicalId(album) / "cover" / "placements" =>
        covers.read(AlbumId(album)).flatMap:
          case CoverManager.ReadResult.Found(value)  => Ok(encodeCover(value))
          case CoverManager.ReadResult.CoverNotFound => NotFound(Map("code" -> "cover_not_found"))
          case CoverManager.ReadResult.Failed        => InternalServerError(Map("code" -> "service_failure"))
      case request @ POST -> Root / "api" / "albums" / PlacementJson.CanonicalId(album) / "cover" / "placements" =>
        decode(request, buildCoverDecoder).flatMap:
          case Left(message) => BadRequest(Map("message" -> message))
          case Right(edit)   => covers.edit(AlbumId(album), edit).flatMap(encodeCoverResult)
    }

    private def encodeCoverResult(result: CoverManager.Result) = result match
      case CoverManager.Result.Saved(value)     => Ok(encodeCover(value))
      case CoverManager.Result.Rejected(reason) => Conflict(Map("code" -> explainCoverFailure(reason)))
      case CoverManager.Result.CoverNotFound    => NotFound(Map("code" -> "cover_not_found"))
      case CoverManager.Result.Failed           => InternalServerError(Map("code" -> "service_failure"))

    private def explainCoverFailure(reason: CoverManager.Failure) = reason match
      case CoverManager.Failure.PhotoNotInCollection => "photo_not_in_collection"
      case CoverManager.Failure.PhotoAlreadyUsed     => "photo_already_used"
      case CoverManager.Failure.SlotNotFound         => "slot_not_found"
      case CoverManager.Failure.PlacementNotFound    => "placement_not_found"
      case CoverManager.Failure.InvalidTransform     => "invalid_transform"

    private def decode[A](request: Request[F], decoder: Decoder[A]) =
      request.as[Json].map(value => decoder.decodeJson(value).leftMap(_.getMessage))
        .recover { case _: MessageBodyFailure => "Supply a valid JSON placement request".asLeft[A] }

    private def buildCoverDecoder = Decoder.instance[CoverManager.Edit]: cursor =>
      cursor.get[String]("operation").flatMap:
        case "place" => (PlacementJson.decodeId(cursor, field = "slotId"), PlacementJson.decodeId(cursor, field = "photoId")).mapN { (slot, photo) =>
            CoverManager.Edit.Place(CoverSlotId(slot), PhotoId(photo))
          }
        case "remove" => PlacementJson.decodeId(cursor, field = "placementId").map(id => CoverManager.Edit.Remove(CoverPlacementId(id)))
        case "adjust" =>
          (
            PlacementJson.decodeId(cursor, field = "placementId"),
            cursor.get[PhotoTransform]("transform")(using PlacementJson.buildTransformDecoder)
          ).mapN { (id, transform) =>
            CoverManager.Edit.Adjust(CoverPlacementId(id), transform)
          }
        case "move" => (PlacementJson.decodeId(cursor, field = "placementId"), PlacementJson.decodeId(cursor, field = "slotId")).mapN {
            (source, slot) =>
              CoverManager.Edit.Move(CoverPlacementId(source), CoverSlotId(slot))
          }
        case _ => DecodingFailure("Choose a placement operation", cursor.history).asLeft[CoverManager.Edit]

    private def encodeCover(value: Composition) =
      val placements =
        value.placements.map(placement =>
          PlacementJson.encodePlacement(id = placement.id.value, slot = placement.slot.value, placement.photo, placement.transform)
        )
      val spine = value.cover.spineOwner match
        case SpineOwner.Front => "front"
        case SpineOwner.Back  => "back"
      Json.obj(
        "frontSlotId" -> Json.fromString(value.cover.front.id.value.toString),
        "backSlotId"  -> Json.fromString(value.cover.back.id.value.toString),
        "spineOwner"  -> Json.fromString(spine),
        "placements"  -> Json.arr(placements*)
      )
