package photography.adapters.sqlite

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all.*
import io.github.iltotore.iron.*
import org.typelevel.doobie.{ConnectionIO, Meta, Read, Transactor}
import org.typelevel.doobie.implicits.*
import org.typelevel.doobie.util.invariant.InvariantViolation
import org.typelevel.log4cats.Logger
import photography.adapters.sqlite.SqliteCodecs.given
import photography.domain.album.{Album, AlbumId, AlbumName, ChapterId, PhotoId}
import photography.domain.layout.*
import photography.ports.AlbumRepository
import photography.ports.AlbumRepository.*

import java.time.Instant
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

    override def rename(id: AlbumId, title: AlbumName): F[RenameResult] =
      val query = load(id.value).flatMap:
        case None        => (RenameResult.NotFound: RenameResult).pure[ConnectionIO]
        case Some(album) =>
          sql"UPDATE album SET title = $title WHERE id = ${id.value}".update.run
            .as(RenameResult.Renamed(album.copy(title = title)): RenameResult)
      execute(query, RenameResult.Failed(_))

    override def delete(id: AlbumId): F[DeleteResult] =
      val query = sql"DELETE FROM album WHERE id = ${id.value}".update.run.map: deleted =>
        if deleted === 0 then DeleteResult.NotFound else DeleteResult.Deleted
      execute(query, DeleteResult.Failed(_))

    private def load(id: UUID) =
      sql"SELECT title, synopsis, created_at_ms FROM album WHERE id = $id".query[(AlbumName, String, Instant)].option.flatMap:
        case None                               => Option.empty[Album].pure[ConnectionIO]
        case Some((title, synopsis, createdAt)) =>
          for
            spreads <- loadSpreads(id)
            _       <- validateCover(id)
          yield Album(AlbumId(id), title, synopsis, createdAt, spreads).some

    private def loadRequired(id: UUID) =
      load(id).flatMap(_.toRight(new IllegalArgumentException(s"Missing required album $id")).liftTo[ConnectionIO])

    private def loadSpreads(albumId: UUID) =
      val select = sql"""SELECT s.id, s.template_id, s.kind, s.album_id, s.chapter_id, s.position, t.kind
                        FROM spread s LEFT JOIN chapter c ON c.id = s.chapter_id
                        LEFT JOIN spread_template t ON t.id = s.template_id
                        WHERE s.album_id = $albumId OR c.album_id = $albumId
                        ORDER BY COALESCE(c.position, -1), s.position"""
      for
        rows    <- select.query[SpreadRow].to[List]
        _       <- validateChapters(albumId)
        spreads <- rows.traverse(loadSpread)
        ordered <- validateSpreadOrder(spreads).leftMap(new IllegalArgumentException(_)).liftTo[ConnectionIO]
      yield ordered

    private def validateChapters(albumId: UUID) =
      sql"""SELECT count(*) FROM chapter c WHERE c.album_id = $albumId AND
            (c.position < 0 OR c.position > 19 OR NOT EXISTS
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
        slots         <- ordered.traverse(slot => loadSlot(SpreadId(id), slot))
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

    private def loadSlot(spreadId: SpreadId, row: SlotRow) =
      val id  = row.id
      val box = row.box
      row.kind match
        case "photo" =>
          sql"SELECT edge FROM spread_photo_slot_bleed WHERE slot_id = $id".query[SpreadEdge].to[List]
            .map(edges => SpreadSlot.Photo(PhotoSpreadSlotId(id), spreadId, box, edges.toSet): SpreadSlot)
        case "text" => (SpreadSlot.Text(TextSpreadSlotId(id), spreadId, box): SpreadSlot).pure[ConnectionIO]
        case other  => new IllegalArgumentException(s"Unknown slot kind $other").raiseError[ConnectionIO, SpreadSlot]

    private def decodeSpread(row: SpreadRow, slots: Vector[SpreadSlot], placements: Vector[SpreadPlacement]) =
      val (id, template, kind, maybeAlbum, maybeChapter, position, maybeTemplateKind) = row
      (kind, maybeTemplateKind, maybeAlbum, maybeChapter) match
        case (SpreadTemplateKind.AlbumStart, Some(SpreadTemplateKind.AlbumStart), Some(owner), None) if position === 0 =>
          (Spread.AlbumStart(SpreadId(id), AlbumId(owner), SpreadTemplateId(template), slots, placements): Spread).asRight[String]
        case (SpreadTemplateKind.ChapterStart, Some(SpreadTemplateKind.ChapterStart), None, Some(owner)) if position === 0 =>
          (Spread.ChapterStart(SpreadId(id), ChapterId(owner), SpreadTemplateId(template), slots, placements): Spread).asRight[String]
        case (SpreadTemplateKind.PhotosOnly, Some(SpreadTemplateKind.PhotosOnly), None, Some(owner)) =>
          (position.refineEither: Either[String, PositionInChapter])
            .map(validPosition =>
              Spread.PhotosOnly(SpreadId(id), ChapterId(owner), validPosition, SpreadTemplateId(template), slots, placements): Spread
            )
        case _ => s"Invalid spread shape for $id".asLeft[Spread]

    private def validateSpreadOrder(spreads: List[Spread]) =
      spreads match
        case (start: Spread.AlbumStart) :: rest if !rest.exists { case _: Spread.AlbumStart => true; case _ => false } =>
          NonEmptyList(start: Spread, rest).asRight[String]
        case _ => "Album must begin with exactly one AlbumStart".asLeft[NonEmptyList[Spread]]

    private def loadPlacements(spreadId: UUID) =
      sql"""SELECT p.id, p.spread_photo_slot_id, p.photo_id, p.shift_x, p.shift_y, p.mirrored, p.zoom, p.caption_corner
            FROM placement p JOIN spread_photo_slot s ON s.id = p.spread_photo_slot_id
            WHERE s.spread_id = $spreadId ORDER BY p.id""".query[PlacementRow].to[Vector].map:
        _.map { case (id, slot, photo, x, y, mirrored, zoom, corner) =>
          SpreadPlacement(SpreadPlacementId(id), PhotoSpreadSlotId(slot), PhotoId(photo), PhotoTransform(Shift(x, y), mirrored, zoom), corner)
        }

    private def validateCover(id: UUID) =
      for
        maybeSpine <- sql"SELECT spine_owner FROM cover WHERE album_id = $id".query[String].option
        slots      <- sql"SELECT side FROM cover_slot WHERE album_id = $id ORDER BY side".query[String].to[List]
        validSpine = maybeSpine.exists(value => value === "front" || value === "back")
        complete   = validSpine && slots === List("back", "front")
        _ <- Either.cond(complete, (), new IllegalArgumentException("Incomplete cover")).liftTo[ConnectionIO]
      yield ()

    private def validateInitial(album: Album, cover: Cover) =
      val maybeStart = album.spreads.toList match
        case (start: Spread.AlbumStart) :: Nil if start.album.value === album.id.value && start.placements.isEmpty => start.some
        case _                                                                                                     => None
      val coverOwnership =
        Either.cond(cover.album.value === album.id.value, (), new IllegalArgumentException("Cover belongs to a different album"))
      for
        start <- maybeStart.toRight(new IllegalArgumentException("New album must contain one empty AlbumStart")).liftTo[ConnectionIO]
        _     <- coverOwnership.liftTo[ConnectionIO]
      yield start

    private def insertAlbum(album: Album) =
      sql"""INSERT INTO album (id, title, synopsis, created_at_ms)
            VALUES (${album.id.value}, ${album.title}, ${album.synopsis}, ${album.createdAt})""".update.run.void

    private def insertInitialSpread(albumId: AlbumId, start: Spread.AlbumStart) =
      val insert = sql"""INSERT INTO spread (id, template_id, kind, album_id, position)
                        VALUES (${start.id.value}, ${start.template.value}, ${SpreadTemplateKind.AlbumStart}, ${albumId.value}, 0)"""
      for
        _ <- insert.update.run
        _ <- start.slots.traverse_(insertSlot)
      yield ()

    private def insertSlot(slot: SpreadSlot) =
      slot match
        case SpreadSlot.Photo(id, spread, box, bleed) =>
          for
            _ <- sql"INSERT INTO spread_photo_slot (id, spread_id, x, y, width, height) VALUES (${id.value}, ${spread.value}, $box)".update.run
            _ <- bleed.toList.traverse_(edge => sql"INSERT INTO spread_photo_slot_bleed (slot_id, edge) VALUES (${id.value}, $edge)".update.run.void)
          yield ()
        case SpreadSlot.Text(id, spread, box) =>
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
            case _: IllegalArgumentException | _: InvariantViolation => Failure.InvalidAlbumData
            case _                                                   => Failure.DatabaseFailure
          Logger[F].error(error)("SQLite album operation failed").as(failed(reason))

    private given Meta[AlbumName]      = Meta[String].tiemap[AlbumName](_.refineEither)(value => value: String)
    private given Meta[Instant]        = Meta[Long].timap[Instant](Instant.ofEpochMilli)(_.toEpochMilli)
    private given Meta[InDesignPoints] = Meta[Double].tiemap[InDesignPoints](_.refineEither)(value => value: Double)
    private given Meta[Zoom]           = Meta[Double].tiemap[Zoom](_.refineEither)(value => value: Double)

    private given Read[SpreadRow] =
      Read[(UUID, UUID, SpreadTemplateKind, Option[UUID], Option[UUID], Int, Option[SpreadTemplateKind])]
        .map { case (id, template, kind, maybeAlbum, maybeChapter, position, maybeTemplateKind) =>
          (
            id = id,
            template = template,
            kind = kind,
            maybeAlbum = maybeAlbum,
            maybeChapter = maybeChapter,
            position = position,
            maybeTemplateKind = maybeTemplateKind
          )
        }

    private given Read[SlotRow] =
      Read[(UUID, String, SlotBox)].map { case (id, kind, box) => (id = id, kind = kind, box = box) }

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
      kind: SpreadTemplateKind,
      maybeAlbum: Option[UUID],
      maybeChapter: Option[UUID],
      position: Int,
      maybeTemplateKind: Option[SpreadTemplateKind]
  )
  private type SlotRow      = (id: UUID, kind: String, box: SlotBox)
  private type PlacementRow = (UUID, UUID, UUID, InDesignPoints, InDesignPoints, Boolean, Zoom, CaptionCorner)
