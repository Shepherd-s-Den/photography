package photography.usecases.album

import cats.Functor
import cats.syntax.all.*
import photography.domain.album.*
import photography.ports.album.AlbumEditorialRepository
import photography.usecases.album.AlbumEditorialManager.*

trait AlbumEditorialManager[F[_]]:
  def read(album: AlbumId): F[ReadResult]
  def saveSynopsis(album: AlbumId, text: String): F[SaveSynopsisResult]
  def saveAuthors(album: AlbumId, authors: AlbumAuthors): F[SaveAuthorsResult]

object AlbumEditorialManager:
  def make[F[_]: Functor](repository: AlbumEditorialRepository[F]): AlbumEditorialManager[F] = new LiveAlbumEditorialManager(repository)

  final private class LiveAlbumEditorialManager[F[_]: Functor](repository: AlbumEditorialRepository[F]) extends AlbumEditorialManager[F]:
    override def read(album: AlbumId): F[ReadResult] =
      repository.read(album).map:
        case AlbumEditorialRepository.ReadResult.Found(value) => ReadResult.Found(value)
        case AlbumEditorialRepository.ReadResult.NotFound     => ReadResult.NotFound
        case AlbumEditorialRepository.ReadResult.Failed       => ReadResult.Failed

    override def saveSynopsis(album: AlbumId, text: String): F[SaveSynopsisResult] =
      repository.saveSynopsis(album, text = text).map:
        case AlbumEditorialRepository.WriteResult.Saved     => SaveSynopsisResult.Saved
        case AlbumEditorialRepository.WriteResult.NotFound  => SaveSynopsisResult.NotFound
        case AlbumEditorialRepository.WriteResult.Failed    => SaveSynopsisResult.Failed
        case AlbumEditorialRepository.WriteResult.Uncertain => SaveSynopsisResult.Uncertain

    override def saveAuthors(album: AlbumId, authors: AlbumAuthors): F[SaveAuthorsResult] =
      repository.saveAuthors(album, authors = authors).map:
        case AlbumEditorialRepository.WriteResult.Saved     => SaveAuthorsResult.Saved
        case AlbumEditorialRepository.WriteResult.NotFound  => SaveAuthorsResult.NotFound
        case AlbumEditorialRepository.WriteResult.Failed    => SaveAuthorsResult.Failed
        case AlbumEditorialRepository.WriteResult.Uncertain => SaveAuthorsResult.Uncertain

  enum ReadResult derives CanEqual:
    case Found(editorial: AlbumEditorial)
    case NotFound, Failed

  enum SaveSynopsisResult derives CanEqual:
    case Saved, NotFound, Failed, Uncertain

  enum SaveAuthorsResult derives CanEqual:
    case Saved, NotFound, Failed, Uncertain
