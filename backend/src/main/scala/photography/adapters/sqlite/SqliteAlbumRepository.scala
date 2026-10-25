package photography.adapters.sqlite

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.Positive
import org.typelevel.doobie.{ConnectionIO, Fragment, Meta, Read, Transactor}
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import org.sqlite.{SQLiteErrorCode, SQLiteException}
import photography.adapters.sqlite.SqliteCodecs.given
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.AlbumRepository
import photography.ports.AlbumRepository.*

import java.nio.file.{InvalidPathException, Path}
import java.time.{Instant, ZoneOffset}
import java.time.temporal.ChronoUnit
import java.util.UUID

object SqliteAlbumRepository:
  def make[F[_]: Async: Logger](transactor: Transactor[F]): AlbumRepository[F] =
    new LiveAlbumRepository(transactor)

  final private class LiveAlbumRepository[F[_]: Async: Logger](transactor: Transactor[F]) extends AlbumRepository[F]:
    override def list(offset: Int, limit: Int): F[ListResult] =
      val query =
        for
          total  <- sql"SELECT count(*) FROM album".query[Long].unique
          ids    <- sql"SELECT id FROM album ORDER BY created_at_ms DESC, id DESC LIMIT $limit OFFSET $offset".query[UUID].to[Vector]
          albums <- ids.traverse(loadRequired)
        yield ListResult.Listed(AlbumPageSlice(total, albums)): ListResult
      execute(query, ListResult.Failed(_))

    override def create(album: Album, cover: Cover): F[CreateResult] =
      val query =
        for
          start <- validateInitial(album, cover)
          _     <- insertAlbum(album)
          _     <- insertInitialSpread(album.id, start)
          _     <- insertCover(cover)
          _     <- loadRequired(album.id.value)
        yield CreateResult.Created: CreateResult
      execute(query, CreateResult.Failed(_))

    override def get(id: AlbumId): F[GetResult] =
      execute(load(id.value).map(_.fold[GetResult](GetResult.NotFound)(GetResult.Found(_))), GetResult.Failed(_))

    override def rename(id: AlbumId, title: AlbumTitle): F[RenameResult] =
      val update = sql"UPDATE album SET title = $title WHERE id = ${id.value}"
      val query  = load(id.value).flatMap:
        case None        => RenameResult.NotFound.pure[ConnectionIO]
        case Some(album) =>
          update.update.run.as(RenameResult.Renamed(album.copy(title = title)))
      execute(query, RenameResult.Failed(_))

    override def delete(id: AlbumId): F[DeleteResult] =
      val query = sql"DELETE FROM album WHERE id = ${id.value}".update.run.map: deleted =>
        if deleted === 0 then DeleteResult.NotFound else DeleteResult.Deleted
      execute(query, DeleteResult.Failed(_))

    private def load(id: UUID) =
      sql"SELECT year, title, synopsis, created_at_ms FROM album WHERE id = $id".query[(AlbumYear, AlbumTitle, String, Instant)].option.flatMap:
        case None                                     => Option.empty[Album].pure[ConnectionIO]
        case Some((year, title, synopsis, createdAt)) =>
          for
            chapters <- loadChapters(id)
            opening  <- loadAlbumOpening(id)
            _        <- validateCover(id)
          yield Album(AlbumId(id), year, title, synopsis, createdAt, opening, chapters).some

    private def loadChapters(albumId: UUID) =
      val select = sql"""SELECT c.id, d.id, d.name, c.description,
                               c.start_ms, c.end_ms, c.source_folder
                        FROM chapter c LEFT JOIN destination d ON d.id = c.destination_id
                        WHERE c.album_id = $albumId ORDER BY c.position"""
      validateChapters(albumId) >> select.query[ChapterRow].to[Vector].flatMap(_.traverse(decodeChapter))

    private def decodeChapter(row: ChapterRow) =
      val (id, destination, name, description, start, end, folder) = row

      val days         = ChronoUnit.DAYS.between(start, end)
      val calendarDays = Either.cond(
        start.compareTo(start.truncatedTo(ChronoUnit.DAYS)) === 0 && end.compareTo(end.truncatedTo(ChronoUnit.DAYS)) === 0,
        (),
        "Expected UTC calendar days"
      )
      val period =
        calendarDays.flatMap(_ => days.refineEither[Positive]).map(length => VisitPeriod(start.atOffset(ZoneOffset.UTC).toLocalDate, length))
      for
        visit       <- period.leftMap(new IllegalArgumentException(_)).liftTo[ConnectionIO]
        composition <- loadChapterSpreads(id)
        countries   <- loadCountries(destination)
      yield
        val (opening, photos) = composition

        Chapter(ChapterId(id), Destination(DestinationId(destination), name, countries), visit, description, folder, opening, photos)

    private def loadCountries(destination: UUID) =
      val select = sql"""SELECT k.id, k.code, k.name, k.flag FROM destination_country d
                         LEFT JOIN country k ON k.id = d.country_id WHERE d.destination_id = $destination ORDER BY d.position"""
      select.query[(UUID, CountryCode, CountryName, AbsoluteLocation)].to[List].flatMap: rows =>
        val countries = rows.map { case (id, code, name, flag) => Country(CountryId(id), code, name, flag) }
        NonEmptyList.fromList(countries).toRight(new IllegalArgumentException("Destination must have countries")).liftTo[ConnectionIO]

    private def loadRequired(id: UUID) =
      load(id).flatMap(_.toRight(new IllegalArgumentException(s"Missing required album $id")).liftTo[ConnectionIO])

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
      select.query[SpreadRow].to[List].flatMap(_.traverse(loadSpread))

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
        storedSlots   <- selectStored.query[SlotRow].to[List]
        ordered       <- orderSlots(expectedSlots, storedSlots).leftMap(new IllegalArgumentException(_)).liftTo[ConnectionIO]
        slots         <- ordered.traverse(loadSlot)
        placements    <- loadPlacements(id)
        spread        <- decodeSpread(row, slots.toVector, placements).leftMap(new IllegalArgumentException(_)).liftTo[ConnectionIO]
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
            .map(edges => SpreadSlot.Photo(PhotoSpreadSlotId(id), box, edges.toSet): SpreadSlot)
        case "text" => (SpreadSlot.Text(TextSpreadSlotId(id), box): SpreadSlot).pure[ConnectionIO]
        case other  => new IllegalArgumentException(s"Unknown slot kind $other").raiseError[ConnectionIO, SpreadSlot]

    private def decodeSpread(row: SpreadRow, slots: Vector[SpreadSlot], placements: Vector[SpreadPlacement]) =
      val (id, template, kind, maybeAlbum, maybeChapter, position, maybeTemplateKind) = row

      val photos = slots.collect { case photo: SpreadSlot.Photo => photo }
      val texts  = slots.collect { case text: SpreadSlot.Text => text }
      (kind, maybeTemplateKind, maybeAlbum, maybeChapter, texts) match
        case ("album_start", Some("album_start"), Some(_), None, Vector(text)) if position === 0 =>
          (Spread.AlbumStart(SpreadId(id), SpreadTemplateId(template), photos, text, placements): Spread).asRight[String]
        case ("chapter_start", Some("chapter_start"), None, Some(_), Vector(text)) if position === 0 =>
          (Spread.ChapterStart(SpreadId(id), SpreadTemplateId(template), photos, text, placements): Spread).asRight[String]
        case ("photos_only", Some("photos_only"), None, Some(_), Vector()) if position > 0 =>
          NonEmptyList.fromList(photos.toList).toRight("Photo spread must have photo slots")
            .map(nonEmpty => Spread.PhotosOnly(SpreadId(id), SpreadTemplateId(template), nonEmpty, placements): Spread)
        case _ => s"Invalid spread shape for $id".asLeft[Spread]

    private def loadPlacements(spreadId: UUID) =
      sql"""SELECT p.id, p.spread_photo_slot_id, p.photo_id, p.shift_x, p.shift_y, p.mirrored, p.zoom, p.caption_corner
            FROM placement p JOIN spread_photo_slot s ON s.id = p.spread_photo_slot_id
            WHERE s.spread_id = $spreadId ORDER BY p.id""".query[PlacementRow].to[Vector].map:
        _.map { case (id, slot, photo, x, y, mirrored, zoom, corner) =>
          SpreadPlacement(SpreadPlacementId(id), PhotoSpreadSlotId(slot), PhotoId(photo), PhotoTransform(Shift(x, y), mirrored, zoom), corner)
        }

    private def validateCover(id: UUID) =
      validateCoverSpine(id) >> validateCoverSlots(id)

    private def validateCoverSpine(id: UUID) =
      sql"SELECT spine_owner FROM cover WHERE album_id = $id".query[String].option
        .ensure(new IllegalArgumentException("Incomplete cover"))(_.exists(value => value === "front" || value === "back"))
        .void

    private def validateCoverSlots(id: UUID) =
      sql"SELECT side FROM cover_slot WHERE album_id = $id ORDER BY side".query[String].to[List]
        .ensure(new IllegalArgumentException("Incomplete cover"))(_ === List("back", "front"))
        .void

    private def validateInitial(album: Album, cover: Cover) =
      Either.cond(
        album.chapters.isEmpty && album.opening.placements.isEmpty && cover.album.value === album.id.value,
        album.opening,
        new IllegalArgumentException("Expected an initial album and its cover")
      ).liftTo[ConnectionIO]

    private def insertAlbum(album: Album) =
      sql"""INSERT INTO album (id, year, title, synopsis, created_at_ms)
            VALUES (${album.id.value}, ${album.year}, ${album.title}, ${album.synopsis}, ${album.createdAt})""".update.run.void

    private def insertInitialSpread(albumId: AlbumId, start: Spread.AlbumStart) =
      val insert = sql"""INSERT INTO spread (id, template_id, kind, album_id, position)
                        VALUES (${start.id.value}, ${start.template.value}, 'album_start', ${albumId.value}, 0)"""
      for
        _ <- insert.update.run
        _ <- start.photoSlots.traverse_(slot => insertSlot(start.id, slot))
        _ <- insertSlot(start.id, start.textSlot)
      yield ()

    private def insertSlot(spread: SpreadId, slot: SpreadSlot) =
      slot match
        case SpreadSlot.Photo(id, box, bleed) =>
          for
            _ <- sql"INSERT INTO spread_photo_slot (id, spread_id, x, y, width, height) VALUES (${id.value}, ${spread.value}, $box)".update.run
            _ <- bleed.toList.traverse_(edge => sql"INSERT INTO spread_photo_slot_bleed (slot_id, edge) VALUES (${id.value}, $edge)".update.run.void)
          yield ()
        case SpreadSlot.Text(id, box) =>
          sql"INSERT INTO spread_text_slot (id, spread_id, x, y, width, height) VALUES (${id.value}, ${spread.value}, $box)".update.run.void

    private def insertCover(cover: Cover) =
      val albumId = cover.album.value
      val spine   = cover.spineOwner match
        case SpineOwner.Front => "front"
        case SpineOwner.Back  => "back"
      for
        _ <- sql"INSERT INTO cover (album_id, spine_owner) VALUES ($albumId, $spine)".update.run
        _ <- sql"INSERT INTO cover_slot (id, album_id, side) VALUES (${cover.front.id.value}, $albumId, 'front')".update.run
        _ <- sql"INSERT INTO cover_slot (id, album_id, side) VALUES (${cover.back.id.value}, $albumId, 'back')".update.run
      yield ()

    private def execute[A](query: ConnectionIO[A], failed: Failure => A) =
      query.transact(transactor).attempt.flatMap:
        case Right(result) => result.pure[F]
        case Left(error)   =>
          val reason = error match
            case sqlite: SQLiteException
                if sqlite.getResultCode.code === SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE.code &&
                  sqlite.getMessage.contains("album.year") => Failure.YearAlreadyUsed
            case _ => Failure.DatabaseFailure
          Logger[F].error(error)("SQLite album operation failed").as(failed(reason))

    private given Meta[AlbumYear]        = Meta[Int].tiemap[AlbumYear](_.refineEither)(value => value: Int)
    private given Meta[AlbumTitle]       = Meta[String].tiemap[AlbumTitle](_.refineEither)(value => value: String)
    private given Meta[CountryCode]      = Meta[String].tiemap[CountryCode](_.refineEither)(value => value: String)
    private given Meta[CountryName]      = Meta[String].tiemap[CountryName](_.refineEither)(value => value: String)
    private given Meta[SourceFolder]     = Meta[String].tiemap[SourceFolder](_.refineEither)(value => value: String)
    private given Meta[AbsoluteLocation] = Meta[String].tiemap[AbsoluteLocation] { value =>
      Either.catchOnly[InvalidPathException](Path.of(value)).leftMap(_.getMessage).flatMap: path =>
        Either.cond(path.isAbsolute, AbsoluteLocation(path), "Expected absolute flag path")
    }(_.value.toString)
    private given Meta[Instant]        = Meta[Long].timap[Instant](Instant.ofEpochMilli)(_.toEpochMilli)
    private given Meta[InDesignPoints] = Meta[Double].tiemap[InDesignPoints](_.refineEither)(value => value: Double)
    private given Meta[Zoom]           = Meta[Double].tiemap[Zoom](_.refineEither)(value => value: Double)

    private given Read[SpreadRow] =
      Read[(UUID, UUID, String, Option[UUID], Option[UUID], Int, Option[String])]
        .map { case (id, template, kind, maybeAlbum, maybeChapter, position, maybeTemplateKind) =>
          (id, template, kind, maybeAlbum, maybeChapter, position, maybeTemplateKind)
        }

    private given Read[SlotRow] =
      Read[(UUID, String, SlotBox)].map { case (id, kind, box) => (id, kind, box) }

    private given Meta[SpreadEdge] = Meta[String].tiemap[SpreadEdge] {
      case "top"    => SpreadEdge.Top.asRight[String]
      case "bottom" => SpreadEdge.Bottom.asRight[String]
      case "left"   => SpreadEdge.Left.asRight[String]
      case "right"  => SpreadEdge.Right.asRight[String]
      case other    => s"Unknown bleed edge $other".asLeft[SpreadEdge]
    } {
      case SpreadEdge.Top    => "top"
      case SpreadEdge.Bottom => "bottom"
      case SpreadEdge.Left   => "left"
      case SpreadEdge.Right  => "right"
    }

    private given Meta[CaptionCorner] = Meta[String].tiemap[CaptionCorner] {
      case "top_left"     => CaptionCorner.TopLeft.asRight[String]
      case "top_right"    => CaptionCorner.TopRight.asRight[String]
      case "bottom_left"  => CaptionCorner.BottomLeft.asRight[String]
      case "bottom_right" => CaptionCorner.BottomRight.asRight[String]
      case other          => s"Unknown caption corner $other".asLeft[CaptionCorner]
    } {
      case CaptionCorner.TopLeft     => "top_left"
      case CaptionCorner.TopRight    => "top_right"
      case CaptionCorner.BottomLeft  => "bottom_left"
      case CaptionCorner.BottomRight => "bottom_right"
    }

  private type SpreadRow = (
      id: UUID,
      template: UUID,
      kind: String,
      maybeAlbum: Option[UUID],
      maybeChapter: Option[UUID],
      position: Int,
      maybeTemplateKind: Option[String]
  )
  private type ChapterRow =
    (UUID, UUID, DestinationName, String, Instant, Instant, SourceFolder)
  private type SlotRow      = (id: UUID, kind: String, box: SlotBox)
  private type PlacementRow = (UUID, UUID, UUID, InDesignPoints, InDesignPoints, Boolean, Zoom, CaptionCorner)
