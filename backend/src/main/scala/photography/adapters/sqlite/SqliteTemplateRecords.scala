package photography.adapters.sqlite

import cats.data.NonEmptyList
import cats.syntax.all.*
import io.github.iltotore.iron.*
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits.*
import photography.domain.layout.*

private[sqlite] object SqliteTemplateRecords:
  def albumStarts: ConnectionIO[NonEmptyList[SpreadTemplate]] =
    for
      ids       <- sql"SELECT id FROM spread_template WHERE kind = 'album_start' ORDER BY id".query[String].to[List]
      templates <- ids.traverse(load)
      nonEmpty  <- SqliteDecoding.requireData(NonEmptyList.fromList(templates).toRight("No AlbumStart templates"))
    yield nonEmpty

  def load(id: String): ConnectionIO[SpreadTemplate] =
    for
      maybeRow <- sql"SELECT name, kind FROM spread_template WHERE id = $id".query[(String, String)].option
      row      <- SqliteDecoding.requireData(maybeRow.toRight(s"Missing template $id"))
      slots    <- sql"SELECT kind, x, y, width, height FROM spread_template_slot WHERE template_id = $id ORDER BY id".query[SlotRow].to[List]
      template <- SqliteDecoding.requireData(decode(id, row._1, row._2, slots))
    yield template

  private def decode(id: String, name: String, kind: String, slots: List[SlotRow]) =
    for
      validId   <- SqliteDecoding.uuid(id)
      validName <- name.refineEither: Either[String, SpreadTemplateName]
      validKind <- kind match
        case "album_start"   => SpreadTemplateKind.AlbumStart.asRight[String]
        case "chapter_start" => SpreadTemplateKind.ChapterStart.asRight[String]
        case "photos_only"   => SpreadTemplateKind.PhotosOnly.asRight[String]
        case other           => s"Unknown template kind $other".asLeft[SpreadTemplateKind]
      decodedSlots <- slots.traverse(_.decode)
      nonEmpty     <- NonEmptyList.fromList(decodedSlots).toRight(s"Template $id has no slots")
    yield SpreadTemplate(SpreadTemplateId(validId), validName, validKind, nonEmpty)

  final private case class SlotRow(kind: String, x: Double, y: Double, width: Double, height: Double):
    def decode: Either[String, SpreadTemplateSlot] =
      SqliteDecoding.box(x, y, width, height).flatMap: box =>
        kind match
          case "photo" => SpreadTemplateSlot.Photo(box).asRight[String]
          case "text"  => SpreadTemplateSlot.Text(box).asRight[String]
          case other   => s"Unknown slot kind $other".asLeft[SpreadTemplateSlot]
