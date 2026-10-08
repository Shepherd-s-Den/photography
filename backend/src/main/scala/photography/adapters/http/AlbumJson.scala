package photography.adapters.http

import io.circe.{Json, JsonObject}
import photography.app.album.{AlbumPage, AlbumSummary}
import photography.domain.album.{AlbumComposition, ChapterComposition, Photo}
import photography.domain.layout.*

object AlbumJson:

  def summary(value: AlbumSummary): Json =
    val title: String = value.title
    Json.obj(
      "id"        -> Json.fromString(value.id.value.toString),
      "title"     -> Json.fromString(title),
      "createdAt" -> Json.fromString(value.createdAt.toString)
    )

  def page(value: AlbumPage): Json =
    Json.obj(
      "albums"      -> Json.fromValues(value.albums.map(summary)),
      "page"        -> Json.fromInt(value.page),
      "pageSize"    -> Json.fromInt(value.pageSize),
      "totalAlbums" -> Json.fromLong(value.totalAlbums),
      "totalPages"  -> Json.fromInt(value.totalPages)
    )

  def composition(value: AlbumComposition): Json =
    val title: String = value.album.title
    Json.obj(
      "album" -> Json.obj(
        "id"       -> Json.fromString(value.album.id.value.toString),
        "title"    -> Json.fromString(title),
        "synopsis" -> Json.fromString(value.album.synopsis)
      ),
      "start"    -> spread(value.start),
      "cover"    -> cover(value.cover),
      "chapters" -> Json.fromValues(value.chapters.map(chapter))
    )

  private def chapter(value: ChapterComposition): Json =
    val position: Int = value.chapter.position
    Json.obj(
      "chapter" -> Json.obj(
        "id"            -> Json.fromString(value.chapter.id.value.toString),
        "albumId"       -> Json.fromString(value.chapter.album.value.toString),
        "destinationId" -> Json.fromString(value.chapter.destination.value.toString),
        "description"   -> Json.fromString(value.chapter.description),
        "start"         -> Json.fromString(value.chapter.period.start.toString),
        "end"           -> Json.fromString(value.chapter.period.end.toString),
        "position"      -> Json.fromInt(position),
        "sourceFolder"  -> Json.fromString(value.chapter.sourceFolder.value.toString)
      ),
      "photos"  -> Json.fromValues(value.photos.map(photo)),
      "start"   -> spread(value.start),
      "spreads" -> Json.fromValues(value.spreads.map(spread))
    )

  private def photo(value: Photo): Json =
    val width: Int  = value.dimensions.width
    val height: Int = value.dimensions.height
    Json.obj(
      "id"        -> Json.fromString(value.id.value.toString),
      "chapterId" -> Json.fromString(value.chapter.value.toString),
      "source"    -> Json.fromString(value.source.value.toString),
      "thumbnail" -> option(value.maybeThumbnail)(location =>
        Json.fromString(location.value.toString)
      ),
      "capturedAt" -> option(value.maybeCapturedAt)(instant =>
        Json.fromString(instant.toString)
      ),
      "width"   -> Json.fromInt(width),
      "height"  -> Json.fromInt(height),
      "caption" -> option(value.maybeCaption)(Json.fromString)
    )

  private def spread(value: SpreadComposition): Json =
    Json.obj(
      "id"         -> Json.fromString(value.id.value.toString),
      "templateId" -> Json.fromString(value.templateId.value.toString),
      "slots"      -> Json.fromValues(value.slots.map(slot)),
      "placements" -> Json.fromValues(value.placements.map(placement))
    )

  private def slot(value: SpreadSlot): Json =
    value match
      case SpreadSlot.Photo(id, _, box, bleed) =>
        slotObject(
          id = id.value.toString,
          kind = "photo",
          box = box,
          extra = JsonObject(
            "bleed" -> Json.fromValues(bleed.toVector.map(edge =>
              Json.fromString(edge match
                case SpreadEdge.Top    => "top"
                case SpreadEdge.Bottom => "bottom"
                case SpreadEdge.Left   => "left"
                case SpreadEdge.Right  => "right")
            ))
          )
        )
      case SpreadSlot.Text(id, _, box) =>
        slotObject(id.value.toString, "text", box, JsonObject.empty)

  private def slotObject(id: String, kind: String, box: SlotBox, extra: JsonObject): Json =
    val x: Double      = box.x
    val y: Double      = box.y
    val width: Double  = box.width
    val height: Double = box.height
    Json.fromJsonObject(
      extra.add("id", Json.fromString(id))
        .add("kind", Json.fromString(kind))
        .add("x", Json.fromDoubleOrNull(x))
        .add("y", Json.fromDoubleOrNull(y))
        .add("width", Json.fromDoubleOrNull(width))
        .add("height", Json.fromDoubleOrNull(height))
    )

  private def placement(value: SpreadPlacement): Json =
    Json.obj(
      "id"            -> Json.fromString(value.id.value.toString),
      "slotId"        -> Json.fromString(value.slot.value.toString),
      "photoId"       -> Json.fromString(value.photo.value.toString),
      "transform"     -> transform(value.transform),
      "captionCorner" -> Json.fromString(captionCorner(value.captionCorner))
    )

  private def cover(value: CoverComposition): Json =
    Json.obj(
      "spineOwner" -> Json.fromString(spineOwner(value.spineOwner)),
      "front"      -> coverSide(value.front, value.frontPlacement),
      "back"       -> coverSide(value.back, value.backPlacement)
    )

  private def coverSide(slot: CoverSlot, maybePlacement: Option[CoverPlacement]): Json =
    Json.obj(
      "id"        -> Json.fromString(slot.id.value.toString),
      "placement" -> option(maybePlacement)(coverPlacement)
    )

  private def coverPlacement(value: CoverPlacement): Json =
    Json.obj(
      "id"        -> Json.fromString(value.id.value.toString),
      "slotId"    -> Json.fromString(value.slot.id.value.toString),
      "photoId"   -> Json.fromString(value.photo.value.toString),
      "transform" -> transform(value.transform)
    )

  private def transform(value: PhotoTransform): Json =
    val shiftX: Double = value.shift.x
    val shiftY: Double = value.shift.y
    val zoom: Double   = value.zoom
    Json.obj(
      "shift" -> Json.obj(
        "x" -> Json.fromDoubleOrNull(shiftX),
        "y" -> Json.fromDoubleOrNull(shiftY)
      ),
      "mirrored" -> Json.fromBoolean(value.mirrored),
      "zoom"     -> Json.fromDoubleOrNull(zoom)
    )

  private def option[A](value: Option[A])(encode: A => Json): Json =
    value.fold(Json.Null)(encode)

  private def spineOwner(value: SpineOwner): String =
    value match
      case SpineOwner.Front => "front"
      case SpineOwner.Back  => "back"

  private def captionCorner(value: CaptionCorner): String =
    value match
      case CaptionCorner.TopLeft     => "top_left"
      case CaptionCorner.TopRight    => "top_right"
      case CaptionCorner.BottomLeft  => "bottom_left"
      case CaptionCorner.BottomRight => "bottom_right"
