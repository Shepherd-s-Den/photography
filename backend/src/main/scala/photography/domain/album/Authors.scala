package photography.domain.album

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.collection.MinLength

enum Author derives CanEqual:
  case Cosmin, Mara, Iacob

type AlbumAuthors = Set[Author] :| MinLength[1]

object Author:
  val all: AlbumAuthors = Set(Author.Cosmin, Author.Mara, Author.Iacob).refineUnsafe[MinLength[1]]
