package photography.usecases.album

import cats.Functor
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.collection.MinLength
import photography.domain.album.*
import photography.ports.album.AlbumEditorialRepository
import photography.usecases.album.AlbumEditorialManager.*

trait AlbumEditorialManager[F[_]]:
  def read(album: AlbumId): F[ReadResult]
  def saveSynopsis(album: AlbumId, text: String): F[SaveSynopsisResult]
  def saveAuthors(album: AlbumId, authors: AlbumAuthors): F[SaveAuthorsResult]
  def initialize(origin: Origin): InitializationResult

object AlbumEditorialManager:
  def make[F[_]: Functor](repository: AlbumEditorialRepository[F]): AlbumEditorialManager[F] = new LiveAlbumEditorialManager(repository)

  final private class LiveAlbumEditorialManager[F[_]: Functor](repository: AlbumEditorialRepository[F]) extends AlbumEditorialManager[F]:
    override def read(album: AlbumId): F[ReadResult] =
      repository.read(album).map:
        case AlbumEditorialRepository.ReadResult.Found(value) => ReadResult.Found(value)
        case AlbumEditorialRepository.ReadResult.NotFound     => ReadResult.NotFound
        case AlbumEditorialRepository.ReadResult.Failed       => ReadResult.Failed

    override def saveSynopsis(album: AlbumId, text: String): F[SaveSynopsisResult] =
      repository.saveSynopsis(album, text).map:
        case AlbumEditorialRepository.WriteResult.Saved     => SaveSynopsisResult.Saved
        case AlbumEditorialRepository.WriteResult.NotFound  => SaveSynopsisResult.NotFound
        case AlbumEditorialRepository.WriteResult.Failed    => SaveSynopsisResult.Failed
        case AlbumEditorialRepository.WriteResult.Uncertain => SaveSynopsisResult.Uncertain

    override def saveAuthors(album: AlbumId, authors: AlbumAuthors): F[SaveAuthorsResult] =
      repository.saveAuthors(album, authors).map:
        case AlbumEditorialRepository.WriteResult.Saved     => SaveAuthorsResult.Saved
        case AlbumEditorialRepository.WriteResult.NotFound  => SaveAuthorsResult.NotFound
        case AlbumEditorialRepository.WriteResult.Failed    => SaveAuthorsResult.Failed
        case AlbumEditorialRepository.WriteResult.Uncertain => SaveAuthorsResult.Uncertain

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

  enum ReadResult derives CanEqual:
    case Found(editorial: AlbumEditorial)
    case NotFound, Failed

  enum SaveSynopsisResult derives CanEqual:
    case Saved, NotFound, Failed, Uncertain

  enum SaveAuthorsResult derives CanEqual:
    case Saved, NotFound, Failed, Uncertain
