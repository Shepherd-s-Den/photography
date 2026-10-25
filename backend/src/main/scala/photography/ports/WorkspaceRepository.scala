package photography.ports

import photography.domain.album.*
import photography.domain.layout.{Spread, SpreadTemplate}

trait WorkspaceRepository[F[_]]:
  /** One consistent, domain-valid snapshot: ordered chapters/blocks, complete matching slots and the country catalog. */
  def get(id: AlbumId): F[WorkspaceRepository.ReadResult]

  /** The lowest-ID ChapterStart template with its complete ordered slots. Missing or malformed catalogs fail. */
  def defaultChapterTemplate: F[WorkspaceRepository.TemplateResult]

  /**
   * Compare revision and atomically commit references, opening and order; advance revision on every relevant album mutation. Stale, rejected, missing
   * and failed writes leave persisted state unchanged. Unique year/Home/folder constraints remain enforced.
   */
  def commit(id: AlbumId, revision: Long, mutation: WorkspaceRepository.Mutation): F[WorkspaceRepository.CommitResult]

object WorkspaceRepository:
  final case class Snapshot(workspace: AlbumWorkspace, revision: Long)

  enum ReadResult:
    case Found(snapshot: Snapshot)
    case NotFound
    case Failed(reason: Failure)

  enum TemplateResult:
    case Found(template: SpreadTemplate)
    case Failed(reason: Failure)

  enum Mutation:
    case AssignYear(year: AlbumYear, title: AlbumName)
    case CreateChapter(chapter: Chapter, opening: Spread.ChapterStart, order: Vector[ChapterId])
    case UpdateChapter(chapter: Chapter, order: Vector[ChapterId])

  enum CommitResult:
    case Applied, Stale, NotFound
    case Rejected(message: String)
    case Failed(reason: Failure)

  enum Failure:
    case InvalidWorkspace, DatabaseFailure
