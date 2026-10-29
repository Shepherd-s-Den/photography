package photography.adapters.sqlite

import cats.effect.Async
import cats.syntax.all.*
import org.typelevel.doobie.{ConnectionIO, Transactor}
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import org.sqlite.{SQLiteErrorCode, SQLiteException}
import photography.adapters.sqlite.SqliteCodecs.given
import photography.domain.album.*
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
          albums <- ids.traverse(SqliteAlbumStore.loadRequired)
        yield ListResult.Listed(AlbumPageSlice(totalAlbums = total, albums)): ListResult
      execute(query, ListResult.Failed(_))

    override def create(album: Album, cover: Cover): F[CreateResult] =
      val isInitial = album.chapters.isEmpty && album.opening.placements.isEmpty && cover.album.value === album.id.value
      val initial   = Either.cond(isInitial, (), new IllegalArgumentException("Expected an initial album and its cover"))
      val query     =
        for
          _ <- initial.liftTo[ConnectionIO]
          _ <- insertAlbum(album.id, album.year, album.title, album.synopsis, album.createdAt)
          _ <- insertInitialSpread(album.id, album.opening.id, album.opening.template, photos = album.opening.photoSlots, album.opening.textSlot)
          _ <- insertCover(cover)
          _ <- SqliteAlbumStore.loadRequired(album.id.value)
        yield CreateResult.Created: CreateResult
      execute(query, CreateResult.Failed(_))

    override def get(id: AlbumId): F[GetResult] =
      execute(SqliteAlbumStore.load(id.value).map(_.fold[GetResult](GetResult.NotFound)(GetResult.Found(_))), GetResult.Failed(_))

    override def rename(id: AlbumId, title: AlbumTitle): F[RenameResult] =
      val update = sql"UPDATE album SET title = $title WHERE id = ${id.value}"
      val query  = SqliteAlbumStore.load(id.value).flatMap:
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
        _ <- photos.traverse_(slot => SqliteAlbumStore.insertSlot(spread, slot))
        _ <- SqliteAlbumStore.insertSlot(spread, text)
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
