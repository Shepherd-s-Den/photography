package photography.adapters.sqlite

import cats.effect.Async
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import org.typelevel.doobie.*
import org.typelevel.doobie.implicits.*
import photography.app.album.*
import photography.domain.album.{Album, AlbumId, AlbumName}
import photography.domain.layout.*

import java.time.Instant
import java.util.UUID

trait SqliteAlbumRepository[F[_]] extends AlbumRepository[F]

object SqliteAlbumRepository:
  def make[F[_]: Async](transactor: Transactor[F]): SqliteAlbumRepository[F] =
    new LiveSqliteAlbumRepository(transactor)

  final private class LiveSqliteAlbumRepository[F[_]: Async](transactor: Transactor[F])
      extends SqliteAlbumRepository[F]:

    override def list(offset: Int, limit: Int): F[Either[AlbumRepositoryFailure, AlbumPageSlice]] =
      val query =
        for
          total  <- sql"SELECT COUNT(*) FROM album".query[Long].unique
          albums <- sql"""
            SELECT id, title, synopsis, created_at_ms
            FROM album
            ORDER BY created_at_ms DESC, id DESC
            LIMIT $limit OFFSET $offset
          """.query[(String, String, String, Long)].to[Vector]
        yield total -> albums
      capture(query.transact(transactor)).map(_.flatMap: (total, rows) =>
        rows.traverse(decodeAlbum).map(albums => AlbumPageSlice(total, albums)))

    override def builtInAlbumStartTemplate
        : F[Either[AlbumRepositoryFailure, Option[SpreadTemplate]]] =
      val query =
        for
          maybeTemplate <- sql"""
            SELECT id, name, kind FROM spread_template WHERE kind = 'album_start'
          """.query[(String, String, String)].option
          slots <- maybeTemplate match
            case None      => Vector.empty[(String, Double, Double, Double, Double)].pure[ConnectionIO]
            case Some(row) =>
              sql"""
              SELECT kind, x, y, width, height
              FROM spread_template_slot
              WHERE template_id = ${row._1}
              ORDER BY id
            """.query[(String, Double, Double, Double, Double)].to[Vector]
        yield maybeTemplate -> slots
      captureTemplate(query.transact(transactor))

    override def create(album: Album, start: Spread.AlbumStart, cover: Cover): F[Either[AlbumRepositoryFailure, Unit]] =
      val slotsBelongToSpread    = start.slots.forall(slot => slotBelongsToSpread(slot, start.id))
      val resourcesBelongToAlbum =
        start.album.value === album.id.value && cover.album.value === album.id.value
      if !resourcesBelongToAlbum || start.placements.nonEmpty || !slotsBelongToSpread
      then AlbumRepositoryFailure.InvalidAlbumData.asLeft[Unit].pure[F]
      else
        val title: String = album.title
        val query         =
          for
            _ <- sql"""
              INSERT INTO album (id, title, synopsis, created_at_ms)
              VALUES (
                ${album.id.value.toString},
                $title,
                ${album.synopsis},
                ${album.createdAt.toEpochMilli}
              )
            """.update.run
            _ <- sql"""
              INSERT INTO spread (id, template_id, kind, album_id, chapter_id, position)
              VALUES (
                ${start.id.value.toString},
                ${start.template.value.toString},
                'album_start',
                ${start.album.value.toString},
                NULL,
                0
              )
            """.update.run
            _ <- start.slots.traverse_(insertSlot(start.id))
            _ <- sql"""
              INSERT INTO cover (album_id, spine_owner)
              VALUES (${cover.album.value.toString}, ${encodeSpineOwner(cover.spineOwner)})
            """.update.run
            _ <- insertCoverSlot(cover.album, "front", cover.front)
            _ <- insertCoverSlot(cover.album, "back", cover.back)
          yield ()
        capture(query.transact(transactor))

    private def slotBelongsToSpread(slot: SpreadSlot, spreadId: SpreadId) =
      slot match
        case SpreadSlot.Photo(_, owner, _, _) => owner.value === spreadId.value
        case SpreadSlot.Text(_, owner, _)     => owner.value === spreadId.value

    override def getAlbum(id: AlbumId): F[Either[AlbumRepositoryFailure, Option[Album]]] =
      val query =
        sql"""
          SELECT id, title, synopsis, created_at_ms
          FROM album
          WHERE id = ${id.value.toString}
        """.query[(String, String, String, Long)].option
      capture(query.transact(transactor)).map(_.flatMap(_.traverse(decodeAlbum)))

    override def rename(id: AlbumId, title: AlbumName): F[Either[AlbumRepositoryFailure, Option[Album]]] =
      val titleValue: String = title
      val query              =
        for
          changed <- sql"UPDATE album SET title = $titleValue WHERE id = ${id.value.toString}".update.run
          album   <-
            if changed === 0 then Option.empty[(String, String, String, Long)].pure[ConnectionIO]
            else
              sql"""
                SELECT id, title, synopsis, created_at_ms
                FROM album
                WHERE id = ${id.value.toString}
              """.query[(String, String, String, Long)].option
        yield album
      capture(query.transact(transactor)).map(_.flatMap(_.traverse(decodeAlbum)))

    override def delete(id: AlbumId): F[Either[AlbumRepositoryFailure, Boolean]] =
      val albumId = id.value.toString
      val query   =
        for
          _ <- sql"""
            DELETE FROM placement
            WHERE spread_photo_slot_id IN (
              SELECT id FROM spread_photo_slot WHERE spread_id IN (
                SELECT id FROM spread
                WHERE album_id = $albumId
                  OR chapter_id IN (SELECT id FROM chapter WHERE album_id = $albumId)
              )
            )
              OR cover_slot_id IN (SELECT id FROM cover_slot WHERE album_id = $albumId)
              OR photo_id IN (SELECT photo.id FROM photo JOIN chapter ON photo.chapter_id = chapter.id WHERE chapter.album_id = $albumId)
          """.update.run
          _ <- sql"""
            DELETE FROM spread_editing_lock WHERE spread_id IN (
              SELECT id FROM spread
              WHERE album_id = $albumId
                OR chapter_id IN (SELECT id FROM chapter WHERE album_id = $albumId)
            )
          """.update.run
          _ <- sql"DELETE FROM cover_editing_lock WHERE album_id = $albumId".update.run
          _ <- sql"""
            DELETE FROM spread_photo_slot_bleed
            WHERE slot_id IN (SELECT slot.id FROM spread_photo_slot slot
              JOIN spread ON spread.id = slot.spread_id
              WHERE spread.album_id = $albumId
                OR spread.chapter_id IN (SELECT id FROM chapter WHERE album_id = $albumId))
          """.update.run
          _ <- sql"""
            DELETE FROM spread_text_slot WHERE spread_id IN (
              SELECT id FROM spread
              WHERE album_id = $albumId
                OR chapter_id IN (SELECT id FROM chapter WHERE album_id = $albumId)
            )
          """.update.run
          _ <- sql"""
            DELETE FROM spread_photo_slot WHERE spread_id IN (
              SELECT id FROM spread
              WHERE album_id = $albumId
                OR chapter_id IN (SELECT id FROM chapter WHERE album_id = $albumId)
            )
          """.update.run
          _ <- sql"""
            DELETE FROM spread
            WHERE album_id = $albumId
              OR chapter_id IN (SELECT id FROM chapter WHERE album_id = $albumId)
          """.update.run
          _       <- sql"DELETE FROM cover_slot WHERE album_id = $albumId".update.run
          _       <- sql"DELETE FROM cover WHERE album_id = $albumId".update.run
          _       <- sql"DELETE FROM chapter_camera_lens WHERE chapter_id IN (SELECT id FROM chapter WHERE album_id = $albumId)".update.run
          _       <- sql"DELETE FROM chapter_camera WHERE chapter_id IN (SELECT id FROM chapter WHERE album_id = $albumId)".update.run
          _       <- sql"DELETE FROM photo WHERE chapter_id IN (SELECT id FROM chapter WHERE album_id = $albumId)".update.run
          _       <- sql"DELETE FROM chapter WHERE album_id = $albumId".update.run
          deleted <- sql"DELETE FROM album WHERE id = $albumId".update.run
        yield deleted > 0
      capture(query.transact(transactor))

    private def insertSlot(spreadId: SpreadId)(slot: SpreadSlot) =
      slot match
        case SpreadSlot.Photo(id, _, box, bleed) =>
          val x: Double      = box.x
          val y: Double      = box.y
          val width: Double  = box.width
          val height: Double = box.height
          for
            _ <- sql"""
                INSERT INTO spread_photo_slot (id, spread_id, x, y, width, height)
                VALUES (${id.value.toString}, ${spreadId.value.toString}, $x, $y, $width, $height)
              """.update.run
            _ <- bleed.toVector.traverse_(edge =>
              sql"""
                  INSERT INTO spread_photo_slot_bleed (slot_id, edge)
                  VALUES (${id.value.toString}, ${encodeEdge(edge)})
                """.update.run
            )
          yield ()
        case SpreadSlot.Text(id, _, box) =>
          val x: Double      = box.x
          val y: Double      = box.y
          val width: Double  = box.width
          val height: Double = box.height
          sql"""
              INSERT INTO spread_text_slot (id, spread_id, x, y, width, height)
              VALUES (${id.value.toString}, ${spreadId.value.toString}, $x, $y, $width, $height)
            """.update.run.void

    private def insertCoverSlot(albumId: AlbumId, side: String, slot: CoverSlot) =
      sql"""
          INSERT INTO cover_slot (id, album_id, side)
          VALUES (${slot.id.value.toString}, ${albumId.value.toString}, $side)
        """.update.run.void

    private def decodeTemplate(row: (String, String, String), slots: Vector[(String, Double, Double, Double, Double)]) =
      val templateSlots = slots.traverse: (kind, x, y, width, height) =>
        for
          refinedX      <- refinePosition(x)
          refinedY      <- refinePosition(y)
          refinedWidth  <- refineDistance(width)
          refinedHeight <- refineDistance(height)
          templateSlot  <- kind match
            case "photo" => SpreadTemplateSlot.Photo(SlotBox(refinedX, refinedY, refinedWidth, refinedHeight)).asRight[AlbumRepositoryFailure]
            case "text"  => SpreadTemplateSlot.Text(SlotBox(refinedX, refinedY, refinedWidth, refinedHeight)).asRight[AlbumRepositoryFailure]
            case _       => AlbumRepositoryFailure.InvalidTemplate.asLeft[SpreadTemplateSlot]
        yield templateSlot
      for
        templateId  <- parseUuid(row._1).leftMap(_ => AlbumRepositoryFailure.InvalidTemplate)
        refinedName <- refineTitle(row._2)
        kind        <- row._3 match
          case "album_start"   => SpreadTemplateKind.AlbumStart.asRight[AlbumRepositoryFailure]
          case "chapter_start" => SpreadTemplateKind.ChapterStart.asRight[AlbumRepositoryFailure]
          case "photos_only"   => SpreadTemplateKind.PhotosOnly.asRight[AlbumRepositoryFailure]
          case _               => AlbumRepositoryFailure.InvalidTemplate.asLeft[SpreadTemplateKind]
        parsedSlots <- templateSlots
        head        <- parsedSlots.headOption.toRight(AlbumRepositoryFailure.InvalidTemplate)
      yield SpreadTemplate(SpreadTemplateId(templateId), refinedName, kind, cats.data.NonEmptySet.of(head, parsedSlots.drop(1)*))

    private def decodeAlbum(row: (String, String, String, Long)) =
      for
        albumUuid <- parseUuid(row._1).leftMap(_ => AlbumRepositoryFailure.InvalidAlbumData)
        title     <- row._2.refineEither[Not[Empty]].leftMap(_ => AlbumRepositoryFailure.InvalidAlbumData)
      yield Album(id = AlbumId(albumUuid), title = title, synopsis = row._3, createdAt = Instant.ofEpochMilli(row._4))

    private def capture[A](action: F[A]) =
      action.attempt.map(_.leftMap(_ => AlbumRepositoryFailure.DatabaseFailure))

    private def captureTemplate(action: F[(Option[(String, String, String)], Vector[(String, Double, Double, Double, Double)])]) =
      capture(action).map(_.flatMap: (maybeTemplate, slots) =>
        maybeTemplate.traverse(row => decodeTemplate(row, slots)))

    private def parseUuid(value: String) =
      Either.catchNonFatal(UUID.fromString(value))

    private def refineTitle(value: String) =
      value.refineEither[Not[Empty]].leftMap(_ => AlbumRepositoryFailure.InvalidTemplate)

    private def refinePosition(value: Double) =
      value.refineEither[GreaterEqual[0.0] & Less[1.7976931348623157e308]]
        .leftMap(_ => AlbumRepositoryFailure.InvalidTemplate)

    private def refineDistance(value: Double) =
      value.refineEither[Positive & Less[1.7976931348623157e308]]
        .leftMap(_ => AlbumRepositoryFailure.InvalidTemplate)

    private def encodeSpineOwner(value: SpineOwner) =
      value match
        case SpineOwner.Front => "front"
        case SpineOwner.Back  => "back"

    private def encodeEdge(value: SpreadEdge) =
      value match
        case SpreadEdge.Top    => "top"
        case SpreadEdge.Bottom => "bottom"
        case SpreadEdge.Left   => "left"
        case SpreadEdge.Right  => "right"
