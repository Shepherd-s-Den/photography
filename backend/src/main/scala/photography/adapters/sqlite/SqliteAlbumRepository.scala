package photography.adapters.sqlite

import cats.Eq
import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import org.typelevel.doobie.*
import org.typelevel.doobie.implicits.*
import photography.domain.album.{Album, AlbumId, AlbumName}
import photography.domain.layout.*
import photography.ports.AlbumRepository
import photography.ports.AlbumRepository.*
import photography.ports.SpreadTemplateRepository
import photography.ports.SpreadTemplateRepository.ListAlbumStartTemplatesResult

import java.time.Instant
import java.util.UUID

trait SqliteAlbumRepository[F[_]] extends AlbumRepository[F] with SpreadTemplateRepository[F]

private object UuidEq:
  given Eq[UUID]                            = Eq.fromUniversalEquals
  def eqv(left: UUID, right: UUID): Boolean = left === right

object SqliteAlbumRepository:
  def make[F[_]: Async](transactor: Transactor[F]): SqliteAlbumRepository[F] =
    new LiveSqliteAlbumRepository(transactor)

  private type AlbumRow        = (String, String, String, Long)
  private type SpreadRow       = (String, String)
  private type BoxRow          = (String, Double, Double, Double, Double)
  private type BleedRow        = (String, String)
  private type TemplateRow     = (String, String, String)
  private type TemplateSlotRow = (String, Double, Double, Double, Double)

  final private case class SpreadRaw(
      spread: SpreadRow,
      photos: Vector[BoxRow],
      texts: Vector[BoxRow],
      bleed: Vector[BleedRow]
  )

  final private class LiveSqliteAlbumRepository[F[_]: Async](transactor: Transactor[F]) extends SqliteAlbumRepository[F]:

    override def list(offset: Int, limit: Int): F[ListResult] =
      val query =
        for
          total <- sql"SELECT COUNT(*) FROM album".query[Long].unique
          rows  <- sql"""
            SELECT id, title, synopsis, created_at_ms
            FROM album
            ORDER BY created_at_ms DESC, id DESC
            LIMIT $limit OFFSET $offset
          """.query[AlbumRow].to[Vector]
          parts <- rows.traverse(row => loadSpreads(row._1).map(row -> _))
        yield total -> parts
      runQuery(query).map:
        case Left(_)               => ListResult.Failed(Failure.DatabaseFailure)
        case Right((total, parts)) =>
          parts.traverse(decodeAlbum).fold(
            ListResult.Failed(_),
            albums => ListResult.Listed(AlbumPageSlice(total, albums))
          )

    override def get(id: AlbumId): F[GetResult] =
      runQuery(loadAlbum(id.value.toString)).map:
        case Left(_)            => GetResult.Failed(Failure.DatabaseFailure)
        case Right(None)        => GetResult.NotFound
        case Right(Some(parts)) => decodeAlbum(parts).fold(GetResult.Failed(_), GetResult.Found(_))

    override def create(album: Album, cover: Cover): F[CreateResult] =
      if !isWritable(album, cover) then CreateResult.Failed(Failure.InvalidAlbumData).pure[F]
      else
        runQuery(insertAlbum(album, cover)).map:
          case Left(_)  => CreateResult.Failed(Failure.DatabaseFailure)
          case Right(_) => CreateResult.Created

    override def rename(id: AlbumId, title: AlbumName): F[RenameResult] =
      val albumId: String    = id.value.toString
      val titleValue: String = title
      val query              =
        for
          changed <- sql"UPDATE album SET title = $titleValue WHERE id = $albumId".update.run
          parts   <- if changed === 0 then None.pure[ConnectionIO] else loadAlbum(albumId)
        yield parts
      runQuery(query).map:
        case Left(_)            => RenameResult.Failed(Failure.DatabaseFailure)
        case Right(None)        => RenameResult.NotFound
        case Right(Some(parts)) => decodeAlbum(parts).fold(RenameResult.Failed(_), RenameResult.Renamed(_))

    override def delete(id: AlbumId): F[DeleteResult] =
      runQuery(deleteAlbum(id.value.toString)).map:
        case Left(_)      => DeleteResult.Failed(Failure.DatabaseFailure)
        case Right(false) => DeleteResult.NotFound
        case Right(true)  => DeleteResult.Deleted

    override def listAlbumStartTemplates: F[ListAlbumStartTemplatesResult] =
      runQuery(loadAlbumStartTemplates).map:
        case Left(_)     => ListAlbumStartTemplatesResult.Failed(SpreadTemplateRepository.Failure.DatabaseFailure)
        case Right(rows) =>
          rows.traverse(decodeTemplate).flatMap(templates =>
            NonEmptyList.fromList(templates.toList)
              .toRight(SpreadTemplateRepository.Failure.InvalidSpreadData)
          ).fold(ListAlbumStartTemplatesResult.Failed(_), ListAlbumStartTemplatesResult.Listed(_))

    private def isWritable(album: Album, cover: Cover): Boolean =
      sameId(cover.album.value, album.id.value) && album.spreads.forall:
        case start: Spread.AlbumStart =>
          sameId(start.album.value, album.id.value) && start.placements.isEmpty && start.slots.forall(belongsTo(start.id))
        case _ => false

    private def sameId(left: UUID, right: UUID): Boolean = UuidEq.eqv(left, right)

    private def belongsTo(spreadId: SpreadId)(slot: SpreadSlot): Boolean =
      slot match
        case SpreadSlot.Photo(_, owner, _, _) => sameId(owner.value, spreadId.value)
        case SpreadSlot.Text(_, owner, _)     => sameId(owner.value, spreadId.value)

    private def insertAlbum(album: Album, cover: Cover): ConnectionIO[Unit] =
      val albumId: String   = album.id.value.toString
      val title: String     = album.title
      val createdAtMs: Long = album.createdAt.toEpochMilli
      val coverAlbumId      = cover.album.value.toString
      for
        _ <- sql"""
          INSERT INTO album (id, title, synopsis, created_at_ms)
          VALUES ($albumId, $title, ${album.synopsis}, $createdAtMs)
        """.update.run
        _ <- album.spreads.toList.traverse_ {
          case start: Spread.AlbumStart => insertAlbumStart(start)
          case _                        => ().pure[ConnectionIO]
        }
        _ <- sql"""
          INSERT INTO cover (album_id, spine_owner)
          VALUES ($coverAlbumId, ${encodeSpineOwner(cover.spineOwner)})
        """.update.run
        _ <- insertCoverSlot(coverAlbumId, "front", cover.front.id.value.toString)
        _ <- insertCoverSlot(coverAlbumId, "back", cover.back.id.value.toString)
      yield ()

    private def insertAlbumStart(start: Spread.AlbumStart): ConnectionIO[Unit] =
      val spreadId   = start.id.value.toString
      val templateId = start.template.value.toString
      val albumId    = start.album.value.toString
      for
        _ <- sql"""
          INSERT INTO spread (id, template_id, kind, album_id, chapter_id, position)
          VALUES ($spreadId, $templateId, 'album_start', $albumId, NULL, 0)
        """.update.run
        _ <- start.slots.traverse_(insertSlot(spreadId))
      yield ()

    private def insertSlot(spreadId: String)(slot: SpreadSlot): ConnectionIO[Unit] =
      slot match
        case SpreadSlot.Photo(id, _, box, bleed) =>
          val slotId                = id.value.toString
          val (x, y, width, height) = boxValues(box)
          for
            _ <- sql"""
              INSERT INTO spread_photo_slot (id, spread_id, x, y, width, height)
              VALUES ($slotId, $spreadId, $x, $y, $width, $height)
            """.update.run
            _ <- bleed.toVector.traverse_(edge =>
              sql"INSERT INTO spread_photo_slot_bleed (slot_id, edge) VALUES ($slotId, ${encodeEdge(edge)})".update.run
            )
          yield ()
        case SpreadSlot.Text(id, _, box) =>
          val slotId                = id.value.toString
          val (x, y, width, height) = boxValues(box)
          sql"""
            INSERT INTO spread_text_slot (id, spread_id, x, y, width, height)
            VALUES ($slotId, $spreadId, $x, $y, $width, $height)
          """.update.run.void

    private def boxValues(box: SlotBox): (Double, Double, Double, Double) =
      (box.x, box.y, box.width, box.height)

    private def insertCoverSlot(albumId: String, side: String, slotId: String): ConnectionIO[Unit] =
      sql"INSERT INTO cover_slot (id, album_id, side) VALUES ($slotId, $albumId, $side)".update.run.void

    private def loadAlbum(albumId: String): ConnectionIO[Option[(AlbumRow, Vector[SpreadRaw])]] =
      for
        maybeRow <- sql"SELECT id, title, synopsis, created_at_ms FROM album WHERE id = $albumId"
          .query[AlbumRow].option
        parts <- maybeRow.traverse(row => loadSpreads(row._1).map(row -> _))
      yield parts

    private def loadSpreads(albumId: String): ConnectionIO[Vector[SpreadRaw]] =
      for
        spreadRows <- sql"SELECT id, template_id FROM spread WHERE album_id = $albumId ORDER BY position"
          .query[SpreadRow].to[Vector]
        raws <- spreadRows.traverse(loadSpread)
      yield raws

    private def loadSpread(spread: SpreadRow): ConnectionIO[SpreadRaw] =
      val spreadId = spread._1
      for
        photos <- sql"SELECT id, x, y, width, height FROM spread_photo_slot WHERE spread_id = $spreadId ORDER BY rowid"
          .query[BoxRow].to[Vector]
        texts <- sql"SELECT id, x, y, width, height FROM spread_text_slot WHERE spread_id = $spreadId ORDER BY rowid"
          .query[BoxRow].to[Vector]
        bleed <- sql"""
          SELECT slot_id, edge FROM spread_photo_slot_bleed
          WHERE slot_id IN (SELECT id FROM spread_photo_slot WHERE spread_id = $spreadId)
        """.query[BleedRow].to[Vector]
      yield SpreadRaw(spread, photos, texts, bleed)

    private def deleteAlbum(albumId: String): ConnectionIO[Boolean] =
      for
        _ <- sql"""
          DELETE FROM placement
          WHERE spread_photo_slot_id IN (
            SELECT slot.id FROM spread_photo_slot slot
            JOIN spread ON spread.id = slot.spread_id
            WHERE spread.album_id = $albumId
          )
          OR cover_slot_id IN (SELECT id FROM cover_slot WHERE album_id = $albumId)
        """.update.run
        _ <- sql"""
          DELETE FROM spread_photo_slot_bleed WHERE slot_id IN (
            SELECT slot.id FROM spread_photo_slot slot
            JOIN spread ON spread.id = slot.spread_id
            WHERE spread.album_id = $albumId
          )
        """.update.run
        _ <- sql"DELETE FROM spread_text_slot WHERE spread_id IN (SELECT id FROM spread WHERE album_id = $albumId)"
          .update.run
        _ <- sql"DELETE FROM spread_photo_slot WHERE spread_id IN (SELECT id FROM spread WHERE album_id = $albumId)"
          .update.run
        _       <- sql"DELETE FROM spread WHERE album_id = $albumId".update.run
        _       <- sql"DELETE FROM cover_slot WHERE album_id = $albumId".update.run
        _       <- sql"DELETE FROM cover WHERE album_id = $albumId".update.run
        deleted <- sql"DELETE FROM album WHERE id = $albumId".update.run
      yield deleted > 0

    private def loadAlbumStartTemplates: ConnectionIO[Vector[(TemplateRow, Vector[TemplateSlotRow])]] =
      for
        templates <- sql"SELECT id, name, kind FROM spread_template WHERE kind = 'album_start' ORDER BY rowid"
          .query[TemplateRow].to[Vector]
        withSlots <- templates.traverse(template =>
          sql"SELECT kind, x, y, width, height FROM spread_template_slot WHERE template_id = ${template._1} ORDER BY id"
            .query[TemplateSlotRow].to[Vector].map(template -> _)
        )
      yield withSlots

    private def decodeAlbum(parts: (AlbumRow, Vector[SpreadRaw])): Either[Failure, Album] =
      val (albumRow, spreadRaws)             = parts
      val (id, title, synopsis, createdAtMs) = albumRow
      for
        albumId    <- uuid(id).map(AlbumId(_))
        albumTitle <- title.refineEither[Not[Empty] & Not[Blank]].leftMap(_ => Failure.InvalidAlbumData)
        spreads    <- spreadRaws.traverse(decodeSpread(albumId))
        nonEmpty   <- NonEmptyList.fromList(spreads.toList).toRight(Failure.InvalidAlbumData)
      yield Album(albumId, albumTitle, synopsis, Instant.ofEpochMilli(createdAtMs), nonEmpty)

    private def decodeSpread(albumId: AlbumId)(raw: SpreadRaw): Either[Failure, Spread] =
      for
        spreadId   <- uuid(raw.spread._1).map(SpreadId(_))
        templateId <- uuid(raw.spread._2).map(SpreadTemplateId(_))
        photos     <- raw.photos.traverse(decodePhotoSlot(spreadId, raw.bleed))
        texts      <- raw.texts.traverse(decodeTextSlot(spreadId))
      yield Spread.AlbumStart(spreadId, albumId, templateId, photos ++ texts, Vector.empty)

    private def decodePhotoSlot(spreadId: SpreadId, bleedRows: Vector[BleedRow])(row: BoxRow): Either[Failure, SpreadSlot] =
      for
        slotId <- uuid(row._1).map(PhotoSpreadSlotId(_))
        box    <- decodeBox(row).toRight(Failure.InvalidAlbumData)
        bleed  <- bleedRows.filter(_._1 === row._1).traverse(edge => decodeEdge(edge._2).toRight(Failure.InvalidAlbumData))
      yield SpreadSlot.Photo(slotId, spreadId, box, bleed.toSet)

    private def decodeTextSlot(spreadId: SpreadId)(row: BoxRow): Either[Failure, SpreadSlot] =
      for
        slotId <- uuid(row._1).map(TextSpreadSlotId(_))
        box    <- decodeBox(row).toRight(Failure.InvalidAlbumData)
      yield SpreadSlot.Text(slotId, spreadId, box)

    private def decodeTemplate(
        parts: (TemplateRow, Vector[TemplateSlotRow])
    ): Either[SpreadTemplateRepository.Failure, SpreadTemplate] =
      val ((id, name, kind), slotRows) = parts
      for
        templateId   <- uuid(id).map(SpreadTemplateId(_)).leftMap(_ => SpreadTemplateRepository.Failure.InvalidSpreadData)
        templateName <- name.refineEither[Not[Empty]].leftMap(_ => SpreadTemplateRepository.Failure.InvalidSpreadData)
        templateKind <- decodeKind(kind).toRight(SpreadTemplateRepository.Failure.InvalidSpreadData)
        slots        <- slotRows.traverse(decodeTemplateSlot)
        nonEmpty     <- NonEmptyList.fromList(slots.toList).toRight(SpreadTemplateRepository.Failure.InvalidSpreadData)
      yield SpreadTemplate(templateId, templateName, templateKind, nonEmpty)

    private def decodeTemplateSlot(row: TemplateSlotRow): Either[SpreadTemplateRepository.Failure, SpreadTemplateSlot] =
      val (kind, x, y, width, height) = row
      val box                         = decodeBox(("", x, y, width, height)).toRight(SpreadTemplateRepository.Failure.InvalidSpreadData)
      val decoded: Either[SpreadTemplateRepository.Failure, SpreadTemplateSlot] = kind match
        case "photo" => box.map(SpreadTemplateSlot.Photo(_))
        case "text"  => box.map(SpreadTemplateSlot.Text(_))
        case _       => SpreadTemplateRepository.Failure.InvalidSpreadData.asLeft[SpreadTemplateSlot]
      decoded

    private def decodeBox(row: BoxRow): Option[SlotBox] =
      val (_, x, y, width, height) = row
      for
        refinedX      <- x.refineOption[GreaterEqual[0.0] & Less[1.7976931348623157e308]]
        refinedY      <- y.refineOption[GreaterEqual[0.0] & Less[1.7976931348623157e308]]
        refinedWidth  <- width.refineOption[Positive & Less[1.7976931348623157e308]]
        refinedHeight <- height.refineOption[Positive & Less[1.7976931348623157e308]]
      yield SlotBox(refinedX, refinedY, refinedWidth, refinedHeight)

    private def decodeKind(value: String): Option[SpreadTemplateKind] =
      value match
        case "album_start"   => Some(SpreadTemplateKind.AlbumStart)
        case "chapter_start" => Some(SpreadTemplateKind.ChapterStart)
        case "photos_only"   => Some(SpreadTemplateKind.PhotosOnly)
        case _               => None

    private def decodeEdge(value: String): Option[SpreadEdge] =
      value match
        case "top"    => Some(SpreadEdge.Top)
        case "bottom" => Some(SpreadEdge.Bottom)
        case "left"   => Some(SpreadEdge.Left)
        case "right"  => Some(SpreadEdge.Right)
        case _        => None

    private def encodeEdge(value: SpreadEdge): String =
      value match
        case SpreadEdge.Top    => "top"
        case SpreadEdge.Bottom => "bottom"
        case SpreadEdge.Left   => "left"
        case SpreadEdge.Right  => "right"

    private def encodeSpineOwner(value: SpineOwner): String =
      value match
        case SpineOwner.Front => "front"
        case SpineOwner.Back  => "back"

    private def uuid(value: String): Either[Failure, UUID] =
      Either.catchNonFatal(UUID.fromString(value)).leftMap(_ => Failure.InvalidAlbumData)

    private def runQuery[A](query: ConnectionIO[A]): F[Either[Throwable, A]] =
      query.transact(transactor).attempt
