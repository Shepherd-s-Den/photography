package photography.usecases.editing

import photography.domain.editing.{EditingLock, EditorId, EditorSession}

enum EditorTabState:
  case Active(session: EditorSession)
  case TakeoverRequired(existing: EditorSession)
  case KeptExisting
  case StaleSession

final case class EditorSessionState(sessions: Map[EditorId, EditorSession], locks: Set[EditingLock])
