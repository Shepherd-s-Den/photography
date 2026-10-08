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
            SELECT id, title, created_at_ms
            FROM album
            ORDER BY created_at_ms DESC, id DESC
            LIMIT $limit OFFSET $offset
          """.query[(String, String, Long)].to[Vector]
        yield AlbumPageSlice(total, albums.map(toSummary))
      capture(query.transact(transactor))

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
        yield (maybeTemplate, slots)
      captureTemplate(query.transact(transactor))

    override def create(composition: photography.domain.album.AlbumComposition)
        : F[Either[AlbumRepositoryFailure, Unit]] =
      val title: String = composition.album.title
      val query         =
        for
          _ <- sql"""
            INSERT INTO album (id, title, synopsis, created_at_ms)
            VALUES (
              ${composition.album.id.value.toString},
              $title,
              ${composition.album.synopsis},
              ${composition.album.createdAt.toEpochMilli}
            )
          """.update.run
          _ <- sql"""
            INSERT INTO spread (id, template_id, kind, album_id, chapter_id, position)
            VALUES (
              ${composition.start.id.value.toString},
              ${composition.start.templateId.value.toString},
              'album_start',
              ${composition.album.id.value.toString},
              NULL,
              0
            )
          """.update.run
          _ <- composition.start.slots.traverse_(insertSlot(composition.start.id))
          _ <- sql"""
            INSERT INTO cover (album_id, spine_owner)
            VALUES (${composition.album.id.value.toString}, ${spineOwnerValue(composition.cover.spineOwner)})
          """.update.run
          _ <- insertCoverSlot(composition.album.id, "front", composition.cover.front)
          _ <- insertCoverSlot(composition.album.id, "back", composition.cover.back)
        yield ()
      capture(query.transact(transactor))

    override def getComposition(
        id: AlbumId
    ): F[Either[AlbumRepositoryFailure, Option[photography.domain.album.AlbumComposition]]] =
      val query =
        for
          album <- sql"""
            SELECT id, title, synopsis, created_at_ms FROM album WHERE id = ${id.value.toString}
          """.query[(String, String, String, Long)].option
          composition <- album.traverse(readComposition)
        yield composition match
          case None        => Option.empty[photography.domain.album.AlbumComposition].asRight[AlbumRepositoryFailure]
          case Some(value) => value.map(_.some)
      captureResult(query.transact(transactor))

    override def rename(
        id: AlbumId,
        title: AlbumName
    ): F[Either[AlbumRepositoryFailure, Option[AlbumSummary]]] =
      val titleValue: String = title
      val query              =
        for
          changed <- sql"UPDATE album SET title = $titleValue WHERE id = ${id.value.toString}".update.run
          summary <-
            if changed === 0 then Option.empty[(String, String, Long)].pure[ConnectionIO]
            else
              sql"SELECT id, title, created_at_ms FROM album WHERE id = ${id.value.toString}"
                .query[(String, String, Long)]
                .option
        yield summary.map(toSummary)
      capture(query.transact(transactor))

    override def delete(id: AlbumId): F[Either[AlbumRepositoryFailure, Boolean]] =
      val albumId = id.value.toString
      val query   =
        for
          _ <- sql"""
            DELETE FROM placement
            WHERE spread_photo_slot_id IN (SELECT id FROM spread_photo_slot WHERE spread_id IN
              (SELECT id FROM spread WHERE album_id = $albumId))
              OR cover_slot_id IN (SELECT id FROM cover_slot WHERE album_id = $albumId)
              OR photo_id IN (SELECT photo.id FROM photo JOIN chapter ON photo.chapter_id = chapter.id WHERE chapter.album_id = $albumId)
          """.update.run
          _ <- sql"""
            DELETE FROM spread_photo_slot_bleed
            WHERE slot_id IN (SELECT slot.id FROM spread_photo_slot slot
              JOIN spread ON spread.id = slot.spread_id WHERE spread.album_id = $albumId)
          """.update.run
          _       <- sql"DELETE FROM spread_text_slot WHERE spread_id IN (SELECT id FROM spread WHERE album_id = $albumId)".update.run
          _       <- sql"DELETE FROM spread_photo_slot WHERE spread_id IN (SELECT id FROM spread WHERE album_id = $albumId)".update.run
          _       <- sql"DELETE FROM spread WHERE album_id = $albumId".update.run
          _       <- sql"DELETE FROM cover_slot WHERE album_id = $albumId".update.run
          _       <- sql"DELETE FROM cover WHERE album_id = $albumId".update.run
          _       <- sql"DELETE FROM chapter_camera_lens WHERE chapter_id IN (SELECT id FROM chapter WHERE album_id = $albumId)".update.run
          _       <- sql"DELETE FROM chapter_camera WHERE chapter_id IN (SELECT id FROM chapter WHERE album_id = $albumId)".update.run
          _       <- sql"DELETE FROM photo WHERE chapter_id IN (SELECT id FROM chapter WHERE album_id = $albumId)".update.run
          _       <- sql"DELETE FROM chapter WHERE album_id = $albumId".update.run
          deleted <- sql"DELETE FROM album WHERE id = $albumId".update.run
        yield deleted > 0
      capture(query.transact(transactor))

    private def readComposition(
        albumRow: (String, String, String, Long)
    ): ConnectionIO[Either[AlbumRepositoryFailure, photography.domain.album.AlbumComposition]] =
      val (albumIdText, title, synopsis, createdAtMs) = albumRow
      for
        maybeStart <- sql"""
            SELECT id, template_id
            FROM spread
            WHERE album_id = $albumIdText AND kind = 'album_start' AND position = 0
          """.query[(String, String)].option
        maybeCover <- sql"""
            SELECT spine_owner FROM cover WHERE album_id = $albumIdText
          """.query[String].option
        coverSlots <- sql"""
            SELECT id, side FROM cover_slot WHERE album_id = $albumIdText ORDER BY side
          """.query[(String, String)].to[Vector]
        chapters <- sql"""
            SELECT id, destination_id, description, start_ms, end_ms, position, source_folder
            FROM chapter WHERE album_id = $albumIdText ORDER BY position
          """.query[(String, String, String, Long, Long, Int, String)].to[Vector]
        chapterResults   <- chapters.traverse(readChapter(albumIdText))
        startComposition <- maybeStart.traverse((spreadId, templateId) => readSpread(spreadId, templateId))
        coverPlacements  <- readCoverPlacements(albumIdText)
      yield
        for
          _                               <- maybeStart.toRight(AlbumRepositoryFailure.IncompleteComposition)
          spineOwnerValue                 <- maybeCover.toRight(AlbumRepositoryFailure.IncompleteComposition)
          frontId                         <- coverSlots.find(_._2 === "front").map(_._1).toRight(AlbumRepositoryFailure.IncompleteComposition)
          backId                          <- coverSlots.find(_._2 === "back").map(_._1).toRight(AlbumRepositoryFailure.IncompleteComposition)
          albumUuid                       <- parseUuid(albumIdText)
          frontUuid                       <- parseUuid(frontId)
          backUuid                        <- parseUuid(backId)
          refinedTitle                    <- refineTitle(title)
          spineOwner                      <- parseSpineOwner(spineOwnerValue)
          spread                          <- startComposition.toRight(AlbumRepositoryFailure.IncompleteComposition).flatMap(identity)
          chapterCompositions             <- chapterResults.sequence
          (frontPlacement, backPlacement) <- coverPlacements
        yield
          val cover = CoverComposition(
            spineOwner,
            CoverSlot(CoverSlotId(frontUuid)),
            frontPlacement,
            CoverSlot(CoverSlotId(backUuid)),
            backPlacement
          )
          photography.domain.album.AlbumComposition(
            Album(AlbumId(albumUuid), refinedTitle, synopsis, Instant.ofEpochMilli(createdAtMs)),
            spread,
            cover,
            chapterCompositions
          )

    private def readChapter(albumIdText: String)(
        chapterRow: (String, String, String, Long, Long, Int, String)
    ): ConnectionIO[Either[AlbumRepositoryFailure, photography.domain.album.ChapterComposition]] =
      val (chapterIdText, destinationIdText, description, startMs, endMs, position, sourceFolder) = chapterRow
      for
        photoRows <- sql"""
            SELECT id, source, thumbnail, captured_at_ms, width, height, caption
            FROM photo WHERE chapter_id = $chapterIdText ORDER BY captured_at_ms IS NULL, captured_at_ms, id
          """.query[(String, String, Option[String], Option[Long], Int, Int, Option[String])].to[Vector]
        spreadRows <- sql"""
            SELECT id, template_id, kind, position FROM spread
            WHERE chapter_id = $chapterIdText ORDER BY position
          """.query[(String, String, String, Int)].to[Vector]
        photos = photoRows.traverse(parsePhoto(chapterIdText))
        spreads <- spreadRows.traverse(readChapterSpread)
      yield
        for
          albumId         <- parseUuid(albumIdText)
          chapterId       <- parseUuid(chapterIdText)
          destinationId   <- parseUuid(destinationIdText)
          refinedPosition <- position.refineEither[GreaterEqual[0] & Less[20]]
            .leftMap(_ => AlbumRepositoryFailure.IncompleteComposition)
          refinedFolder <- Either.catchNonFatal(java.nio.file.Path.of(sourceFolder))
            .leftMap(_ => AlbumRepositoryFailure.IncompleteComposition)
          parsedPhotos  <- photos
          parsedSpreads <- spreads.sequence
          start         <- parsedSpreads.headOption.toRight(AlbumRepositoryFailure.IncompleteComposition)
          _             <- Either.cond(start._2, (), AlbumRepositoryFailure.IncompleteComposition)
        yield
          val chapter = photography.domain.album.Chapter(
            photography.domain.album.ChapterId(chapterId),
            AlbumId(albumId),
            photography.domain.album.DestinationId(destinationId),
            description,
            photography.domain.album.VisitPeriod(Instant.ofEpochMilli(startMs), Instant.ofEpochMilli(endMs)),
            refinedPosition,
            photography.domain.album.RelativeLocation(refinedFolder)
          )
          photography.domain.album.ChapterComposition(chapter, start._1, parsedPhotos, parsedSpreads.drop(1).map(_._1))

    private def readChapterSpread(
        row: (String, String, String, Int)
    ): ConnectionIO[Either[AlbumRepositoryFailure, (SpreadComposition, Boolean)]] =
      val (spreadId, templateId, kind, position) = row
      val isStart                                = kind === "chapter_start" && position === 0
      val validKind                              = isStart || (kind === "photos_only" && position > 0)
      readSpread(spreadId, templateId).map(_.map(_ -> isStart).flatMap: composition =>
        Either.cond(validKind, composition, AlbumRepositoryFailure.IncompleteComposition))

    private def readSpread(
        spreadIdText: String,
        templateIdText: String
    ): ConnectionIO[Either[AlbumRepositoryFailure, SpreadComposition]] =
      for
        slotRows <- sql"""
            SELECT 'photo', id, x, y, width, height FROM spread_photo_slot WHERE spread_id = $spreadIdText
            UNION ALL
            SELECT 'text', id, x, y, width, height FROM spread_text_slot WHERE spread_id = $spreadIdText
            ORDER BY 1, 3, 4, 5, 6
          """.query[(String, String, Double, Double, Double, Double)].to[Vector]
        bleedRows <- sql"""
            SELECT b.slot_id, b.edge FROM spread_photo_slot_bleed b
            JOIN spread_photo_slot s ON s.id = b.slot_id
            WHERE s.spread_id = $spreadIdText ORDER BY b.slot_id, b.edge
          """.query[(String, String)].to[Vector]
        placements <- readSpreadPlacements(spreadIdText)
      yield
        for
          spreadId         <- parseUuid(spreadIdText)
          templateId       <- parseUuid(templateIdText)
          slots            <- slotRows.traverse(parseSpreadSlot(spreadId, bleedRows.groupMap(_._1)(_._2)))
          parsedPlacements <- placements
        yield SpreadComposition(SpreadId(spreadId), SpreadTemplateId(templateId), slots, parsedPlacements)

    private def readSpreadPlacements(
        spreadIdText: String
    ): ConnectionIO[Either[AlbumRepositoryFailure, Vector[SpreadPlacement]]] =
      sql"""
          SELECT p.id, p.photo_id, p.spread_photo_slot_id, p.shift_x, p.shift_y, p.mirrored, p.zoom, p.caption_corner
          FROM placement p
          JOIN spread_photo_slot s ON s.id = p.spread_photo_slot_id
          WHERE s.spread_id = $spreadIdText
          ORDER BY p.id
        """.query[(String, String, String, Double, Double, Int, Double, String)].to[Vector].map:
        _.traverse(parseSpreadPlacement)

    private def readCoverPlacements(
        albumIdText: String
    ): ConnectionIO[Either[AlbumRepositoryFailure, (Option[CoverPlacement], Option[CoverPlacement])]] =
      sql"""
          SELECT p.id, p.photo_id, p.cover_slot_id, p.shift_x, p.shift_y, p.mirrored, p.zoom, s.side
          FROM placement p
          JOIN cover_slot s ON s.id = p.cover_slot_id
          WHERE s.album_id = $albumIdText
          ORDER BY s.side
        """.query[(String, String, String, Double, Double, Int, Double, String)].to[Vector].map: rows =>
        rows.traverse(parseCoverPlacement).flatMap: placements =>
          val front = placements.collect { case ("front", placement) => placement }
          val back  = placements.collect { case ("back", placement) => placement }
          Either.cond(
            front.sizeIs <= 1 && back.sizeIs <= 1,
            (front.headOption, back.headOption),
            AlbumRepositoryFailure.IncompleteComposition
          )

    private def parsePhoto(chapterIdText: String)(
        row: (String, String, Option[String], Option[Long], Int, Int, Option[String])
    ): Either[AlbumRepositoryFailure, photography.domain.album.Photo] =
      val (idText, source, thumbnail, capturedAt, width, height, caption) = row
      for
        id            <- parseUuid(idText)
        chapterId     <- parseUuid(chapterIdText)
        sourcePath    <- parsePath(source)
        thumbnailPath <- thumbnail.traverse(parsePath)
        refinedWidth  <- width.refineEither[Positive].leftMap(_ => AlbumRepositoryFailure.IncompleteComposition)
        refinedHeight <- height.refineEither[Positive].leftMap(_ => AlbumRepositoryFailure.IncompleteComposition)
      yield photography.domain.album.Photo(
        photography.domain.album.PhotoId(id),
        photography.domain.album.ChapterId(chapterId),
        photography.domain.album.RelativeLocation(sourcePath),
        thumbnailPath.map(photography.domain.album.RelativeLocation.apply),
        capturedAt.map(Instant.ofEpochMilli),
        photography.domain.album.PhotoDimensions(refinedWidth, refinedHeight),
        caption
      )

    private def parsePath(value: String): Either[AlbumRepositoryFailure, java.nio.file.Path] =
      Either.catchNonFatal(java.nio.file.Path.of(value)).leftMap(_ => AlbumRepositoryFailure.IncompleteComposition)

    private def parseSpreadPlacement(
        row: (String, String, String, Double, Double, Int, Double, String)
    ): Either[AlbumRepositoryFailure, SpreadPlacement] =
      val (idText, photoText, slotText, shiftX, shiftY, mirrored, zoom, cornerText) = row
      for
        id        <- parseUuid(idText)
        photoId   <- parseUuid(photoText)
        slotId    <- parseUuid(slotText)
        transform <- parseTransform(shiftX, shiftY, mirrored, zoom)
        corner    <- parseCaptionCorner(cornerText)
      yield SpreadPlacement(
        SpreadPlacementId(id),
        PhotoSpreadSlotId(slotId),
        photography.domain.album.PhotoId(photoId),
        transform,
        corner
      )

    private def parseCoverPlacement(
        row: (String, String, String, Double, Double, Int, Double, String)
    ): Either[AlbumRepositoryFailure, (String, CoverPlacement)] =
      val (idText, photoText, slotText, shiftX, shiftY, mirrored, zoom, side) = row
      for
        id        <- parseUuid(idText)
        photoId   <- parseUuid(photoText)
        slotId    <- parseUuid(slotText)
        transform <- parseTransform(shiftX, shiftY, mirrored, zoom)
        slot      <- side match
          case "front" => CoverSlot(CoverSlotId(slotId)).asRight[AlbumRepositoryFailure]
          case "back"  => CoverSlot(CoverSlotId(slotId)).asRight[AlbumRepositoryFailure]
          case _       => AlbumRepositoryFailure.IncompleteComposition.asLeft[CoverSlot]
      yield side -> CoverPlacement(CoverPlacementId(id), slot, photography.domain.album.PhotoId(photoId), transform)

    private def parseTransform(
        shiftX: Double,
        shiftY: Double,
        mirrored: Int,
        zoom: Double
    ): Either[AlbumRepositoryFailure, PhotoTransform] =
      for
        x <- shiftX.refineEither[Greater[-1.7976931348623157e308] & Less[1.7976931348623157e308]]
          .leftMap(_ => AlbumRepositoryFailure.IncompleteComposition)
        y <- shiftY.refineEither[Greater[-1.7976931348623157e308] & Less[1.7976931348623157e308]]
          .leftMap(_ => AlbumRepositoryFailure.IncompleteComposition)
        refinedZoom <- zoom.refineEither[Positive & Less[1.7976931348623157e308]]
          .leftMap(_ => AlbumRepositoryFailure.IncompleteComposition)
        isMirrored <- Either.cond(mirrored === 0 || mirrored === 1, mirrored === 1, AlbumRepositoryFailure.IncompleteComposition)
      yield PhotoTransform(Shift(x, y), isMirrored, refinedZoom)

    private def parseCaptionCorner(value: String): Either[AlbumRepositoryFailure, CaptionCorner] =
      value match
        case "top_left"     => CaptionCorner.TopLeft.asRight[AlbumRepositoryFailure]
        case "top_right"    => CaptionCorner.TopRight.asRight[AlbumRepositoryFailure]
        case "bottom_left"  => CaptionCorner.BottomLeft.asRight[AlbumRepositoryFailure]
        case "bottom_right" => CaptionCorner.BottomRight.asRight[AlbumRepositoryFailure]
        case _              => AlbumRepositoryFailure.IncompleteComposition.asLeft[CaptionCorner]

    private def insertSlot(spreadId: SpreadId)(slot: SpreadSlot): ConnectionIO[Unit] =
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
                  VALUES (${id.value.toString}, ${edgeValue(edge)})
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

    private def insertCoverSlot(albumId: AlbumId, side: String, slot: CoverSlot): ConnectionIO[Unit] =
      sql"""
          INSERT INTO cover_slot (id, album_id, side)
          VALUES (${slot.id.value.toString}, ${albumId.value.toString}, $side)
        """.update.run.void

    private def toTemplate(
        row: (String, String, String),
        slots: Vector[(String, Double, Double, Double, Double)]
    ): Either[AlbumRepositoryFailure, SpreadTemplate] =
      val templateSlots = slots.traverse: (kind, x, y, width, height) =>
        for
          refinedX      <- refinePosition(x)
          refinedY      <- refinePosition(y)
          refinedWidth  <- refineDistance(width)
          refinedHeight <- refineDistance(height)
          templateSlot  <- kind match
            case "photo" => SpreadTemplateSlot.Photo(SlotBox(refinedX, refinedY, refinedWidth, refinedHeight)).asRight[AlbumRepositoryFailure]
            case "text"  => SpreadTemplateSlot.Text(SlotBox(refinedX, refinedY, refinedWidth, refinedHeight)).asRight[AlbumRepositoryFailure]
            case _       => AlbumRepositoryFailure.IncompleteComposition.asLeft[SpreadTemplateSlot]
        yield templateSlot
      for
        templateId  <- parseUuid(row._1)
        refinedName <- refineTitle(row._2)
        kind        <- row._3 match
          case "album_start"   => SpreadTemplateKind.AlbumStart.asRight[AlbumRepositoryFailure]
          case "chapter_start" => SpreadTemplateKind.ChapterStart.asRight[AlbumRepositoryFailure]
          case "photos_only"   => SpreadTemplateKind.PhotosOnly.asRight[AlbumRepositoryFailure]
          case _               => AlbumRepositoryFailure.IncompleteComposition.asLeft[SpreadTemplateKind]
        parsedSlots <- templateSlots
        head        <- parsedSlots.headOption.toRight(AlbumRepositoryFailure.IncompleteComposition)
      yield SpreadTemplate(
        SpreadTemplateId(templateId),
        refinedName,
        kind,
        cats.data.NonEmptySet.of(head, parsedSlots.drop(1)*)
      )

    private def parseSpreadSlot(
        spreadId: UUID,
        bleeds: Map[String, Vector[String]]
    )(row: (String, String, Double, Double, Double, Double)): Either[AlbumRepositoryFailure, SpreadSlot] =
      val (kind, idText, x, y, width, height) = row
      for
        id            <- parseUuid(idText)
        refinedX      <- refinePosition(x)
        refinedY      <- refinePosition(y)
        refinedWidth  <- refineDistance(width)
        refinedHeight <- refineDistance(height)
        box = SlotBox(refinedX, refinedY, refinedWidth, refinedHeight)
        slot <- parseSlotKind(kind, id, spreadId, box, bleeds.getOrElse(idText, Vector.empty))
      yield slot

    private def parseSlotKind(
        kind: String,
        id: UUID,
        spreadId: UUID,
        box: SlotBox,
        bleedValues: Vector[String]
    ): Either[AlbumRepositoryFailure, SpreadSlot] =
      kind match
        case "photo" =>
          bleedValues.traverse(parseSpreadEdge)
            .map(edges => SpreadSlot.Photo(PhotoSpreadSlotId(id), SpreadId(spreadId), box, edges.toSet))
        case "text" => SpreadSlot.Text(TextSpreadSlotId(id), SpreadId(spreadId), box).asRight[AlbumRepositoryFailure]
        case _      => AlbumRepositoryFailure.IncompleteComposition.asLeft[SpreadSlot]

    private def parseSpreadEdge(value: String): Either[AlbumRepositoryFailure, SpreadEdge] =
      value match
        case "top"    => SpreadEdge.Top.asRight[AlbumRepositoryFailure]
        case "bottom" => SpreadEdge.Bottom.asRight[AlbumRepositoryFailure]
        case "left"   => SpreadEdge.Left.asRight[AlbumRepositoryFailure]
        case "right"  => SpreadEdge.Right.asRight[AlbumRepositoryFailure]
        case _        => AlbumRepositoryFailure.IncompleteComposition.asLeft[SpreadEdge]

    private def toSummary(row: (String, String, Long)): AlbumSummary =
      AlbumSummary(
        AlbumId(UUID.fromString(row._1)),
        row._2.refineUnsafe[io.github.iltotore.iron.constraint.all.Not[Empty]],
        Instant.ofEpochMilli(row._3)
      )

    private def capture[A](action: F[A]): F[Either[AlbumRepositoryFailure, A]] =
      action.attempt.map(_.leftMap(_ => AlbumRepositoryFailure.DatabaseFailure))

    private def captureResult[A](
        action: F[Either[AlbumRepositoryFailure, A]]
    ): F[Either[AlbumRepositoryFailure, A]] =
      action.attempt.map(_.leftMap(_ => AlbumRepositoryFailure.DatabaseFailure).flatMap(identity))

    private def captureTemplate(
        action: F[(Option[(String, String, String)], Vector[(String, Double, Double, Double, Double)])]
    ): F[Either[AlbumRepositoryFailure, Option[SpreadTemplate]]] =
      capture(action).map(_.flatMap: (maybeTemplate, slots) =>
        maybeTemplate.traverse(row => toTemplate(row, slots)))

    private def parseUuid(value: String): Either[AlbumRepositoryFailure, UUID] =
      Either.catchNonFatal(UUID.fromString(value)).leftMap(_ => AlbumRepositoryFailure.IncompleteComposition)

    private def refineTitle(value: String): Either[AlbumRepositoryFailure, AlbumName] =
      value.refineEither[Not[Empty]]
        .leftMap(_ => AlbumRepositoryFailure.IncompleteComposition)

    private def refinePosition(value: Double): Either[AlbumRepositoryFailure, PositionOnSpread] =
      value.refineEither[GreaterEqual[0.0] & Less[1.7976931348623157e308]]
        .leftMap(_ => AlbumRepositoryFailure.IncompleteComposition)

    private def refineDistance(value: Double): Either[AlbumRepositoryFailure, DistanceWithinSpread] =
      value.refineEither[Positive & Less[1.7976931348623157e308]]
        .leftMap(_ => AlbumRepositoryFailure.IncompleteComposition)

    private def parseSpineOwner(value: String): Either[AlbumRepositoryFailure, SpineOwner] =
      value match
        case "front" => SpineOwner.Front.asRight[AlbumRepositoryFailure]
        case "back"  => SpineOwner.Back.asRight[AlbumRepositoryFailure]
        case _       => AlbumRepositoryFailure.IncompleteComposition.asLeft[SpineOwner]

    private def spineOwnerValue(value: SpineOwner): String =
      value match
        case SpineOwner.Front => "front"
        case SpineOwner.Back  => "back"

    private def edgeValue(value: SpreadEdge): String =
      value match
        case SpreadEdge.Top    => "top"
        case SpreadEdge.Bottom => "bottom"
        case SpreadEdge.Left   => "left"
        case SpreadEdge.Right  => "right"
