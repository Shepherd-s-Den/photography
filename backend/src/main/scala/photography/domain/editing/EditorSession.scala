package photography.domain.editing

import photography.domain.album.AlbumId
import photography.domain.layout.SpreadId
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

import java.time.Instant
import java.util.UUID

opaque type EditorId = UUID
object EditorId:
  def apply(value: UUID): EditorId = value
  extension (id: EditorId) def value: UUID = id

opaque type SessionId = UUID
object SessionId:
  def apply(value: UUID): SessionId = value
  extension (id: SessionId) def value: UUID = id

type SessionLease = Long :| Positive

final case class EditorSession(id: SessionId, owner: EditorId, lastHeartbeat: Instant)

enum EditingTarget:
  case Spread(id: SpreadId)
  case Cover(album: AlbumId)

final case class EditingLock(target: EditingTarget, session: SessionId)

enum EditorTabState:
  case Active(session: EditorSession)
  case TakeoverRequired(existing: EditorSession)
  case KeptExisting
  case StaleSession

final case class EditorSessionState(
    sessions: Map[EditorId, EditorSession],
    locks: Set[EditingLock]
)
