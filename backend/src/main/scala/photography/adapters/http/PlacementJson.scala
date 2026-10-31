package photography.adapters.http

import cats.syntax.all.*
import io.circe.{Decoder, DecodingFailure, HCursor, Json}
import io.github.iltotore.iron.*
import photography.domain.album.PhotoId
import photography.domain.layout.*
import java.util.UUID

private[http] object PlacementJson:
  def buildTransformDecoder: Decoder[PhotoTransform] = Decoder.instance[PhotoTransform]: cursor =>
    for
      x        <- cursor.get[InDesignPoints]("x")(using buildPointDecoder)
      y        <- cursor.get[InDesignPoints]("y")(using buildPointDecoder)
      zoom     <- cursor.get[Zoom]("zoom")(using buildZoomDecoder)
      mirrored <- cursor.get[Boolean]("mirrored")
    yield PhotoTransform(Shift(x = x, y = y), mirrored = mirrored, zoom = zoom)

  private def buildPointDecoder = Decoder.decodeDouble.emap[InDesignPoints](_.refineEither)
  private def buildZoomDecoder  = Decoder.decodeDouble.emap[Zoom](_.refineEither)

  def decodeId(cursor: HCursor, field: String): Either[DecodingFailure, UUID] =
    cursor.get[String](field).flatMap(value => CanonicalId.unapply(value).toRight(DecodingFailure("IDs must be canonical UUIDs", cursor.history)))

  def encodePlacement(id: UUID, slot: UUID, photo: PhotoId, transform: PhotoTransform): Json =
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

  object CanonicalId:
    def unapply(value: String): Option[UUID] =
      Either.catchOnly[IllegalArgumentException](UUID.fromString(value)).toOption.filter(_.toString === value)
