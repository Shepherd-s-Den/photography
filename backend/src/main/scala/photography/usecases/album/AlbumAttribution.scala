package photography.usecases.album

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.collection.MinLength
import photography.domain.album.{AlbumAuthors, AlbumYear, Author}
import photography.usecases.album.AlbumAttribution.*

trait AlbumAttribution:
  def initialize(origin: Origin): InitializationResult

object AlbumAttribution:
  def make: AlbumAttribution = new LiveAlbumAttribution

  final private class LiveAlbumAttribution extends AlbumAttribution:
    override def initialize(origin: Origin): InitializationResult =
      val includeIacob = origin match
        case Origin.NewAlbum            => true
        case Origin.ExistingAlbum(year) => year > 2025
      val authors: AlbumAuthors =
        if includeIacob then Set(Author.Cosmin, Author.Mara, Author.Iacob).refineUnsafe[MinLength[1]]
        else Set(Author.Cosmin, Author.Mara).refineUnsafe[MinLength[1]]
      InitializationResult.Initialized(authors)

  enum Origin:
    case NewAlbum
    case ExistingAlbum(year: AlbumYear)

  enum InitializationResult derives CanEqual:
    case Initialized(authors: AlbumAuthors)
