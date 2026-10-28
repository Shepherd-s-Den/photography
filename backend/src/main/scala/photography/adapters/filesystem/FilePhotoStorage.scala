package photography.adapters.filesystem

import cats.effect.{Async, Resource}
import cats.Eq
import cats.syntax.all.*
import com.drew.imaging.jpeg.{JpegMetadataReader, JpegProcessingException}
import com.drew.metadata.Metadata
import com.drew.metadata.exif.{ExifDirectoryBase, ExifIFD0Directory, ExifSubIFDDirectory}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import org.typelevel.log4cats.Logger
import photography.domain.album.*
import photography.ports.{PhotoStorage, ThumbnailStorage}
import photography.ports.PhotoStorage.*
import photography.syntax.SyncSyntax.*
import java.io.{ByteArrayInputStream, IOException}
import java.nio.channels.{Channels, FileChannel}
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.{DateTimeFormatter, ResolverStyle}
import java.util.{HexFormat, Locale}
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import scala.jdk.CollectionConverters.*
import scala.util.Try

object FilePhotoStorage:
  def make[F[_]: Async: Logger](thumbnails: ThumbnailStorage[F]): PhotoStorage[F] = new LiveFilePhotoStorage(thumbnails)

  final private class LiveFilePhotoStorage[F[_]: Async: Logger](thumbnails: ThumbnailStorage[F]) extends PhotoStorage[F]:
    override def discoverOriginals(source: PhotoSource): F[DiscoveryResult] =
      resolveSource(source).blocking[F].flatMap: root =>
        Resource.fromAutoCloseable(Files.newDirectoryStream(root).blocking[F]).use: entries =>
          enumerate(root, entries = entries.iterator().asScala.toVector).blocking[F]
      .handleErrorWith(error => Logger[F].error(error)("Original discovery failed").as(DiscoveryResult.Failed))

    override def readOriginal(location: OriginalPhotoLocation): F[ReadResult] =
      val read =
        for
          path     <- resolveOriginal(location).blocking[F]
          before   <- inspectOriginal(path).blocking[F]
          bytes    <- bufferOriginal(path)
          decoded  <- decodeOriginal(bytes = bytes).blocking[F].recover { case _: IOException | _: JpegProcessingException => ReadResult.Undecodable }
          middle   <- inspectOriginal(path).blocking[F]
          repeated <- bufferOriginal(path)
          after    <- inspectOriginal(path).blocking[F]
          stable   <- (before === middle && middle === after && bytes === repeated).blocking[F]
        yield if stable then decoded else ReadResult.Unstable
      read.handleErrorWith(error => Logger[F].error(error)("Original read failed").as(ReadResult.Failed))

    override def storeThumbnail(location: ThumbnailLocation, image: EncodedThumbnail): F[ThumbnailStorage.StoreResult] =
      thumbnails.storeThumbnail(location, image)

    override def removeThumbnail(location: ThumbnailLocation): F[ThumbnailStorage.RemoveResult] =
      thumbnails.removeThumbnail(location)

    private given Eq[Object] = Eq.fromUniversalEquals

    private def enumerate(root: Path, entries: Vector[Path]) =
      val regular = entries.filter: entry =>
        val attributes = Files.readAttributes(entry, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
        attributes.isRegularFile
      val originals = regular.filter(isJpeg).map: entry =>
        val filename = entry.getFileName.toString.refineUnsafe[PhotoFilenameConstraint]
        OriginalPhotoLocation(root.toString.refineUnsafe[PhotoSourceConstraint], filename)
      val ordered = originals.sortBy(original => ((original.filename: String).toLowerCase(Locale.ROOT), original.filename: String))
      DiscoveryResult.Complete(originals = ordered, unsupportedCount = (regular.size - originals.size).refineUnsafe[GreaterEqual[0]])

    private def resolveSource(source: PhotoSource) =
      val root      = Path.of(source)
      val ancestors = root.iterator().asScala.scanLeft(root.getRoot)((parent, segment) => parent.resolve(segment))
      require(!ancestors.exists(Files.isSymbolicLink(_)), "Photo source and ancestors must not be symlinks")
      require(Files.readAttributes(root, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS).isDirectory, "Expected a photo directory")
      root

    private def resolveOriginal(location: OriginalPhotoLocation) =
      val root = resolveSource(location.source)
      val path = root.resolve(location.filename)
      require(isJpeg(path), "Expected an original JPEG")
      path

    private def inspectOriginal(path: Path) =
      val attributes = Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
      require(attributes.isRegularFile, "Expected a regular original")
      val maybeFileKey = Option(attributes.fileKey())
      (maybeFileKey, attributes.size(), attributes.lastModifiedTime().toString)

    private def bufferOriginal(path: Path) =
      Resource.fromAutoCloseable(FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).blocking[F])
        .use(channel => Channels.newInputStream(channel).readAllBytes().toVector.blocking[F])

    private def isJpeg(path: Path) =
      val filename = path.getFileName.toString.toLowerCase(Locale.ROOT)
      filename.endsWith(".jpg") || filename.endsWith(".jpeg")

    private def decodeOriginal(bytes: Vector[Byte]) =
      if bytes.isEmpty then ReadResult.Undecodable
      else
        val metadata = JpegMetadataReader.readMetadata(new ByteArrayInputStream(bytes.toArray))
        readImage(bytes = bytes).fold[ReadResult](ReadResult.Undecodable): image =>
          val content    = PhotoContent(bytes = bytes.refineUnsafe[MinLength[1]])
          val hash       = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toArray)).refineUnsafe[Not[Blank]]
          val dimensions = readDimensions(metadata, width = image.getWidth, height = image.getHeight)
          ReadResult.Read(content, PhotoMetadata(hash, maybeCapturedLocal = readCaptureTime(metadata), dimensions))

    private def readImage(bytes: Vector[Byte]) =
      val input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes.toArray))
      try
        ImageIO.getImageReaders(input).asScala.nextOption().flatMap: reader =>
          try
            val hasWarnings = new AtomicBoolean(false)
            reader.addIIOReadWarningListener((_, _) => hasWarnings.set(true))
            reader.setInput(input)
            val image = reader.read(0)
            Option.when(!hasWarnings.get())(image)
          finally reader.dispose()
      finally input.close()

    private def readDimensions(metadata: Metadata, width: Int, height: Int) =
      val maybeOrientation = Option(metadata.getFirstDirectoryOfType(classOf[ExifIFD0Directory]))
        .flatMap(directory => Option(directory.getInteger(ExifDirectoryBase.TAG_ORIENTATION))).map(_.intValue)
      val rotated    = maybeOrientation.exists(orientation => orientation >= 5 && orientation <= 8)
      val dimensions = if rotated then (height, width) else (width, height)
      dimensions match
        case (displayWidth, displayHeight) => PhotoDimensions(displayWidth.refineUnsafe[Positive], displayHeight.refineUnsafe[Positive])

    private def readCaptureTime(metadata: Metadata) =
      Option(metadata.getFirstDirectoryOfType(classOf[ExifSubIFDDirectory])).flatMap: directory =>
        val formatter = DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)
        val maybeDate = Option(directory.getString(ExifDirectoryBase.TAG_DATETIME_ORIGINAL))
          .flatMap(value => Try(LocalDateTime.parse(value, formatter)).toOption).filter(_.getYear > 0)
        val maybeFraction = Option(directory.getString(ExifDirectoryBase.TAG_SUBSECOND_TIME_ORIGINAL)).filter(_.matches("[0-9]{1,9}"))
        maybeDate.map(date => maybeFraction.fold(date)(fraction => date.withNano(fraction.padTo(9, '0').toInt)))
