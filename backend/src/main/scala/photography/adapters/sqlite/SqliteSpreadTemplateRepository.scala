package photography.adapters.sqlite

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all.*
import io.github.iltotore.iron.*
import org.typelevel.doobie.{ConnectionIO, Meta, Transactor}
import org.typelevel.doobie.implicits.*
import org.typelevel.doobie.util.invariant.InvariantViolation
import org.typelevel.log4cats.Logger
import photography.adapters.sqlite.SqliteCodecs.given
import photography.domain.layout.*
import photography.ports.SpreadTemplateRepository
import photography.ports.SpreadTemplateRepository.*

import java.util.UUID

object SqliteSpreadTemplateRepository:
  def make[F[_]: Async: Logger](transactor: Transactor[F]): SpreadTemplateRepository[F] =
    new LiveSpreadTemplateRepository(transactor)

  final private class LiveSpreadTemplateRepository[F[_]: Async: Logger](transactor: Transactor[F]) extends SpreadTemplateRepository[F]:
    override def listAlbumStartTemplates: F[ListAlbumStartTemplatesResult] =
      loadAlbumStarts.transact(transactor).attempt.flatMap:
        case Right(templates) => ListAlbumStartTemplatesResult.Listed(templates).pure[F]
        case Left(error)      =>
          val reason = error match
            case _: IllegalArgumentException | _: InvariantViolation => Failure.InvalidSpreadData
            case _                                                   => Failure.DatabaseFailure
          Logger[F].error(error)("Could not list AlbumStart templates").as(ListAlbumStartTemplatesResult.Failed(reason))

    private def loadAlbumStarts =
      for
        rows <- sql"SELECT id, name, kind FROM spread_template WHERE kind = 'album_start' ORDER BY id"
          .query[(UUID, SpreadTemplateName, SpreadTemplateKind)].to[List]
        templates <- rows.traverse { (id, name, kind) =>
          loadSlots(id).map(slots => SpreadTemplate(SpreadTemplateId(id), name, kind, slots))
        }
        nonEmpty <- NonEmptyList.fromList(templates).toRight(new IllegalArgumentException("No AlbumStart templates")).liftTo[ConnectionIO]
      yield nonEmpty

    private def loadSlots(id: UUID) =
      for
        orderedRows <- sql"SELECT kind, x, y, width, height FROM spread_template_slot WHERE template_id = $id ORDER BY id"
          .query[(String, SlotBox)].to[List]
        slots <- orderedRows.traverse {
          case ("photo", box) => SpreadTemplateSlot.Photo(box).asRight[IllegalArgumentException]
          case ("text", box)  => SpreadTemplateSlot.Text(box).asRight[IllegalArgumentException]
          case (other, _)     => new IllegalArgumentException(s"Unknown slot kind $other").asLeft[SpreadTemplateSlot]
        }.liftTo[ConnectionIO]
        nonEmpty <- NonEmptyList.fromList(slots).toRight(new IllegalArgumentException(s"Template $id has no slots")).liftTo[ConnectionIO]
      yield nonEmpty

    private given Meta[SpreadTemplateName] =
      Meta[String].tiemap[SpreadTemplateName](_.refineEither)(value => value: String)
