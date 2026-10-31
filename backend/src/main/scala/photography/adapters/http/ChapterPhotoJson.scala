package photography.adapters.http

import io.circe.Json
import cats.syntax.all.*
import photography.domain.album.*
import photography.usecases.album.AlbumWorkspaceManager.ChapterPhotos
import photography.usecases.photo.{ChapterPhotoImport, PhotoManager, PhotoWriteRecovery}

private[http] object ChapterPhotoJson:
  def encodePhotos(snapshot: ChapterPhotos): Json =
    val inventory = snapshot.inventory
    Json.obj(
      "photos"         -> Json.arr(inventory.photos.map(encodePhoto)*),
      "placedPhotoIds" -> Json.arr(inventory.placed.toVector.sortBy(_.value.toString).map(photo => Json.fromString(photo.value.toString))*),
      "counts"         -> Json.obj("unplaced" -> Json.fromInt(inventory.countUnplaced), "total" -> Json.fromInt(inventory.total)),
      "processing"     -> encodeProcessing(snapshot.execution)
    )

  private def encodePhoto(photo: Photo) =
    val thumbnail = photo.thumbnail
    val url       = s"/thumbnails/${thumbnail.location.value}/${thumbnail.version}.jpg"
    Json.obj(
      "id"            -> Json.fromString(photo.id.value.toString),
      "filename"      -> Json.fromString(photo.original.filename),
      "capturedLocal" -> photo.metadata.maybeCapturedLocal.fold(Json.Null)(value => Json.fromString(value.toString)),
      "width"         -> Json.fromInt(photo.metadata.dimensions.width),
      "height"        -> Json.fromInt(photo.metadata.dimensions.height),
      "caption"       -> photo.maybeCaption.fold(Json.Null)(Json.fromString),
      "thumbnail"     -> Json.obj(
        "url"    -> Json.fromString(url),
        "width"  -> Json.fromInt(thumbnail.dimensions.width),
        "height" -> Json.fromInt(thumbnail.dimensions.height)
      )
    )

  private def encodeProcessing(execution: ChapterPhotoImport.ExecutionOutcome) =
    val (state, unsupported, issues) = execution.status match
      case ChapterPhotoImport.Status.Idle             => ("idle", 0, Vector.empty[PhotoManager.Issue])
      case ChapterPhotoImport.Status.Queued           => ("queued", 0, Vector.empty[PhotoManager.Issue])
      case ChapterPhotoImport.Status.Running          => ("running", 0, Vector.empty[PhotoManager.Issue])
      case ChapterPhotoImport.Status.LaunchFailed     => ("failed", 0, Vector.empty[PhotoManager.Issue])
      case ChapterPhotoImport.Status.Uncertain        => ("failed", 0, Vector.empty[PhotoManager.Issue])
      case ChapterPhotoImport.Status.Finished(result) =>
        val failed = result.issues.exists(issue => !isSkipped(issue))
        (if failed then "failed" else "completed", result.unsupportedCount: Int, result.issues)
    val scheduling = execution.status match
      case ChapterPhotoImport.Status.LaunchFailed => Vector(encodeIssue(stage = "scheduling", code = "scheduling_failed", maybeFilename = None))
      case ChapterPhotoImport.Status.Uncertain    => Vector(encodeIssue(stage = "persistence", code = "persistence_failed", maybeFilename = None))
      case _                                      => Vector.empty[Json]
    val failures = issues.map(encodeImportIssue) ++ execution.retirementIssues.map(encodeRetirementIssue) ++ scheduling
    Json.obj(
      "state"            -> Json.fromString(if execution.retirementIssues.nonEmpty then "failed" else state),
      "unsupportedCount" -> Json.fromInt(unsupported),
      "skippedCount"     -> Json.fromInt(issues.count(isSkipped)),
      "issues"           -> Json.arr(failures*)
    )

  private def isSkipped(issue: PhotoManager.Issue) = issue match
    case _: PhotoManager.Issue.Undecodable | _: PhotoManager.Issue.Unstable => true
    case _                                                                  => false

  private def encodeImportIssue(issue: PhotoManager.Issue) = issue match
    case PhotoManager.Issue.Discovery             => encodeIssue(stage = "discovery", code = "unreadable_source", maybeFilename = None)
    case PhotoManager.Issue.Read(filename)        => encodeIssue(stage = "read", code = "unreadable_source", maybeFilename = filename.some)
    case PhotoManager.Issue.Generation(filename)  => encodeIssue(stage = "generation", code = "encoding_failed", maybeFilename = filename.some)
    case PhotoManager.Issue.Undecodable(filename) => encodeIssue(stage = "read", code = "undecodable", maybeFilename = filename.some)
    case PhotoManager.Issue.Unstable(filename)    => encodeIssue(stage = "read", code = "unstable", maybeFilename = filename.some)
    case PhotoManager.Issue.Storage               => encodeIssue(stage = "storage", code = "storage_failed", maybeFilename = None)
    case _: PhotoManager.Issue.Cleanup            => encodeIssue(stage = "cleanup", code = "cleanup_failed", maybeFilename = None)
    case _                                        => encodeIssue(stage = "persistence", code = "persistence_failed", maybeFilename = None)

  private def encodeRetirementIssue(issue: PhotoWriteRecovery.Issue) = issue match
    case _: PhotoWriteRecovery.Issue.Cleanup => encodeIssue(stage = "cleanup", code = "cleanup_failed", maybeFilename = None)
    case _                                   => encodeIssue(stage = "persistence", code = "persistence_failed", maybeFilename = None)

  private def encodeIssue(stage: String, code: String, maybeFilename: Option[PhotoFilename]) =
    Json.obj("stage" -> Json.fromString(stage), "code" -> Json.fromString(code), "filename" -> maybeFilename.fold(Json.Null)(Json.fromString))
