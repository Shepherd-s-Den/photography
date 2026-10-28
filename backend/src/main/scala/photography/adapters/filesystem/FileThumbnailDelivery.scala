package photography.adapters.filesystem

import cats.effect.Async
import cats.syntax.all.*
import fs2.io.file.{Files as StreamFiles, Path as StreamPath}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import org.typelevel.log4cats.Logger
import photography.domain.album.*
import photography.usecases.ThumbnailDelivery
import photography.usecases.ThumbnailDelivery.GetResult
import java.nio.file.{Files, NoSuchFileException, Path}
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat

object FileThumbnailDelivery:
  def make[F[_]: Async: Logger](root: Path): ThumbnailDelivery[F] = new LiveFileThumbnailDelivery(root)

  final private class LiveFileThumbnailDelivery[F[_]: Async: Logger](root: Path) extends ThumbnailDelivery[F]:
    override def get(location: ThumbnailLocation, version: String :| Not[Blank]): F[GetResult] =
      resolve(location).flatMap:
        case None       => GetResult.NotFound.pure[F]
        case Some(path) =>
          StreamFiles.forAsync[F].readAll(StreamPath.fromNioPath(path)).take(300001).compile.toVector.flatMap: bytes =>
            Async[F].blocking:
              val valid = bytes.refineEither[ThumbnailBytesConstraint].leftMap(new IllegalStateException(_))
              valid.map: content =>
                val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.toArray))
                if digest === version then GetResult.Found(content) else GetResult.NotFound
            .flatMap(_.liftTo[F])
      .recoverWith:
        case _: NoSuchFileException => GetResult.NotFound.pure[F]
        case error                  => Logger[F].error(error)("Managed thumbnail delivery failed").as(GetResult.Failed)

    private def resolve(location: ThumbnailLocation) =
      Async[F].blocking:
        require(root.isAbsolute, "Expected an absolute thumbnail root")
        val directory = root.toRealPath()
        val file      = directory.resolve(s"${location.value}.jpg").toRealPath()
        val contained = file.startsWith(directory) && Files.readAttributes(file, classOf[BasicFileAttributes]).isRegularFile
        Option.when(contained)(file)
