package photography.adapters.sqlite

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all.*
import org.typelevel.doobie.{ConnectionIO, Transactor}
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import photography.adapters.sqlite.SqliteCodecs.given
import photography.capabilities.IdGenerator
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.ChapterRepository
import photography.ports.ChapterRepository.*

import java.time.ZoneOffset
import java.util.UUID

object SqliteChapterRepository:
  def make[F[_]: Async: Logger: IdGenerator](transactor: Transactor[F]): ChapterRepository[F] =
    new LiveChapterRepository(transactor)

  final private class LiveChapterRepository[F[_]: Async: Logger: IdGenerator](transactor: Transactor[F]) extends ChapterRepository[F]:
    override def create(album: AlbumId, chapter: Chapter, order: NonEmptyList[ChapterId]): F[CreateResult] =
      val query = albumExists(album).flatMap:
        case false => CreateResult.AlbumNotFound.pure[ConnectionIO]
        case true  =>
          for
            _ <- validateInitial(chapter).liftTo[ConnectionIO]
            _ <- insertDestination(chapter.destination.id.value, chapter.destination.name, chapter.destination.countries)
            _ <- insertChapter(album, chapter)
            _ <- insertOpening(chapter.id, chapter.opening)
            _ <- persistOrder(album, order)
            _ <- validateComposition(album)
          yield CreateResult.Saved: CreateResult
      execute(query.transact(transactor), CreateResult.Failed)

    override def update(
        album: AlbumId,
        chapter: ChapterId,
        name: DestinationName,
        countries: NonEmptyList[Country],
        period: VisitPeriod,
        sourceFolder: SourceFolder,
        order: NonEmptyList[ChapterId]
    ): F[UpdateResult] =
      val operation = IdGenerator[F].generateId.flatMap: destination =>
        val query = albumExists(album).flatMap:
          case false => UpdateResult.AlbumNotFound.pure[ConnectionIO]
          case true  =>
            sql"SELECT destination_id FROM chapter WHERE id = ${chapter.value} AND album_id = ${album.value}".query[UUID].option.flatMap:
              case None           => UpdateResult.ChapterNotFound.pure[ConnectionIO]
              case Some(previous) =>
                for
                  _ <- validateComposition(album)
                  _ <- insertDestination(destination, name, countries)
                  _ <- updateMetadata(album, chapter, destination, name, period, sourceFolder)
                  _ <- deleteUnreferencedDestination(previous)
                  _ <- persistOrder(album, order)
                  _ <- validateComposition(album)
                yield UpdateResult.Saved: UpdateResult
        query.transact(transactor)
      execute(operation, UpdateResult.Failed)

    private def albumExists(album: AlbumId) =
      sql"SELECT EXISTS(SELECT 1 FROM album WHERE id = ${album.value})".query[Boolean].unique

    private def validateInitial(chapter: Chapter) =
      Either.cond(
        chapter.spreads.isEmpty && chapter.opening.placements.isEmpty,
        (),
        new IllegalArgumentException("Expected an empty chapter opening")
      )

    private def deleteUnreferencedDestination(id: UUID) =
      sql"DELETE FROM destination WHERE id = $id AND NOT EXISTS (SELECT 1 FROM chapter WHERE destination_id = $id)".update.run.void

    private def insertDestination(id: UUID, name: DestinationName, countries: NonEmptyList[Country]) =
      val insertCountries = countries.toList.zipWithIndex.traverse_ { case (country, position) =>
        val insert = sql"""INSERT INTO destination_country (destination_id, country_id, position)
          VALUES ($id, ${country.id.value}, $position)"""
        insert.update.run.flatMap(requireOne)
      }
      for
        _ <- sql"INSERT INTO destination (id, name) VALUES ($id, ${name: String})".update.run.flatMap(requireOne)
        _ <- insertCountries
      yield ()

    private def insertChapter(album: AlbumId, chapter: Chapter) =
      val start  = chapter.period.firstDay.atStartOfDay(ZoneOffset.UTC).toInstant.toEpochMilli
      val end    = chapter.period.firstDay.plusDays(chapter.period.days).atStartOfDay(ZoneOffset.UTC).toInstant.toEpochMilli
      val home   = chapter.destination.isHome
      val insert = sql"""INSERT INTO chapter (id, album_id, destination_id, description, start_ms, end_ms, position, source_folder, is_home)
        VALUES (${chapter.id.value}, ${album.value}, ${chapter.destination.id.value}, ${chapter.description},
                $start, $end, (SELECT coalesce(max(position), -1) + 1 FROM chapter WHERE album_id = ${album.value}),
                ${chapter.sourceFolder: String}, $home)"""
      insert.update.run.flatMap(requireOne)

    private def updateMetadata(
        album: AlbumId,
        chapter: ChapterId,
        destination: UUID,
        name: DestinationName,
        period: VisitPeriod,
        folder: SourceFolder
    ) =
      val start = period.firstDay.atStartOfDay(ZoneOffset.UTC).toInstant.toEpochMilli
      val end   = period.firstDay.plusDays(period.days).atStartOfDay(ZoneOffset.UTC).toInstant.toEpochMilli
      val home  = (name: String) === "Home"
      sql"""UPDATE chapter SET destination_id = $destination, start_ms = $start, end_ms = $end, source_folder = ${folder: String}, is_home = $home
            WHERE id = ${chapter.value} AND album_id = ${album.value}""".update.run.flatMap(requireOne)

    private def persistOrder(album: AlbumId, order: NonEmptyList[ChapterId]) =
      val supplied       = order.toList.map(_.value)
      val writePositions = supplied.zipWithIndex.traverse_ { case (id, position) =>
        sql"UPDATE chapter SET position = $position WHERE album_id = ${album.value} AND id = $id".update.run.flatMap(requireOne)
      }
      for
        stored <- sql"SELECT id FROM chapter WHERE album_id = ${album.value}".query[UUID].to[List]
        _      <- validateOrder(stored, supplied).liftTo[ConnectionIO]
        _      <- sql"UPDATE chapter SET position = -position - 1 WHERE album_id = ${album.value}".update.run
        _      <- writePositions
      yield ()

    private def validateOrder(stored: List[UUID], supplied: List[UUID]) =
      Either.cond(
        stored.size === supplied.size && stored.toSet === supplied.toSet,
        (),
        new IllegalArgumentException("Order must contain every chapter exactly once")
      )

    private def insertOpening(chapter: ChapterId, opening: Spread.ChapterStart) =
      val insert = sql"""INSERT INTO spread (id, template_id, kind, chapter_id, position)
        VALUES (${opening.id.value}, ${opening.template.value}, 'chapter_start', ${chapter.value}, 0)"""
      val insertText = sql"""INSERT INTO spread_text_slot (id, spread_id, x, y, width, height)
        VALUES (${opening.textSlot.id.value}, ${opening.id.value}, ${opening.textSlot.box})"""
      for
        _ <- insert.update.run.flatMap(requireOne)
        _ <- opening.photoSlots.traverse_(slot => insertPhotoSlot(opening.id, slot))
        _ <- insertText.update.run.flatMap(requireOne)
      yield ()

    private def insertPhotoSlot(spread: SpreadId, slot: SpreadSlot.Photo) =
      val insert = sql"INSERT INTO spread_photo_slot (id, spread_id, x, y, width, height) VALUES (${slot.id.value}, ${spread.value}, ${slot.box})"
      val insertBleed = slot.bleed.toList.traverse_ { edge =>
        sql"INSERT INTO spread_photo_slot_bleed (slot_id, edge) VALUES (${slot.id.value}, $edge)".update.run.flatMap(requireOne)
      }
      for
        _ <- insert.update.run.flatMap(requireOne)
        _ <- insertBleed
      yield ()

    private def validateComposition(album: AlbumId) =
      sql"SELECT count(*) FROM invalid_album_composition WHERE album_id = ${album.value}".query[Long].unique.flatMap: invalid =>
        Either.cond(invalid === 0L, (), new IllegalArgumentException("Incomplete stored composition")).liftTo[ConnectionIO]

    private def requireOne(count: Int) =
      Either.cond(count === 1, (), new IllegalStateException("Expected one persisted row")).liftTo[ConnectionIO]

    private def execute[A](operation: F[A], failed: A) =
      operation.attempt.flatMap:
        case Right(result) => result.pure[F]
        case Left(error)   => Logger[F].error(error)("SQLite chapter operation failed").as(failed)
