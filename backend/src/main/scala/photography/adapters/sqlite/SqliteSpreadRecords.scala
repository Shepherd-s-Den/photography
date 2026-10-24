package photography.adapters.sqlite

import cats.data.NonEmptyList
import cats.syntax.all.*
import io.github.iltotore.iron.*
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits.*
import photography.domain.album.{AlbumId, ChapterId, PhotoId}
import photography.domain.layout.*

private[sqlite] object SqliteSpreadRecords:
  def load(albumId: AlbumId): ConnectionIO[NonEmptyList[Spread]] =
    val id = albumId.value.toString
    for
      rows <- sql"""SELECT s.id, s.template_id, s.kind, s.album_id, s.chapter_id, s.position
                    FROM spread s LEFT JOIN chapter c ON c.id = s.chapter_id
                    WHERE s.album_id = $id OR c.album_id = $id ORDER BY COALESCE(c.position, -1), s.position""".query[SpreadRow].to[List]
      invalidChapters <- sql"""SELECT count(*) FROM chapter c WHERE c.album_id = $id AND
                     (c.position < 0 OR c.position > 19 OR NOT EXISTS
                       (SELECT 1 FROM spread s WHERE s.chapter_id = c.id AND s.kind = 'chapter_start'))""".query[Long].unique
      _       <- SqliteDecoding.requireData(Either.cond(invalidChapters === 0L, (), "Invalid chapter composition"))
      spreads <- rows.traverse(_.load)
      ordered <- SqliteDecoding.requireData(validateOrder(spreads))
    yield ordered

  def insertInitial(albumId: AlbumId, start: Spread.AlbumStart): ConnectionIO[Unit] =
    val id       = start.id.value.toString
    val owner    = albumId.value.toString
    val template = start.template.value.toString
    for
      _ <- sql"INSERT INTO spread (id, template_id, kind, album_id, position) VALUES ($id, $template, 'album_start', $owner, 0)".update.run
      _ <- start.slots.traverse_(insertSlot)
    yield ()

  private def insertSlot(slot: SpreadSlot) =
    slot match
      case SpreadSlot.Photo(slotId, spreadId, box, bleed) =>
        val id     = slotId.value.toString
        val spread = spreadId.value.toString
        for
          _ <- sql"""INSERT INTO spread_photo_slot (id, spread_id, x, y, width, height)
                VALUES ($id, $spread, ${box.x: Double}, ${box.y: Double}, ${box.width: Double}, ${box.height: Double})""".update.run
          _ <- bleed.toList.traverse_ { edge =>
            val encoded = edge match
              case SpreadEdge.Top    => "top"
              case SpreadEdge.Bottom => "bottom"
              case SpreadEdge.Left   => "left"
              case SpreadEdge.Right  => "right"
            sql"INSERT INTO spread_photo_slot_bleed (slot_id, edge) VALUES ($id, $encoded)".update.run.void
          }
        yield ()
      case SpreadSlot.Text(slotId, spreadId, box) =>
        val id     = slotId.value.toString
        val spread = spreadId.value.toString
        sql"""INSERT INTO spread_text_slot (id, spread_id, x, y, width, height)
              VALUES ($id, $spread, ${box.x: Double}, ${box.y: Double}, ${box.width: Double}, ${box.height: Double})""".update.run.void

  private def validateOrder(spreads: List[Spread]) =
    spreads match
      case (start: Spread.AlbumStart) :: rest if !rest.exists { case _: Spread.AlbumStart => true; case _ => false } =>
        NonEmptyList(start: Spread, rest).asRight[String]
      case _ => "Album must begin with exactly one AlbumStart".asLeft[NonEmptyList[Spread]]

  final private case class SpreadRow(
      id: String,
      templateId: String,
      kind: String,
      maybeAlbumId: Option[String],
      maybeChapterId: Option[String],
      position: Int
  ):
    def load: ConnectionIO[Spread] =
      for
        template <- SqliteTemplateRecords.load(templateId)
        rows     <- sql"""SELECT id, 'photo', x, y, width, height FROM spread_photo_slot WHERE spread_id = $id
                      UNION ALL SELECT id, 'text', x, y, width, height FROM spread_text_slot WHERE spread_id = $id""".query[SlotRow].to[List]
        slots      <- SqliteDecoding.requireData(orderedSlots(template, rows)).flatMap(_.traverse(loadBleed))
        placements <- loadPlacements(id)
        spread     <- SqliteDecoding.requireData(decode(template, slots, placements))
      yield spread

    private def orderedSlots(template: SpreadTemplate, rows: List[SlotRow]) =
      for
        _        <- Either.cond(rows.size === template.slots.length, (), s"Wrong slot count for spread $id")
        _        <- Either.cond(rows.map(_.id).distinct.size === rows.size, (), s"Duplicate slot identities for spread $id")
        spreadId <- SqliteDecoding.uuid(id).map(SpreadId(_))
        slots    <- template.slots.toList.traverse: expected =>
          rows.find(_.matches(expected)).toRight(s"Missing required slot for spread $id").flatMap(_.decode(spreadId))
      yield slots.toVector

    private def decode(template: SpreadTemplate, slots: Vector[SpreadSlot], placements: Vector[SpreadPlacement]) =
      for
        spreadId <- SqliteDecoding.uuid(id).map(SpreadId(_))
        spread   <- (kind, template.kind, maybeAlbumId, maybeChapterId) match
          case ("album_start", SpreadTemplateKind.AlbumStart, Some(owner), None) if position === 0 =>
            SqliteDecoding.uuid(owner).map(ownerId => Spread.AlbumStart(spreadId, AlbumId(ownerId), template.id, slots, placements): Spread)
          case ("chapter_start", SpreadTemplateKind.ChapterStart, None, Some(owner)) if position === 0 =>
            SqliteDecoding.uuid(owner).map(ownerId => Spread.ChapterStart(spreadId, ChapterId(ownerId), template.id, slots, placements): Spread)
          case ("photos_only", SpreadTemplateKind.PhotosOnly, None, Some(owner)) =>
            for
              chapterId     <- SqliteDecoding.uuid(owner)
              validPosition <- position.refineEither: Either[String, PositionInChapter]
            yield Spread.PhotosOnly(spreadId, ChapterId(chapterId), validPosition, template.id, slots, placements): Spread
          case _ => s"Invalid spread shape for $id".asLeft[Spread]
      yield spread

  final private case class SlotRow(id: String, kind: String, x: Double, y: Double, width: Double, height: Double):
    def matches(slot: SpreadTemplateSlot): Boolean =
      val (expectedKind, box) = slot match
        case SpreadTemplateSlot.Photo(box) => ("photo", box)
        case SpreadTemplateSlot.Text(box)  => ("text", box)
      kind === expectedKind && (x, y, width, height) === ((box.x: Double), (box.y: Double), (box.width: Double), (box.height: Double))

    def decode(spread: SpreadId): Either[String, SpreadSlot] =
      for
        slotId <- SqliteDecoding.uuid(id)
        box    <- SqliteDecoding.box(x, y, width, height)
      yield
        if kind === "photo" then SpreadSlot.Photo(PhotoSpreadSlotId(slotId), spread, box, Set.empty[SpreadEdge])
        else SpreadSlot.Text(TextSpreadSlotId(slotId), spread, box)

  private def loadPlacements(spreadId: String) =
    sql"""SELECT p.id, p.spread_photo_slot_id, p.photo_id, p.shift_x, p.shift_y, p.mirrored, p.zoom, p.caption_corner
          FROM placement p JOIN spread_photo_slot s ON s.id = p.spread_photo_slot_id
          WHERE s.spread_id = $spreadId ORDER BY p.id""".query[PlacementRow].to[Vector].flatMap: rows =>
      SqliteDecoding.requireData(rows.traverse(_.decode))

  private def loadBleed(slot: SpreadSlot) =
    slot match
      case SpreadSlot.Photo(id, spread, box, _) =>
        sql"SELECT edge FROM spread_photo_slot_bleed WHERE slot_id = ${id.value.toString}".query[String].to[List].flatMap: rows =>
          val decoded = rows.traverse:
            case "top"    => SpreadEdge.Top.asRight[String]
            case "bottom" => SpreadEdge.Bottom.asRight[String]
            case "left"   => SpreadEdge.Left.asRight[String]
            case "right"  => SpreadEdge.Right.asRight[String]
            case other    => s"Unknown bleed edge $other".asLeft[SpreadEdge]
          SqliteDecoding.requireData(decoded).map(edges => SpreadSlot.Photo(id, spread, box, edges.toSet): SpreadSlot)
      case text: SpreadSlot.Text => (text: SpreadSlot).pure[ConnectionIO]

  final private case class PlacementRow(id: String, slot: String, photo: String, x: Double, y: Double, mirrored: Int, zoom: Double, corner: String):
    def decode: Either[String, SpreadPlacement] =
      for
        placementId <- SqliteDecoding.uuid(id)
        slotId      <- SqliteDecoding.uuid(slot)
        photoId     <- SqliteDecoding.uuid(photo)
        shiftX      <- x.refineEither: Either[String, InDesignPoints]
        shiftY      <- y.refineEither: Either[String, InDesignPoints]
        validZoom   <- zoom.refineEither: Either[String, Zoom]
        caption     <- corner match
          case "top_left"     => CaptionCorner.TopLeft.asRight[String]
          case "top_right"    => CaptionCorner.TopRight.asRight[String]
          case "bottom_left"  => CaptionCorner.BottomLeft.asRight[String]
          case "bottom_right" => CaptionCorner.BottomRight.asRight[String]
          case other          => s"Unknown caption corner $other".asLeft[CaptionCorner]
      yield SpreadPlacement(
        SpreadPlacementId(placementId),
        PhotoSpreadSlotId(slotId),
        PhotoId(photoId),
        PhotoTransform(Shift(shiftX, shiftY), mirrored === 1, validZoom),
        caption
      )
