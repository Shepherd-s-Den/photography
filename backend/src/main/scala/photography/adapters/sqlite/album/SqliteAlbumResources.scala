package photography.adapters.sqlite.album

import cats.effect.Async
import cats.syntax.all.*
import org.typelevel.doobie.{ConnectionIO, Transactor}
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import photography.adapters.sqlite.SqliteCodecs.given
import photography.domain.album.*
import photography.domain.layout.SpreadId
import photography.ports.album.AlbumResources
import photography.ports.album.AlbumResources.*
import java.time.{Instant, ZoneOffset}
import java.time.temporal.ChronoUnit
import java.util.UUID

object SqliteAlbumResources:
  def make[F[_]: Async: Logger](sqlite: Transactor[F]): AlbumResources[F] = new LiveSqliteAlbumResources(sqlite)

  final private class LiveSqliteAlbumResources[F[_]: Async: Logger](sqlite: Transactor[F]) extends AlbumResources[F]:
    override def metadata(album: AlbumId): F[Either[Failure, Option[Metadata]]] =
      val query = sql"SELECT year, title, synopsis, created_at_ms FROM album WHERE id = ${album.value}"
        .query[(AlbumYear, AlbumTitle, String, Instant)].option
        .map(_.map { case (year, title, synopsis, createdAt) => Metadata(album, year, title, synopsis, createdAt) })
      execute(query)

    override def chapters(album: AlbumId): F[Either[Failure, Option[Vector[ChapterMetadata]]]] =
      val query = sql"""SELECT c.id, d.name, c.start_ms, c.end_ms, c.source_folder, c.description
                        FROM chapter c JOIN destination d ON d.id = c.destination_id
                        WHERE c.album_id = ${album.value} ORDER BY c.position"""
        .query[(UUID, DestinationName, Instant, Instant, PhotoSource, String)].to[Vector]
        .flatMap(_.traverse { case (id, name, start, end, folder, description) =>
          for
            _ <- validate(
              start.compareTo(start.truncatedTo(ChronoUnit.DAYS)) === 0 && end.compareTo(end.truncatedTo(ChronoUnit.DAYS)) === 0 &&
                end.isAfter(start),
              "Invalid chapter period"
            )
            countries <- sql"""SELECT dc.country_id FROM destination_country dc JOIN chapter c ON c.destination_id = dc.destination_id
                               JOIN country k ON k.id = dc.country_id WHERE c.id = $id ORDER BY dc.position""".query[UUID].to[Vector]
            count <- sql"""SELECT count(*) FROM destination_country dc JOIN chapter c ON c.destination_id = dc.destination_id
                           WHERE c.id = $id""".query[Int].unique
            _ <- validate(countries.nonEmpty && countries.size === count, "Chapter must have valid countries")
          yield ChapterMetadata(
            ChapterId(id),
            name,
            countries.map(CountryId(_)),
            start.atOffset(ZoneOffset.UTC).toLocalDate,
            end.atOffset(ZoneOffset.UTC).toLocalDate.minusDays(1),
            folder,
            description
          )
        })
      val checked =
        for
          negative <- sql"SELECT count(*) FROM chapter WHERE album_id = ${album.value} AND position < 0".query[Int].unique
          _        <- validate(negative === 0, "Invalid chapter order")
          metadata <- query
          total    <- sql"SELECT count(*) FROM chapter WHERE album_id = ${album.value}".query[Int].unique
          _        <- validate(metadata.size === total, "Missing chapter destination")
        yield metadata
      execute(ifExists(album, checked))

    override def composition(album: AlbumId): F[Either[Failure, Option[Vector[Member]]]] =
      val query = sql"""SELECT s.id, s.chapter_id, s.kind, s.position FROM spread s LEFT JOIN chapter c ON c.id = s.chapter_id
                        WHERE s.album_id = ${album.value} OR c.album_id = ${album.value}
                        ORDER BY CASE WHEN s.album_id IS NOT NULL THEN 0 ELSE 1 END, c.position, s.position"""
        .query[(UUID, Option[UUID], String, Int)].to[Vector]
      val checked =
        for
          rows     <- query
          chapters <- sql"SELECT id FROM chapter WHERE album_id = ${album.value}".query[UUID].to[Vector]
          openings        = rows.collect { case (_, None, "album_start", 0) => () }
          chapterOpenings = rows.collect { case (_, Some(chapter), "chapter_start", 0) => chapter }
          valid           = rows.forall {
            case (_, None, "album_start", 0)           => true
            case (_, Some(_), "chapter_start", 0)      => true
            case (_, Some(_), "photos_only", position) => position > 0
            case _                                     => false
          }
          contiguous = rows.collect { case row @ (_, Some(_), _, _) => row }.groupBy { case (_, chapter, _, _) => chapter }
            .values.forall(_.zipWithIndex.forall { case ((_, _, _, position), index) => position === index })
          _ <- validate(openings.size === 1 && valid && contiguous && chapterOpenings.toSet === chapters.toSet, "Incomplete album composition")
        yield rows.map { case (id, chapter, _, _) => Member(SpreadId(id), chapter.map(ChapterId(_))) }
      execute(ifExists(album, checked))

    private def validate(valid: Boolean, message: String) =
      Either.cond(valid, (), new IllegalArgumentException(message)).liftTo[ConnectionIO]

    private def ifExists[A](album: AlbumId, query: ConnectionIO[A]) =
      sql"SELECT EXISTS(SELECT 1 FROM album WHERE id = ${album.value})".query[Boolean].unique.flatMap:
        case true  => query.map(_.some)
        case false => none[A].pure[ConnectionIO]

    private def execute[A](query: ConnectionIO[A]): F[Either[Failure, A]] =
      query.transact(sqlite).map(_.asRight[Failure]).handleErrorWith: error =>
        Logger[F].error(error)("Album resource read failed").as(Failure.Unavailable.asLeft[A])
