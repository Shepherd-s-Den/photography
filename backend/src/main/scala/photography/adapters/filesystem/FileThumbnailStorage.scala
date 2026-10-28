package photography.adapters.filesystem

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import org.typelevel.log4cats.Logger
import photography.domain.album.*
import photography.ports.ThumbnailStorage
import photography.ports.ThumbnailStorage.*
import photography.syntax.SyncSyntax.*
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import java.security.MessageDigest
import java.util.HexFormat

object FileThumbnailStorage:
  def make[F[_]: Async: Logger](root: Path): ThumbnailStorage[F] = new LiveFileThumbnailStorage(root)

  final private class LiveFileThumbnailStorage[F[_]: Async: Logger](root: Path) extends ThumbnailStorage[F]:
    override def storeThumbnail(location: ThumbnailLocation, image: EncodedThumbnail): F[StoreResult] =
      val partial = root.resolve(s"${location.value}.tmp")
      val target  = root.resolve(s"${location.value}.jpg")
      val write   =
        for
          bytes  <- image.bytes.toArray.blocking[F]
          _      <- checkRoot().liftTo[F]
          _      <- Files.createDirectories(root).blocking[F]
          _      <- require(!Files.exists(target), "Thumbnail location already exists").blocking[F]
          _      <- Files.write(partial, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).blocking[F]
          _      <- force(partial, StandardOpenOption.WRITE)
          _      <- Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE).blocking[F]
          _      <- force(root, StandardOpenOption.READ)
          digest <- HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).blocking[F]
        yield digest
      write.map: digest =>
        val version = digest.refineUnsafe[Not[Blank]]
        val size    = image.bytes.size.refineUnsafe[ThumbnailByteCountConstraint]
        StoreResult.Stored(Thumbnail(location, version = version, dimensions = image.dimensions, quality = image.quality, bytes = size))
      .handleErrorWith(error => Logger[F].error(error)("Thumbnail storage failed").as(StoreResult.Failed))

    override def removeThumbnail(location: ThumbnailLocation): F[RemoveResult] =
      val remove =
        for
          _      <- checkRoot().liftTo[F]
          _      <- Files.deleteIfExists(root.resolve(s"${location.value}.jpg")).blocking[F]
          _      <- Files.deleteIfExists(root.resolve(s"${location.value}.tmp")).blocking[F]
          exists <- Files.exists(root).blocking[F]
          _      <- if exists then force(root, StandardOpenOption.READ) else ().pure[F]
        yield RemoveResult.Removed
      remove.handleErrorWith(error => Logger[F].error(error)("Thumbnail cleanup failed").as(RemoveResult.Failed))

    private def force(path: Path, access: StandardOpenOption) =
      Resource.fromAutoCloseable(FileChannel.open(path, access).blocking[F])
        .use(channel => channel.force(true).blocking[F])

    private def checkRoot() =
      val valid = root.isAbsolute && root.normalize().toString === root.toString
      Either.cond(valid, (), new IllegalArgumentException("Expected an absolute normalized thumbnail root"))
