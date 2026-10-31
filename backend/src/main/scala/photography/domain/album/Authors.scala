package photography.domain.album

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.collection.MinLength

enum Author derives CanEqual:
  case Cosmin, Mara, Iacob

type AlbumAuthors = Set[Author] :| MinLength[1]

object Author:
  val all: AlbumAuthors = Author.values.toSet.refineUnsafe[MinLength[1]]
