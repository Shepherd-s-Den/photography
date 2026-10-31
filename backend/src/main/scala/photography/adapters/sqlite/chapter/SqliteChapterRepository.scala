package photography.adapters.sqlite.chapter

import cats.data.NonEmptyList
import cats.syntax.all.*
import io.github.iltotore.iron.*
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.free.connection
import org.typelevel.doobie.implicits.*
import photography.adapters.sqlite.SqliteCodecs.given
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.chapter.ChapterRepository
import photography.ports.chapter.ChapterRepository.*

import java.time.ZoneOffset
import java.util.UUID

object SqliteChapterRepository:
  def make: ChapterRepository.Metadata[ConnectionIO] = new LiveChapterRepository

  final private class LiveChapterRepository extends ChapterRepository.Metadata[ConnectionIO]:
    override def saveDescription(album: AlbumId, chapter: ChapterId, text: String): ConnectionIO[SaveDescriptionResult] =
      albumExists(album).attempt.flatMap:
        case Right(false) => SaveDescriptionResult.AlbumNotFound.pure[ConnectionIO]
        case Right(true)  =>
          writeDescription(album, chapter, text = text).map:
            case Right(1)    => SaveDescriptionResult.Saved
            case Right(_)    => SaveDescriptionResult.ChapterNotFound
            case Left(cause) => SaveDescriptionResult.Failed(cause)
        case Left(cause) => SaveDescriptionResult.Failed(cause).pure[ConnectionIO]

    override def findSource(chapter: ChapterId): ConnectionIO[SourceResult] =
      sql"SELECT album_id, source_folder FROM chapter WHERE id = ${chapter.value}".query[(UUID, String)].option.flatMap:
        case Some((album, source)) =>
          source.refineEither[PhotoSourceConstraint].leftMap(new IllegalArgumentException(_)).liftTo[ConnectionIO]
            .map(SourceResult.Found(AlbumId(album), _))
        case None => SourceResult.ChapterNotFound.pure[ConnectionIO]

    override def create(album: AlbumId, chapter: NewChapter, order: NonEmptyList[ChapterId]): ConnectionIO[CreateResult] =
      val opening         = chapter.opening
      val distinctSlotIds = opening.photoSlots.forall(_.id.value =!= opening.textSlot.id.value)
      val isInitial       = opening.placements.isEmpty && opening.photoSlots.nonEmpty && distinctSlotIds
      val initial         = Either.cond(isInitial, (), new IllegalArgumentException("Expected an empty chapter opening with photo slots"))
      val write           =
        for
          _ <- initial.liftTo[ConnectionIO]
          _ <- validateOpening(opening.template, photos = opening.photoSlots.map(_.box), opening.textSlot.box)
          _ <- insertDestination(chapter.destinationId.value, chapter.name, countries = chapter.countries)
          _ <- insertChapter(album, chapter.id, chapter.destinationId, description = chapter.description, chapter.period, chapter.sourceFolder)
          _ <- insertOpening(chapter.id, opening.id, opening.template, photos = opening.photoSlots, opening.textSlot)
          _ <- persistOrder(album, order = order)
        yield CreateResult.Saved: CreateResult
      albumExists(album).flatMap:
        case false => CreateResult.AlbumNotFound.pure[ConnectionIO]
        case true  =>
          countryReferencesExist(countries = chapter.countries).ifM(ifTrue = write, ifFalse = CreateResult.UnknownCountries.pure[ConnectionIO])

    override def update(command: Update): ConnectionIO[UpdateResult] = command match
      case Update(album, chapter, destination, name, countries, period, sourceFolder, order) =>
        albumExists(album).flatMap:
          case false => UpdateResult.AlbumNotFound.pure[ConnectionIO]
          case true  =>
            sql"SELECT destination_id FROM chapter WHERE id = ${chapter.value} AND album_id = ${album.value}".query[UUID].option.flatMap:
              case None           => UpdateResult.ChapterNotFound.pure[ConnectionIO]
              case Some(previous) =>
                val write =
                  for
                    _ <- insertDestination(destination.value, name, countries = countries)
                    _ <- updateMetadata(album, chapter, destination.value, period, sourceFolder)
                    _ <- deleteUnreferencedDestination(previous)
                    _ <- persistOrder(album, order = order)
                  yield UpdateResult.Saved: UpdateResult
                countryReferencesExist(countries = countries).ifM(ifTrue = write, ifFalse = UpdateResult.UnknownCountries.pure[ConnectionIO])

    private def writeDescription(album: AlbumId, chapter: ChapterId, text: String) =
      val update = sql"UPDATE chapter SET description = $text WHERE id = ${chapter.value} AND album_id = ${album.value}".update.run
        .flatMap(rows => checkDescriptionWrite(album, chapter, rows = rows))
      sql"SAVEPOINT chapter_description".update.run >> update.attempt.flatTap:
        case Left(cause) =>
          val rollback = sql"ROLLBACK TO chapter_description".update.run >> sql"RELEASE chapter_description".update.run.void
          rollback.handleErrorWith(error => connection.delay(cause.addSuppressed(error)) >> cause.raiseError[ConnectionIO, Unit])
        case Right(_) => sql"RELEASE chapter_description".update.run.void

    private def checkDescriptionWrite(album: AlbumId, chapter: ChapterId, rows: Int) =
      if rows === 1 then rows.pure[ConnectionIO]
      else
        sql"SELECT EXISTS(SELECT 1 FROM chapter WHERE id = ${chapter.value} AND album_id = ${album.value})".query[Boolean].unique
          .ensure(new IllegalStateException("Description update did not change the existing chapter"))(!_).as(0)

    private def albumExists(album: AlbumId) =
      sql"SELECT EXISTS(SELECT 1 FROM album WHERE id = ${album.value})".query[Boolean].unique

    private def countryReferencesExist(countries: NonEmptyList[CountryId]) =
      countries.traverse(id => sql"SELECT EXISTS(SELECT 1 FROM country WHERE id = ${id.value})".query[Boolean].unique).map(_.forall(identity))

    private def validateOpening(template: SpreadTemplateId, photos: Vector[SlotBox], text: SlotBox) =
      val select = sql"""SELECT s.kind, s.x, s.y, s.width, s.height FROM spread_template_slot s
        JOIN spread_template t ON t.id = s.template_id WHERE t.id = ${template.value} AND t.kind = 'chapter_start'"""
      val supplied = (photos.map(box => ("photo", box)) :+ ("text", text)).map { case (kind, box) =>
        (kind, box.x: Double, box.y: Double, box.width: Double, box.height: Double)
      }
      select.query[(String, Double, Double, Double, Double)].to[Vector].flatMap: expected =>
        val matchesTemplate = expected.size === supplied.size && expected.toSet === supplied.toSet
        Either.cond(matchesTemplate, (), new IllegalArgumentException("Opening slots must represent their chapter template")).liftTo[ConnectionIO]

    private def deleteUnreferencedDestination(id: UUID) =
      sql"DELETE FROM destination WHERE id = $id AND NOT EXISTS (SELECT 1 FROM chapter WHERE destination_id = $id)".update.run.void

    private def insertDestination(id: UUID, name: DestinationName, countries: NonEmptyList[CountryId]) =
      val insert          = sql"INSERT INTO destination (id, name) VALUES ($id, ${name: String})"
      val insertCountries = countries.toList.zipWithIndex.traverse_ { case (country, position) =>
        val insert = sql"""INSERT INTO destination_country (destination_id, country_id, position)
          VALUES ($id, ${country.value}, $position)"""
        insert.update.run.ensure(new IllegalStateException("Expected one persisted row"))(_ === 1).void
      }
      for
        _ <- insert.update.run.ensure(new IllegalStateException("Expected one persisted row"))(_ === 1)
        _ <- insertCountries
      yield ()

    private def insertChapter(
        album: AlbumId,
        chapter: ChapterId,
        destination: DestinationId,
        description: String,
        period: VisitPeriod,
        folder: PhotoSource
    ) =
      val start  = period.firstDay.atStartOfDay(ZoneOffset.UTC).toInstant.toEpochMilli
      val end    = period.firstDay.plusDays(period.days).atStartOfDay(ZoneOffset.UTC).toInstant.toEpochMilli
      val insert = sql"""INSERT INTO chapter (id, album_id, destination_id, description, start_ms, end_ms, position, source_folder)
        VALUES (${chapter.value}, ${album.value}, ${destination.value}, $description,
                $start, $end, (SELECT coalesce(max(position), -1) + 1 FROM chapter WHERE album_id = ${album.value}),
                ${folder: String})"""
      insert.update.run.ensure(new IllegalStateException("Expected one persisted row"))(_ === 1).void

    private def updateMetadata(album: AlbumId, chapter: ChapterId, destination: UUID, period: VisitPeriod, folder: PhotoSource) =
      val start  = period.firstDay.atStartOfDay(ZoneOffset.UTC).toInstant.toEpochMilli
      val end    = period.firstDay.plusDays(period.days).atStartOfDay(ZoneOffset.UTC).toInstant.toEpochMilli
      val update = sql"""UPDATE chapter SET destination_id = $destination, start_ms = $start, end_ms = $end, source_folder = ${folder: String}
            WHERE id = ${chapter.value} AND album_id = ${album.value}"""
      update.update.run.ensure(new IllegalStateException("Expected one persisted row"))(_ === 1).void

    private def persistOrder(album: AlbumId, order: NonEmptyList[ChapterId]) =
      val supplied       = order.toList.map(_.value)
      val writePositions = supplied.zipWithIndex.traverse_ { case (id, position) =>
        val update = sql"UPDATE chapter SET position = $position WHERE album_id = ${album.value} AND id = $id"
        update.update.run.ensure(new IllegalStateException("Expected one persisted row"))(_ === 1).void
      }
      for
        stored <- sql"SELECT id FROM chapter WHERE album_id = ${album.value}".query[UUID].to[List]
        _      <- validateOrder(stored = stored, supplied = supplied).liftTo[ConnectionIO]
        _      <- sql"UPDATE chapter SET position = -position - 1 WHERE album_id = ${album.value}".update.run
        _      <- writePositions
      yield ()

    private def validateOrder(stored: List[UUID], supplied: List[UUID]) =
      val containsEveryChapter = stored.size === supplied.size && stored.toSet === supplied.toSet
      Either.cond(containsEveryChapter, (), new IllegalArgumentException("Order must contain every chapter exactly once"))

    private def insertOpening(
        chapter: ChapterId,
        spread: SpreadId,
        template: SpreadTemplateId,
        photos: Vector[SpreadSlot.Photo],
        text: SpreadSlot.Text
    ) =
      val insert = sql"""INSERT INTO spread (id, template_id, kind, chapter_id, position)
        VALUES (${spread.value}, ${template.value}, 'chapter_start', ${chapter.value}, 0)"""
      val insertText = sql"""INSERT INTO spread_text_slot (id, spread_id, x, y, width, height)
        VALUES (${text.id.value}, ${spread.value}, ${text.box})"""
      for
        _ <- insert.update.run.ensure(new IllegalStateException("Expected one persisted row"))(_ === 1)
        _ <- photos.traverse_(slot => insertPhotoSlot(spread, slot))
        _ <- insertText.update.run.ensure(new IllegalStateException("Expected one persisted row"))(_ === 1)
      yield ()

    private def insertPhotoSlot(spread: SpreadId, slot: SpreadSlot.Photo) =
      val insert = sql"INSERT INTO spread_photo_slot (id, spread_id, x, y, width, height) VALUES (${slot.id.value}, ${spread.value}, ${slot.box})"
      val insertBleed = slot.bleed.toList.traverse_ { edge =>
        val insert = sql"INSERT INTO spread_photo_slot_bleed (slot_id, edge) VALUES (${slot.id.value}, $edge)"
        insert.update.run.ensure(new IllegalStateException("Expected one persisted row"))(_ === 1).void
      }
      for
        _ <- insert.update.run.ensure(new IllegalStateException("Expected one persisted row"))(_ === 1)
        _ <- insertBleed
      yield ()
