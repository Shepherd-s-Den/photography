package photography.adapters.sqlite

import cats.data.NonEmptyList
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.Positive
import org.typelevel.doobie.implicits.*
import org.typelevel.doobie.{ConnectionIO, Fragment}
import photography.adapters.sqlite.SqliteCodecs.given
import photography.domain.album.*
import photography.domain.layout.*

import java.time.temporal.ChronoUnit
import java.time.{Instant, ZoneOffset}
import java.util.UUID

private[sqlite] object SqliteAlbumStore:
  def load(id: UUID): ConnectionIO[Option[Album]] =
    sql"SELECT year, title, synopsis, created_at_ms FROM album WHERE id = $id".query[(AlbumYear, AlbumTitle, String, Instant)].option.flatMap:
      case None                                     => Option.empty[Album].pure[ConnectionIO]
      case Some((year, title, synopsis, createdAt)) =>
        for
          chapters <- loadChapters(id)
          opening  <- loadAlbumOpening(id)
          _        <- validateCover(id)
        yield Album(AlbumId(id), year = year, title = title, synopsis = synopsis, createdAt = createdAt, opening, chapters = chapters).some

  def loadRequired(id: UUID): ConnectionIO[Album] =
    load(id).flatMap(_.toRight(new IllegalArgumentException(s"Missing required album $id")).liftTo[ConnectionIO])

  def insertSlot(spread: SpreadId, slot: SpreadSlot): ConnectionIO[Unit] =
    slot match
      case SpreadSlot.Photo(id, box, bleed) =>
        for
          _ <- sql"INSERT INTO spread_photo_slot (id, spread_id, x, y, width, height) VALUES (${id.value}, ${spread.value}, $box)".update.run
          _ <- bleed.toList.traverse_(edge => sql"INSERT INTO spread_photo_slot_bleed (slot_id, edge) VALUES (${id.value}, $edge)".update.run.void)
        yield ()
      case SpreadSlot.Text(id, box) =>
        sql"INSERT INTO spread_text_slot (id, spread_id, x, y, width, height) VALUES (${id.value}, ${spread.value}, $box)".update.run.void

  private def loadChapters(albumId: UUID) =
    val select = sql"""SELECT c.id, d.id, d.name, c.description,
                             c.start_ms, c.end_ms, c.source_folder
                      FROM chapter c LEFT JOIN destination d ON d.id = c.destination_id
                      WHERE c.album_id = $albumId ORDER BY c.position"""
    validateChapters(albumId) >> select.query[ChapterRow].to[Vector].flatMap(_.traverse(decodeChapter))

  private def decodeChapter(row: ChapterRow) =
    val (id, destination, name, description, start, end, folder) = row

    val days            = ChronoUnit.DAYS.between(start, end)
    val firstDay        = start.atOffset(ZoneOffset.UTC).toLocalDate
    val calendarAligned = start.compareTo(start.truncatedTo(ChronoUnit.DAYS)) === 0 && end.compareTo(end.truncatedTo(ChronoUnit.DAYS)) === 0
    val calendarDays    = Either.cond(calendarAligned, (), "Expected UTC calendar days")
    val period          = calendarDays.flatMap(_ => days.refineEither[Positive]).map(length => VisitPeriod(firstDay, days = length))
    for
      visit       <- period.leftMap(new IllegalArgumentException(_)).liftTo[ConnectionIO]
      composition <- loadChapterSpreads(id)
      countries   <- loadCountries(destination)
    yield
      val (opening, photos)  = composition
      val chapterDestination = Destination(DestinationId(destination), name = name, countries)
      Chapter(ChapterId(id), chapterDestination, visit, description = description, sourceFolder = folder, opening, spreads = photos)

  private def loadCountries(destination: UUID) =
    val select = sql"""SELECT k.id, k.code, k.name, k.flag FROM destination_country d
                       LEFT JOIN country k ON k.id = d.country_id WHERE d.destination_id = $destination ORDER BY d.position"""
    select.query[(UUID, CountryCode, CountryName, AbsoluteLocation)].to[List].flatMap: rows =>
      val countries = rows.map { case (id, code, name, flag) => Country(CountryId(id), code = code, name = name, flag) }
      NonEmptyList.fromList(countries).toRight(new IllegalArgumentException("Destination must have countries")).liftTo[ConnectionIO]

  private def loadAlbumOpening(albumId: UUID) =
    loadSpreads(sql"s.album_id = $albumId").flatMap:
      case (opening: Spread.AlbumStart) :: Nil => opening.pure[ConnectionIO]
      case _ => new IllegalArgumentException("Album must have exactly one opening").raiseError[ConnectionIO, Spread.AlbumStart]

  private def loadChapterSpreads(chapterId: UUID) =
    loadSpreads(sql"s.chapter_id = $chapterId").flatMap:
      case (opening: Spread.ChapterStart) :: rest =>
        val photos = rest.collect { case photo: Spread.PhotosOnly => photo }.toVector
        Either.cond(photos.size === rest.size, (opening, photos), new IllegalArgumentException("Invalid chapter composition")).liftTo[ConnectionIO]
      case _ =>
        val error = new IllegalArgumentException("Chapter must begin with its opening")
        error.raiseError[ConnectionIO, (Spread.ChapterStart, Vector[Spread.PhotosOnly])]

  private def loadSpreads(owner: Fragment) =
    val select =
      sql"""SELECT s.id, s.template_id, s.kind, s.album_id, s.chapter_id, s.position, t.kind
                      FROM spread s
                      LEFT JOIN spread_template t ON t.id = s.template_id
                      WHERE """ ++ owner ++ sql"ORDER BY s.position"
    select.query[(UUID, UUID, String, Option[UUID], Option[UUID], Int, Option[String])].to[List].flatMap(_.traverse(loadSpread))

  private def validateChapters(albumId: UUID) =
    sql"""SELECT count(*) FROM chapter c WHERE c.album_id = $albumId AND
          (c.position < 0 OR NOT EXISTS
           (SELECT 1 FROM spread s WHERE s.chapter_id = c.id AND s.kind = 'chapter_start'))""".query[Long].unique.flatMap: count =>
      Either.cond(count === 0L, (), new IllegalArgumentException("Invalid chapter composition")).liftTo[ConnectionIO]

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

  private def validateCover(id: UUID) =
    validateCoverSpine(id) >> validateCoverSlots(id)

  private def validateCoverSpine(id: UUID) =
    sql"SELECT spine_owner FROM cover WHERE album_id = $id".query[String].option
      .ensure(new IllegalArgumentException("Incomplete cover"))(_.exists(value => value === "front" || value === "back")).void

  private def validateCoverSlots(id: UUID) =
    sql"SELECT side FROM cover_slot WHERE album_id = $id ORDER BY side".query[String].to[List]
      .ensure(new IllegalArgumentException("Incomplete cover"))(_ === List("back", "front")).void

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
  private type ChapterRow   = (UUID, UUID, DestinationName, String, Instant, Instant, PhotoSource)
  private type SlotRow      = (id: UUID, kind: String, box: SlotBox)
  private type PlacementRow = (UUID, UUID, UUID, InDesignPoints, InDesignPoints, Boolean, Zoom, CaptionCorner)
