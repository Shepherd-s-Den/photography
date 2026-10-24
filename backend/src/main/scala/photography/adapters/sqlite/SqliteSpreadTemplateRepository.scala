package photography.adapters.sqlite

import cats.effect.Async
import cats.syntax.all.*
import org.typelevel.doobie.Transactor
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import photography.ports.SpreadTemplateRepository
import photography.ports.SpreadTemplateRepository.*

object SqliteSpreadTemplateRepository:
  def make[F[_]: Async: Logger](transactor: Transactor[F]): SpreadTemplateRepository[F] =
    new LiveSpreadTemplateRepository(transactor)

  final private class LiveSpreadTemplateRepository[F[_]: Async: Logger](transactor: Transactor[F]) extends SpreadTemplateRepository[F]:
    override def listAlbumStartTemplates: F[ListAlbumStartTemplatesResult] =
      SqliteTemplateRecords.albumStarts.transact(transactor).attempt.flatMap:
        case Right(templates) => ListAlbumStartTemplatesResult.Listed(templates).pure[F]
        case Left(error)      =>
          val reason = error match
            case _: InvalidSqliteData => Failure.InvalidSpreadData
            case _                    => Failure.DatabaseFailure
          Logger[F].error(error)("Could not list AlbumStart templates").as(ListAlbumStartTemplatesResult.Failed(reason))
