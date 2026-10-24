package photography.adapters.sqlite

import cats.effect.Async
import cats.syntax.all.*
import io.github.iltotore.iron.*
import org.typelevel.doobie.{ConnectionIO, Transactor}
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import photography.domain.album.{Album, AlbumId, AlbumName}
import photography.domain.layout.{Cover, SpineOwner, Spread}
import photography.ports.AlbumRepository
import photography.ports.AlbumRepository.*

import java.time.Instant

object SqliteAlbumRepository:
  def make[F[_]: Async: Logger](transactor: Transactor[F]): AlbumRepository[F] =
    new LiveAlbumRepository(transactor)

  final private class LiveAlbumRepository[F[_]: Async: Logger](transactor: Transactor[F]) extends AlbumRepository[F]:
    override def list(offset: Int, limit: Int): F[ListResult] =
      val query =
        for
          total  <- sql"SELECT count(*) FROM album".query[Long].unique
          ids    <- sql"SELECT id FROM album ORDER BY created_at_ms DESC, id DESC LIMIT $limit OFFSET $offset".query[String].to[Vector]
          albums <- ids.traverse(loadRequired)
        yield ListResult.Listed(AlbumPageSlice(total, albums)): ListResult
      execute(query, ListResult.Failed(_))

    override def create(album: Album, cover: Cover): F[CreateResult] =
      val query =
        for
          start <- validateInitial(album, cover)
          _     <- insertAlbum(album)
          _     <- SqliteSpreadRecords.insertInitial(album.id, start)
          _     <- insertCover(cover)
          _     <- loadRequired(album.id.value.toString)
        yield CreateResult.Created: CreateResult
      execute(query, CreateResult.Failed(_))

    override def get(id: AlbumId): F[GetResult] =
      execute(load(id.value.toString).map(_.fold[GetResult](GetResult.NotFound)(GetResult.Found(_))), GetResult.Failed(_))

    override def rename(id: AlbumId, title: AlbumName): F[RenameResult] =
      val query = load(id.value.toString).flatMap:
        case None        => (RenameResult.NotFound: RenameResult).pure[ConnectionIO]
        case Some(album) =>
          sql"UPDATE album SET title = ${title: String} WHERE id = ${id.value.toString}".update.run
            .as(RenameResult.Renamed(album.copy(title = title)): RenameResult)
      execute(query, RenameResult.Failed(_))

    override def delete(id: AlbumId): F[DeleteResult] =
      val query = load(id.value.toString).flatMap:
        case None    => (DeleteResult.NotFound: DeleteResult).pure[ConnectionIO]
        case Some(_) => sql"DELETE FROM album WHERE id = ${id.value.toString}".update.run.as(DeleteResult.Deleted: DeleteResult)
      execute(query, DeleteResult.Failed(_))

    private def load(id: String) =
      sql"SELECT title, synopsis, created_at_ms FROM album WHERE id = $id".query[(String, String, Long)].option.flatMap:
        case None                               => Option.empty[Album].pure[ConnectionIO]
        case Some((title, synopsis, createdAt)) =>
          for
            albumId    <- SqliteDecoding.requireData(SqliteDecoding.uuid(id).map(AlbumId(_)))
            validTitle <- SqliteDecoding.requireData(title.refineEither: Either[String, AlbumName])
            spreads    <- SqliteSpreadRecords.load(albumId)
            _          <- validateCover(id)
          yield Album(albumId, validTitle, synopsis, Instant.ofEpochMilli(createdAt), spreads).some

    private def loadRequired(id: String) =
      load(id).flatMap(maybeAlbum => SqliteDecoding.requireData(maybeAlbum.toRight(s"Missing listed album $id")))

    private def validateCover(id: String) =
      for
        maybeSpine <- sql"SELECT spine_owner FROM cover WHERE album_id = $id".query[String].option
        slots      <- sql"SELECT id, side FROM cover_slot WHERE album_id = $id ORDER BY side".query[(String, String)].to[List]
        _          <- SqliteDecoding.requireData(slots.traverse(slot => SqliteDecoding.uuid(slot._1)))
        validSpine = maybeSpine.exists(value => value === "front" || value === "back")
        _ <- SqliteDecoding.requireData(Either.cond(validSpine && slots.map(_._2) === List("back", "front"), (), "Incomplete cover"))
      yield ()

    private def validateInitial(album: Album, cover: Cover) =
      val maybeStart = album.spreads.toList match
        case (start: Spread.AlbumStart) :: Nil if start.album.value === album.id.value && start.placements.isEmpty => start.some
        case _                                                                                                     => None
      for
        start <- SqliteDecoding.requireData(maybeStart.toRight("New album must contain one empty AlbumStart"))
        _     <- SqliteDecoding.requireData(Either.cond(cover.album.value === album.id.value, (), "Cover belongs to a different album"))
      yield start

    private def insertAlbum(album: Album) =
      sql"""INSERT INTO album (id, title, synopsis, created_at_ms)
            VALUES (${album.id.value.toString}, ${album.title: String}, ${album.synopsis}, ${album.createdAt.toEpochMilli})""".update.run.void

    private def insertCover(cover: Cover) =
      val albumId = cover.album.value.toString
      val spine   = cover.spineOwner match
        case SpineOwner.Front => "front"
        case SpineOwner.Back  => "back"
      for
        _ <- sql"INSERT INTO cover (album_id, spine_owner) VALUES ($albumId, $spine)".update.run
        _ <- sql"INSERT INTO cover_slot (id, album_id, side) VALUES (${cover.front.id.value.toString}, $albumId, 'front')".update.run
        _ <- sql"INSERT INTO cover_slot (id, album_id, side) VALUES (${cover.back.id.value.toString}, $albumId, 'back')".update.run
      yield ()

    private def execute[A](query: ConnectionIO[A], failed: Failure => A): F[A] =
      query.transact(transactor).attempt.flatMap:
        case Right(result) => result.pure[F]
        case Left(error)   =>
          val reason = error match
            case _: InvalidSqliteData => Failure.InvalidAlbumData
            case _                    => Failure.DatabaseFailure
          Logger[F].error(error)("SQLite album operation failed").as(failed(reason))
