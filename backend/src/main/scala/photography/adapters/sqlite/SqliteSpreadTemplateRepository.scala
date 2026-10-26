package photography.adapters.sqlite

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all.*
import io.github.iltotore.iron.*
import org.typelevel.doobie.{ConnectionIO, Meta, Transactor}
import org.typelevel.doobie.implicits.*
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
      loadOpenings(kind = "album_start").transact(transactor).attempt.flatMap:
        case Right(rows) =>
          val templates = rows.map { case (id, name, photos, text) => SpreadTemplate.AlbumStart(id, name, photos, text) }
          ListAlbumStartTemplatesResult.Listed(templates).pure[F]
        case Left(error) =>
          Logger[F].error(error)("Could not list album opening templates").as(ListAlbumStartTemplatesResult.Failed(Failure.DatabaseFailure))

    override def listChapterStartTemplates: F[ListChapterStartTemplatesResult] =
      loadOpenings(kind = "chapter_start").transact(transactor).attempt.flatMap:
        case Right(rows) =>
          val templates = rows.map { case (id, name, photos, text) => SpreadTemplate.ChapterStart(id, name, photos, text) }
          ListChapterStartTemplatesResult.Listed(templates).pure[F]
        case Left(error) =>
          Logger[F].error(error)("Could not list chapter opening templates").as(ListChapterStartTemplatesResult.Failed(Failure.DatabaseFailure))

    private def loadOpenings(kind: String) =
      val select = sql"SELECT id, name FROM spread_template WHERE kind = $kind ORDER BY id"
      for
        rows      <- select.query[(UUID, SpreadTemplateName)].to[List]
        templates <- rows.traverse { case (id, name) => loadSlots(id).map { case (photos, text) => (SpreadTemplateId(id), name, photos, text) } }
        nonEmpty  <- NonEmptyList.fromList(templates).toRight(new IllegalArgumentException(s"No $kind templates")).liftTo[ConnectionIO]
      yield nonEmpty

    private def loadSlots(id: UUID) =
      val select = sql"SELECT kind, x, y, width, height FROM spread_template_slot WHERE template_id = $id ORDER BY id"
      select.query[(String, SlotBox)].to[List].flatMap: rows =>
        val photos = rows.collect { case ("photo", box) => box }.toVector
        val texts  = rows.collect { case ("text", box) => box }
        texts match
          case text :: Nil if photos.nonEmpty && photos.size + 1 === rows.size => (photos, text).pure[ConnectionIO]
          case _ => new IllegalArgumentException(s"Invalid opening slots for template $id").raiseError[ConnectionIO, (Vector[SlotBox], SlotBox)]

    private given Meta[SpreadTemplateName] =
      Meta[String].tiemap[SpreadTemplateName](_.refineEither)(value => value: String)
