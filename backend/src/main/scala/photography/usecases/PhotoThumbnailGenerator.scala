package photography.usecases

import cats.Eq
import cats.effect.{Async, Resource}
import cats.syntax.all.*
import com.drew.imaging.jpeg.{JpegMetadataReader, JpegProcessingException}
import com.drew.metadata.exif.{ExifDirectoryBase, ExifIFD0Directory}
import io.github.iltotore.iron.*
import net.coobird.thumbnailator.Thumbnails
import net.coobird.thumbnailator.util.exif.{ExifFilterUtils, Orientation}
import org.typelevel.log4cats.Logger
import photography.domain.album.*
import photography.syntax.SyncSyntax.*
import java.awt.image.BufferedImage
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException}
import java.util.List as JavaList
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.{IIOException, IIOImage, ImageIO, ImageTypeSpecifier, ImageWriteParam}
import javax.imageio.stream.{MemoryCacheImageInputStream, MemoryCacheImageOutputStream}
import scala.jdk.CollectionConverters.*

trait PhotoThumbnailGenerator[F[_]]:
  def quality: ThumbnailQuality
  def generate(content: PhotoContent): F[PhotoThumbnailGenerator.GenerationResult]

object PhotoThumbnailGenerator:
  def make[F[_]: Async: Logger](quality: ThumbnailQuality): PhotoThumbnailGenerator[F] = new LivePhotoThumbnailGenerator(quality)

  final private class LivePhotoThumbnailGenerator[F[_]: Async: Logger](override val quality: ThumbnailQuality) extends PhotoThumbnailGenerator[F]:
    override def generate(content: PhotoContent): F[GenerationResult] =
      renderThumbnail(content).flatMap:
        case Some(image) => (GenerationResult.Generated(image): GenerationResult).pure[F]
        case None        => Logger[F].warn("Encoded thumbnail exceeds its preset").as(GenerationResult.Failed)
      .recoverWith:
        case error if isExpectedFailure(error) =>
          Logger[F].error(error)("Thumbnail generation failed").as(GenerationResult.Failed)

    private def renderThumbnail(content: PhotoContent) =
      for
        bytes       <- content.bytes.toArray.blocking[F]
        orientation <- readOrientation(bytes = bytes).blocking[F]
        decoded     <- decodeImage(bytes = bytes)
        thumbnail   <- transformImage(decoded, orientation).blocking[F]
        encoded     <- encodeImage(thumbnail)
      yield encoded

    private def readOrientation(bytes: Array[Byte]) =
      val metadata = JpegMetadataReader.readMetadata(new ByteArrayInputStream(bytes))
      Option(metadata.getFirstDirectoryOfType(classOf[ExifIFD0Directory]))
        .flatMap(directory => Option(directory.getInteger(ExifDirectoryBase.TAG_ORIENTATION)))
        .flatMap(value => Option(Orientation.typeOf(value.intValue))).getOrElse(Orientation.TOP_LEFT)

    private def decodeImage(bytes: Array[Byte]) =
      val input = Resource.makeCase(new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes)).blocking[F]):
        case (stream, exit) => releaseAfterProcessing(stream.close().blocking[F], exit)
      input.use: stream =>
        val acquire = ImageIO.getImageReaders(stream).asScala.nextOption().flatMap(Option(_))
          .toRight(new IIOException("JPEG decoder unavailable")).blocking[F].flatMap(_.liftTo[F])
        Resource.makeCase(acquire) { case (reader, exit) => releaseAfterProcessing(reader.dispose().blocking[F], exit) }.use: reader =>
          {
            val warnings = new AtomicBoolean(false)
            reader.addIIOReadWarningListener((_, _) => warnings.set(true))
            reader.setInput(stream)
            Option(reader.read(0)).filter(_ => !warnings.get()).toRight(new IIOException("Invalid JPEG image"))
          }.blocking[F].flatMap(_.liftTo[F])

    private def transformImage(image: BufferedImage, orientation: Orientation) =
      val scale  = math.min(1.0, quality.longestEdge.toDouble / math.max(image.getWidth, image.getHeight))
      val filter = ExifFilterUtils.getFilterForOrientation(orientation)
      Thumbnails.of(image).scale(scale).imageType(BufferedImage.TYPE_INT_RGB).addFilter(filter).asBufferedImage()

    private def encodeImage(image: BufferedImage) =
      new ByteArrayOutputStream().blocking[F].flatMap: bytes =>
        val output = Resource.makeCase(new MemoryCacheImageOutputStream(bytes).blocking[F]):
          case (stream, exit) => releaseAfterProcessing(stream.close().blocking[F], exit)
        output.use(stream => writeJpeg(image, stream)) >>
          {
            val dimensions = ThumbnailDimensions(
              width = image.getWidth.refineUnsafe[ThumbnailPixelCountConstraint],
              height = image.getHeight.refineUnsafe[ThumbnailPixelCountConstraint]
            )
            validateThumbnail(bytes = bytes.toByteArray.toVector, dimensions)
          }.blocking[F]

    private def writeJpeg(image: BufferedImage, output: MemoryCacheImageOutputStream) =
      val acquire = ImageIO.getImageWritersByFormatName("jpeg").asScala.nextOption().flatMap(Option(_))
        .toRight(new IIOException("JPEG encoder unavailable")).blocking[F].flatMap(_.liftTo[F])
      Resource.makeCase(acquire) { case (writer, exit) => releaseAfterProcessing(writer.dispose().blocking[F], exit) }.use: writer =>
        {
          val parameters = writer.getDefaultWriteParam
          parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT)
          parameters.setCompressionQuality(0.85f)
          writer.setOutput(output)
          val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), parameters)
          val encoded  = new IIOImage(image, JavaList.of[BufferedImage](), metadata)
          writer.write(writer.getDefaultStreamMetadata(parameters), encoded, parameters)
        }.blocking[F]

    private def validateThumbnail(bytes: Vector[Byte], dimensions: ThumbnailDimensions) =
      Option.when(bytes.sizeIs <= quality.maximumBytes):
        EncodedThumbnail(bytes = bytes.refineUnsafe[ThumbnailBytesConstraint], dimensions, quality)

    private def isExpectedFailure(error: Throwable) =
      isImageFailure(error) && error.getSuppressed.forall(isImageFailure)

    private def isImageFailure(error: Throwable) =
      error match
        case _: IOException | _: JpegProcessingException => true
        case _                                           => false

    private given Eq[Object] = Eq.fromUniversalEquals

    private def releaseAfterProcessing(release: F[Unit], exit: Resource.ExitCase) =
      release.handleErrorWith: error =>
        exit match
          case Resource.ExitCase.Errored(primary) => Async[F].delay(if (primary: Object) =!= (error: Object) then primary.addSuppressed(error))
          case _                                  => Async[F].raiseError(error)

  enum GenerationResult:
    case Generated(image: EncodedThumbnail)
    case Failed
