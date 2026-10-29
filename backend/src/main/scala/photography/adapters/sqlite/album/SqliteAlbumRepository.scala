package photography.adapters.sqlite.album

import cats.effect.Async
import cats.data.NonEmptyList
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.Positive
import org.typelevel.doobie.{ConnectionIO, Fragment, Transactor}
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import org.sqlite.{SQLiteErrorCode, SQLiteException}
import photography.adapters.sqlite.SqliteCodecs.given
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.album.{AlbumRepository, AlbumCompositionRepository}
import photography.ports.album.AlbumRepository.*

import java.time.{Instant, ZoneOffset}
import java.time.temporal.ChronoUnit
import java.util.UUID

object SqliteAlbumRepository:
  def make[F[_]: Async: Logger](transactor: Transactor[F], composition: AlbumCompositionRepository[ConnectionIO]): AlbumRepository[F] =
    new LiveAlbumRepository(transactor, composition)

  final private class LiveAlbumRepository[F[_]: Async: Logger](transactor: Transactor[F], composition: AlbumCompositionRepository[ConnectionIO])
      extends AlbumRepository[F]:
    override def list(offset: Int, limit: Int): F[ListResult] =
      val query =
        for
          total  <- sql"SELECT count(*) FROM album".query[Long].unique
          ids    <- sql"SELECT id FROM album ORDER BY created_at_ms DESC, id DESC LIMIT $limit OFFSET $offset".query[UUID].to[Vector]
          albums <- ids.traverse(loadRequired)
        yield ListResult.Listed(AlbumPageSlice(totalAlbums = total, albums = albums)): ListResult
      execute(query, ListResult.Failed(_))

    override def create(album: Album, cover: Cover): F[CreateResult] =
      val opening   = album.opening
      val isInitial = album.chapters.isEmpty && album.opening.placements.isEmpty && cover.album.value === album.id.value
      val initial   = Either.cond(isInitial, (), new IllegalArgumentException("Expected an initial album and its cover"))
      val query     =
        for
          _ <- initial.liftTo[ConnectionIO]
          _ <- insertAlbum(album.id, album.year, album.title, album.synopsis, album.createdAt)
          _ <- insertInitialSpread(album.id, opening.id, opening.template, photos = opening.photoSlots, opening.textSlot)
          _ <- insertCover(cover)
          _ <- loadRequired(album.id.value)
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

    private def insertAlbum(id: AlbumId, year: AlbumYear, title: AlbumTitle, synopsis: String, createdAt: Instant) =
      sql"""INSERT INTO album (id, year, title, synopsis, created_at_ms)
            VALUES (${id.value}, $year, $title, $synopsis, $createdAt)""".update.run.void

    private def insertInitialSpread(
        album: AlbumId,
        spread: SpreadId,
        template: SpreadTemplateId,
        photos: Vector[SpreadSlot.Photo],
        text: SpreadSlot.Text
    ) =
      val insert = sql"""INSERT INTO spread (id, template_id, kind, album_id, position)
                        VALUES (${spread.value}, ${template.value}, 'album_start', ${album.value}, 0)"""
      for
        _ <- insert.update.run
        _ <- photos.traverse_(slot => insertSlot(spread, slot))
        _ <- insertSlot(spread, text)
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

    private def loadAlbumOpening(albumId: UUID) =
      loadSpreads(albumId, sql"s.album_id = $albumId").flatMap:
        case (opening: Spread.AlbumStart) :: Nil => opening.pure[ConnectionIO]
        case _ => new IllegalArgumentException("Album must have exactly one opening").raiseError[ConnectionIO, Spread.AlbumStart]

    private def loadChapterSpreads(albumId: UUID, chapterId: UUID) =
      loadSpreads(albumId, sql"s.chapter_id = $chapterId").flatMap:
        case (opening: Spread.ChapterStart) :: rest =>
          val photos = rest.collect { case photo: Spread.PhotosOnly => photo }.toVector
          Either.cond(photos.size === rest.size, (opening, photos), new IllegalArgumentException("Invalid chapter composition")).liftTo[ConnectionIO]
        case _ =>
          new IllegalArgumentException("Chapter must begin with its opening").raiseError[
            ConnectionIO,
            (Spread.ChapterStart, Vector[Spread.PhotosOnly])
          ]

    private def loadSpreads(albumId: UUID, owner: Fragment) =
      val select = sql"SELECT s.id FROM spread s WHERE " ++ owner ++ sql" ORDER BY s.position"
      select.query[UUID].to[List].flatMap(_.traverse(id => loadSpread(AlbumId(albumId), SpreadId(id))))

    private def loadSpread(album: AlbumId, id: SpreadId) =
      composition.findSpread(album, id).flatMap:
        case Some(located) => located.spread.pure[ConnectionIO]
        case None          => new IllegalArgumentException(s"Missing required spread ${id.value}").raiseError[ConnectionIO, Spread]

    private def load(id: UUID) =
      sql"SELECT year, title, synopsis, created_at_ms FROM album WHERE id = $id".query[(AlbumYear, AlbumTitle, String, Instant)].option.flatMap:
        case None                                     => none[Album].pure[ConnectionIO]
        case Some((year, title, synopsis, createdAt)) =>
          for
            chapters <- loadChapters(id)
            opening  <- loadAlbumOpening(id)
            _        <- validateCover(id)
          yield Album(AlbumId(id), year = year, title = title, synopsis = synopsis, createdAt = createdAt, opening, chapters = chapters).some

    private def loadRequired(id: UUID) =
      load(id).flatMap(_.toRight(new IllegalArgumentException(s"Missing required album $id")).liftTo[ConnectionIO])

    private def loadChapters(albumId: UUID) =
      val select = sql"""SELECT c.id, d.id, d.name, c.description, c.start_ms, c.end_ms, c.source_folder
                        FROM chapter c LEFT JOIN destination d ON d.id = c.destination_id
                        WHERE c.album_id = $albumId ORDER BY c.position"""
      validateChapters(albumId) >>
        select.query[(UUID, UUID, DestinationName, String, Instant, Instant, PhotoSource)].to[Vector]
          .flatMap(_.traverse(row => decodeChapter(albumId, row)))

    private def decodeChapter(albumId: UUID, row: (UUID, UUID, DestinationName, String, Instant, Instant, PhotoSource)) =
      val (id, destination, name, description, start, end, folder) = row
      for
        period    <- decodePeriod(start = start, end = end).leftMap(new IllegalArgumentException(_)).liftTo[ConnectionIO]
        loaded    <- loadChapterSpreads(albumId, id)
        countries <- loadCountries(destination)
      yield
        val (opening, photos) = loaded
        val location          = Destination(DestinationId(destination), name = name, countries = countries)
        Chapter(ChapterId(id), location, period, description = description, sourceFolder = folder, opening, spreads = photos)

    private def decodePeriod(start: Instant, end: Instant) =
      val days    = ChronoUnit.DAYS.between(start, end)
      val aligned = start.compareTo(start.truncatedTo(ChronoUnit.DAYS)) === 0 && end.compareTo(end.truncatedTo(ChronoUnit.DAYS)) === 0
      Either.cond(aligned, (), "Expected UTC calendar days").flatMap(_ => days.refineEither[Positive])
        .map(length => VisitPeriod(start.atOffset(ZoneOffset.UTC).toLocalDate, days = length))

    private def loadCountries(destination: UUID) =
      val select = sql"""SELECT k.id, k.code, k.name, k.flag FROM destination_country d
                         LEFT JOIN country k ON k.id = d.country_id WHERE d.destination_id = $destination ORDER BY d.position"""
      select.query[(UUID, CountryCode, CountryName, AbsoluteLocation)].to[List].flatMap: rows =>
        val countries = rows.map { case (id, code, name, flag) => Country(CountryId(id), code = code, name = name, flag) }
        NonEmptyList.fromList(countries).toRight(new IllegalArgumentException("Destination must have countries")).liftTo[ConnectionIO]

    private def validateChapters(albumId: UUID) =
      sql"SELECT count(*) FROM chapter WHERE album_id = $albumId AND position < 0".query[Long].unique.flatMap: count =>
        Either.cond(count === 0L, (), new IllegalArgumentException("Invalid chapter composition")).liftTo[ConnectionIO]

    private def validateCover(id: UUID) =
      val spine      = sql"SELECT spine_owner FROM cover WHERE album_id = $id".query[String].option
      val slots      = sql"SELECT side FROM cover_slot WHERE album_id = $id ORDER BY side".query[String].to[List]
      val incomplete = new IllegalArgumentException("Incomplete cover")
      for
        _ <- spine.ensure(incomplete)(_.exists(value => value === "front" || value === "back"))
        _ <- slots.ensure(incomplete)(_ === List("back", "front"))
      yield ()

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
