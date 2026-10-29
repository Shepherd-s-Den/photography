package photography.adapters.sqlite.spread

import cats.data.NonEmptyList
import cats.syntax.all.*
import org.typelevel.doobie.implicits.*
import org.typelevel.doobie.{ConnectionIO, Fragment}
import photography.adapters.sqlite.SqliteCodecs.given
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.spread.SpreadRepository
import photography.ports.spread.SpreadRepository.Located
import java.util.UUID

object SqliteSpreadRepository:
  def make: SpreadRepository[ConnectionIO] = new LiveSqliteSpreadRepository

  final private class LiveSqliteSpreadRepository extends SpreadRepository[ConnectionIO]:
    override def exists(album: AlbumId): ConnectionIO[Boolean] =
      sql"SELECT EXISTS(SELECT 1 FROM album WHERE id = ${album.value})".query[Boolean].unique

    override def countInterior(album: AlbumId): ConnectionIO[Int] =
      sql"""SELECT count(*) FROM spread s LEFT JOIN chapter c ON c.id = s.chapter_id
            WHERE s.album_id = ${album.value} OR c.album_id = ${album.value}""".query[Int].unique

    override def find(album: AlbumId, spread: SpreadId): ConnectionIO[Option[Located]] =
      val scope =
        sql"s.id = ${spread.value} AND (s.album_id = ${album.value} OR s.chapter_id IN (SELECT id FROM chapter WHERE album_id = ${album.value}))"
      loadRows(scope).flatMap(_.traverse(locateSpread)).map(_.headOption)

    override def findOrder(album: AlbumId, chapter: ChapterId): ConnectionIO[Option[NonEmptyList[SpreadId]]] =
      val select = sql"""SELECT s.id, s.kind, s.position FROM chapter c LEFT JOIN spread s ON c.id = s.chapter_id
                        WHERE c.album_id = ${album.value} AND c.id = ${chapter.value} ORDER BY s.position"""
      select.query[(Option[UUID], Option[String], Option[Int])].to[List].flatMap(rows => decodeOrder(rows = rows).liftTo[ConnectionIO])

    override def readOpening(album: AlbumId): ConnectionIO[Spread.AlbumStart] =
      loadSpreads(sql"s.album_id = ${album.value}").flatMap:
        case (opening: Spread.AlbumStart) :: Nil => opening.pure[ConnectionIO]
        case _ => new IllegalArgumentException("Album must have exactly one opening").raiseError[ConnectionIO, Spread.AlbumStart]

    override def readChapter(chapter: ChapterId): ConnectionIO[(Spread.ChapterStart, Vector[Spread.PhotosOnly])] =
      loadSpreads(sql"s.chapter_id = ${chapter.value}").flatMap:
        case (opening: Spread.ChapterStart) :: rest =>
          val photos = rest.collect { case photo: Spread.PhotosOnly => photo }.toVector
          Either.cond(photos.size === rest.size, (opening, photos), new IllegalArgumentException("Invalid chapter composition")).liftTo[ConnectionIO]
        case _ =>
          new IllegalArgumentException("Chapter must begin with its opening").raiseError[
            ConnectionIO,
            (Spread.ChapterStart, Vector[Spread.PhotosOnly])
          ]

    override def insertOpening(album: AlbumId, spread: Spread.AlbumStart): ConnectionIO[Unit] =
      val insert = sql"""INSERT INTO spread (id, template_id, kind, album_id, position)
                        VALUES (${spread.id.value}, ${spread.template.value}, 'album_start', ${album.value}, 0)"""
      insert.update.run >> spread.photoSlots.traverse_(insertSlot(spread.id, _)) >> insertSlot(spread.id, spread.textSlot)

    override def insert(chapter: ChapterId, spread: Spread.PhotosOnly): ConnectionIO[Unit] =
      val insert = sql"""INSERT INTO spread (id, template_id, kind, chapter_id, position)
                        VALUES (${spread.id.value}, ${spread.template.value}, 'photos_only', ${chapter.value},
                        (SELECT coalesce(max(position), 0) + 1 FROM spread WHERE chapter_id = ${chapter.value}))"""
      insert.update.run.ensure(new IllegalStateException("Expected one persisted row"))(_ === 1) >>
        spread.photoSlots.traverse_(insertSlot(spread.id, _))

    override def replace(spread: Spread): ConnectionIO[Unit] =
      val id               = spread.id.value
      val update           = sql"UPDATE spread SET template_id = ${spread.template.value} WHERE id = $id"
      val deletePlacements = sql"DELETE FROM placement WHERE spread_photo_slot_id IN (SELECT id FROM spread_photo_slot WHERE spread_id = $id)"
      for
        _ <- update.update.run.ensure(new IllegalStateException("Expected one persisted row"))(_ === 1)
        _ <- deletePlacements.update.run
        _ <- sql"DELETE FROM spread_photo_slot WHERE spread_id = $id".update.run
        _ <- sql"DELETE FROM spread_text_slot WHERE spread_id = $id".update.run
        _ <- collectSlots(spread).traverse_(insertSlot(spread.id, _))
        _ <- spread.placements.traverse_(insertPlacement)
      yield ()

    override def reorder(chapter: ChapterId, order: NonEmptyList[SpreadId]): ConnectionIO[Unit] =
      val supplied = order.toList.map(_.value)
      val shift    = sql"UPDATE spread SET position = position + 1000000 WHERE chapter_id = ${chapter.value} AND kind = 'photos_only'"
      for
        stored <- sql"SELECT id FROM spread WHERE chapter_id = ${chapter.value}".query[UUID].to[List]
        _      <- validateOrder(stored = stored, supplied = supplied).liftTo[ConnectionIO]
        _      <- shift.update.run
        _      <- supplied.zipWithIndex.traverse_ { case (id, position) => assignPosition(id, position = position) }
      yield ()

    override def remove(spread: SpreadId): ConnectionIO[Unit] =
      sql"DELETE FROM spread WHERE id = ${spread.value}".update.run.ensure(new IllegalStateException("Expected one removed spread"))(_ === 1).void

    private def assignPosition(id: UUID, position: Int) =
      sql"UPDATE spread SET position = $position WHERE id = $id".update.run.ensure(new IllegalStateException("Expected one persisted row"))(_ ===
        1).void

    private def validateOrder(stored: List[UUID], supplied: List[UUID]) =
      val containsEverySpread = stored.size === supplied.size && stored.toSet === supplied.toSet
      Either.cond(containsEverySpread, (), new IllegalArgumentException("Order must contain every spread of the chapter exactly once"))

    private def decodeOrder(rows: List[(Option[UUID], Option[String], Option[Int])]) = rows match
      case Nil                                                       => none[NonEmptyList[SpreadId]].asRight[IllegalArgumentException]
      case (Some(opening), Some("chapter_start"), Some(0)) :: photos =>
        photos.traverse:
          case (Some(id), Some("photos_only"), Some(position)) if position > 0 => SpreadId(id).asRight[IllegalArgumentException]
          case _ => new IllegalArgumentException("Invalid chapter order").asLeft[SpreadId]
        .map(ids => NonEmptyList(SpreadId(opening), tail = ids).some)
      case _ => new IllegalArgumentException("Chapter must begin with its opening").asLeft[Option[NonEmptyList[SpreadId]]]

    private def collectSlots(spread: Spread) = spread match
      case value: Spread.AlbumStart   => value.photoSlots.map(slot => slot: SpreadSlot) :+ value.textSlot
      case value: Spread.ChapterStart => value.photoSlots.map(slot => slot: SpreadSlot) :+ value.textSlot
      case value: Spread.PhotosOnly   => value.photoSlots.toList.toVector.map(slot => slot: SpreadSlot)

    private def insertPlacement(placement: SpreadPlacement) =
      val transform = placement.transform
      sql"""INSERT INTO placement (id, photo_id, kind, spread_photo_slot_id, cover_slot_id, shift_x, shift_y, mirrored, zoom, caption_corner)
            VALUES (${placement.id.value}, ${placement.photo.value}, 'spread', ${placement.slot.value}, NULL,
                    ${transform.shift.x}, ${transform.shift.y}, ${transform.mirrored}, ${transform.zoom}, ${placement.captionCorner})""".update.run
        .ensure(new IllegalStateException("Expected one persisted row"))(_ === 1).void

    private def insertSlot(spread: SpreadId, slot: SpreadSlot) =
      slot match
        case SpreadSlot.Photo(id, box, bleed) =>
          for
            _ <- sql"INSERT INTO spread_photo_slot (id, spread_id, x, y, width, height) VALUES (${id.value}, ${spread.value}, $box)".update.run
            _ <- bleed.toList.traverse_(edge => sql"INSERT INTO spread_photo_slot_bleed (slot_id, edge) VALUES (${id.value}, $edge)".update.run.void)
          yield ()
        case SpreadSlot.Text(id, box) =>
          sql"INSERT INTO spread_text_slot (id, spread_id, x, y, width, height) VALUES (${id.value}, ${spread.value}, $box)".update.run.void

    private def loadSpreads(owner: Fragment) = loadRows(owner).flatMap(_.traverse(loadSpread))

    private def locateSpread(row: SpreadRow) =
      loadSpread(row).flatMap:
        case value: Spread.AlbumStart   => (Located.AlbumOpening(value): Located).pure[ConnectionIO]
        case value: Spread.ChapterStart =>
          row.maybeChapter.toRight(new IllegalArgumentException("Missing chapter")).liftTo[ConnectionIO]
            .map(id => Located.ChapterOpening(ChapterId(id), value): Located)
        case value: Spread.PhotosOnly =>
          row.maybeChapter.toRight(new IllegalArgumentException("Missing chapter")).liftTo[ConnectionIO]
            .map(id => Located.Photo(ChapterId(id), value): Located)

    private def loadRows(owner: Fragment) =
      val select =
        sql"""SELECT s.id, s.template_id, s.kind, s.album_id, s.chapter_id, s.position, t.kind
                      FROM spread s
                      LEFT JOIN spread_template t ON t.id = s.template_id
                      WHERE """ ++ owner ++ sql"ORDER BY s.position"
      select.query[(UUID, UUID, String, Option[UUID], Option[UUID], Int, Option[String])].to[List]

    private def loadSpread(row: SpreadRow) =
      val id             = row.id
      val template       = row.template
      val selectExpected = sql"SELECT kind, x, y, width, height FROM spread_template_slot WHERE template_id = $template ORDER BY id"
      val selectStored   = sql"""SELECT id, 'photo', x, y, width, height FROM spread_photo_slot WHERE spread_id = $id
                            UNION ALL SELECT id, 'text', x, y, width, height FROM spread_text_slot WHERE spread_id = $id"""
      for
        expectedSlots <- selectExpected.query[(String, SlotBox)].to[List]
        storedSlots   <- selectStored.query[(UUID, String, SlotBox)].to[List]
        ordered       <- orderSlots(expected = expectedSlots, stored = storedSlots).leftMap(new IllegalArgumentException(_)).liftTo[ConnectionIO]
        slots         <- ordered.traverse(loadSlot)
        placements    <- loadPlacements(id)
        spread        <- decodeSpread(row, slots = slots.toVector, placements = placements).leftMap(IllegalArgumentException(_)).liftTo[ConnectionIO]
      yield spread

    private def orderSlots(expected: List[(String, SlotBox)], stored: List[SlotRow]) =
      val matchedSlots = expected.traverse { case (kind, box) =>
        stored.find { slot =>
          val storedBox = slot.box
          kind === slot.kind && (box.x: Double, box.y: Double, box.width: Double, box.height: Double) ===
            (storedBox.x: Double, storedBox.y: Double, storedBox.width: Double, storedBox.height: Double)
        }.toRight("Missing required spread slot")
      }
      for
        _     <- Either.cond(expected.nonEmpty && expected.size === stored.size, (), "Wrong spread slot count")
        _     <- Either.cond(stored.map(_.id).distinct.size === stored.size, (), "Duplicate spread slot identities")
        slots <- matchedSlots
      yield slots

    private def loadSlot(row: SlotRow) =
      val id  = row.id
      val box = row.box
      row.kind match
        case "photo" =>
          sql"SELECT edge FROM spread_photo_slot_bleed WHERE slot_id = $id".query[SpreadEdge].to[List]
            .map(edges => SpreadSlot.Photo(PhotoSpreadSlotId(id), box, bleed = edges.toSet): SpreadSlot)
        case "text" => (SpreadSlot.Text(TextSpreadSlotId(id), box): SpreadSlot).pure[ConnectionIO]
        case other  => new IllegalArgumentException(s"Unknown slot kind $other").raiseError[ConnectionIO, SpreadSlot]

    private def decodeSpread(row: SpreadRow, slots: Vector[SpreadSlot], placements: Vector[SpreadPlacement]) =
      val (id, template, kind, maybeAlbum, maybeChapter, position, maybeTemplateKind) = row

      val photos     = slots.collect { case photo: SpreadSlot.Photo => photo }
      val texts      = slots.collect { case text: SpreadSlot.Text => text }
      val spreadId   = SpreadId(id)
      val templateId = SpreadTemplateId(template)
      (kind, maybeTemplateKind, maybeAlbum, maybeChapter, texts) match
        case ("album_start", Some("album_start"), Some(_), None, Vector(text)) if position === 0 =>
          (Spread.AlbumStart(spreadId, templateId, photoSlots = photos, textSlot = text, placements = placements): Spread).asRight[String]
        case ("chapter_start", Some("chapter_start"), None, Some(_), Vector(text)) if position === 0 =>
          (Spread.ChapterStart(spreadId, templateId, photoSlots = photos, textSlot = text, placements = placements): Spread).asRight[String]
        case ("photos_only", Some("photos_only"), None, Some(_), Vector()) if position > 0 =>
          NonEmptyList.fromList(photos.toList).toRight("Photo spread must have photo slots")
            .map(nonEmpty => Spread.PhotosOnly(spreadId, templateId, photoSlots = nonEmpty, placements = placements): Spread)
        case _ => s"Invalid spread shape for $id".asLeft[Spread]

    private def loadPlacements(spreadId: UUID) =
      sql"""SELECT p.id, p.spread_photo_slot_id, p.photo_id, p.shift_x, p.shift_y, p.mirrored, p.zoom, p.caption_corner
          FROM placement p JOIN spread_photo_slot s ON s.id = p.spread_photo_slot_id
          WHERE s.spread_id = $spreadId ORDER BY p.id""".query[PlacementRow].to[Vector].map:
        _.map { case (id, slot, photo, x, y, mirrored, zoom, corner) =>
          val transform = PhotoTransform(Shift(x = x, y = y), mirrored = mirrored, zoom = zoom)
          SpreadPlacement(SpreadPlacementId(id), PhotoSpreadSlotId(slot), PhotoId(photo), transform, corner)
        }

  // doobie RC13 reads ordinary tuples; their decoded values widen to these named row views at the consuming calls.
  private type SpreadRow = (
      id: UUID,
      template: UUID,
      kind: String,
      maybeAlbum: Option[UUID],
      maybeChapter: Option[UUID],
      position: Int,
      maybeTemplateKind: Option[String]
  )
  private type SlotRow      = (id: UUID, kind: String, box: SlotBox)
  private type PlacementRow = (UUID, UUID, UUID, InDesignPoints, InDesignPoints, Boolean, Zoom, CaptionCorner)
