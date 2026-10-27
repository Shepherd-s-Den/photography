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
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption, StandardOpenOption}
import java.security.MessageDigest
import java.util.HexFormat
import scala.jdk.CollectionConverters.*
import scala.util.Try

object FileThumbnailStorage:
  def make[F[_]: Async: Logger](root: Path): ThumbnailStorage[F] = new LiveFileThumbnailStorage(root)

  final private class LiveFileThumbnailStorage[F[_]: Async: Logger](root: Path) extends ThumbnailStorage[F]:
    override def storeThumbnail(location: ThumbnailLocation, image: EncodedThumbnail): F[StoreResult] =
      val partial = root.resolve(s"${location.value}.tmp")
      val target  = root.resolve(s"${location.value}.jpg")
      val bytes   = image.bytes.toArray
      val write   =
        for
          _      <- checkRoot().blocking[F]
          _      <- Files.createDirectories(root).blocking[F]
          _      <- require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS), "Thumbnail location already exists").blocking[F]
          _      <- Files.write(partial, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).blocking[F]
          _      <- force(partial, StandardOpenOption.WRITE)
          _      <- Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE).blocking[F]
          _      <- force(root, StandardOpenOption.READ)
          digest <- HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).blocking[F]
        yield digest
      write.flatMap: digest =>
        val descriptor = Try:
          val version = digest.refineUnsafe[Not[Blank]]
          val size    = image.bytes.size.refineUnsafe[ThumbnailByteCountConstraint]
          Thumbnail(location, version = version, image.dimensions, image.quality, bytes = size)
        descriptor.liftTo[F].map(StoreResult.Stored(_))
      .handleErrorWith(error => Logger[F].error(error)("Thumbnail storage failed").as(StoreResult.Failed))

    override def removeThumbnail(location: ThumbnailLocation): F[RemoveResult] =
      val remove =
        for
          _      <- checkRoot().blocking[F]
          _      <- Files.deleteIfExists(root.resolve(s"${location.value}.jpg")).blocking[F]
          _      <- Files.deleteIfExists(root.resolve(s"${location.value}.tmp")).blocking[F]
          exists <- Files.exists(root, LinkOption.NOFOLLOW_LINKS).blocking[F]
          _      <- if exists then force(root, StandardOpenOption.READ) else ().pure[F]
        yield RemoveResult.Removed
      remove.handleErrorWith(error => Logger[F].error(error)("Thumbnail cleanup failed").as(RemoveResult.Failed))

    private def force(path: Path, access: StandardOpenOption) =
      Resource.fromAutoCloseable(FileChannel.open(path, access, LinkOption.NOFOLLOW_LINKS).blocking[F])
        .use(channel => channel.force(true).blocking[F])

    private def checkRoot(): Unit =
      require(root.isAbsolute && root.normalize().toString === root.toString, "Expected an absolute normalized thumbnail root")
      val ancestors = root.iterator().asScala.scanLeft(root.getRoot)((parent, segment) => parent.resolve(segment))
      require(!ancestors.exists(Files.isSymbolicLink(_)), "Thumbnail root and ancestors must not be symlinks")
