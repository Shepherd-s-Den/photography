# Chapter photo import implementation slices

The proposal owns behavior. Each slice waits for the preceding PR's merge and branch reconciliation; every implementation PR is limited to **1,000 additions plus deletions, including tests**.

Split an oversized slice by responsibility and revise ownership before implementation, never by omitting tests or separating formatting.

## Delivery sequence

| Slice | Responsibility | Criterion ownership | Independently verifiable outcome |
| --- | --- | --- | --- |
| 1 | Source inventory / JPEG metadata | I3, I4, I11; ordering in P3 | Temporary folders prove JPEG scope, symlink rejection, unsupported counts, missing/invalid EXIF fallback and unstable reads. |
| 2 | Thumbnail generation / managed files | T1, T2, T4 | Real images prove orientation, dimensions, exact byte boundary and safe replacement/cleanup/retry. |
| 3 | Persistence / placement invalidation | I7, I13; durable publication in I12 | SQLite tests prove identity, constraints, generation-safe publication, migration and placement-only cascades. |
| 4 | Import application / coordination | I1, I2, I5, I6, I8-I12 | Application tests prove triggers, progress, enumeration safety, coalescing, supersession and recovery. |
| 5 | HTTP / runtime composition | T3; public integration of I1-I13, T1-T4 | HTTP tests prove API-only import, inventory/status/Refresh/recovery and thumbnail-only delivery. |
| 6 | Scoped selector state | P5-P7, P10; scope integration of P1/P3 | Component/HTTP tests prove chapter versus album scope, global order, counts, Refresh, failures and empty albums. |
| 7 | Rows / resize / accessibility | P1-P4, P8, P9, P11 | Browser tests prove ordered full-width rows, whole images, portrait emphasis, shared grip, retained width and focus. |
| 8 | Spread navigation / metadata | N1-N7 | Browser tests prove grouped individual-spread navigation, URL history, captions and editable-only folders. |

Each owner covers its enumerated edge cases. Integration slices consume earlier policy instead of duplicating it.

## Caller-first boundaries

Sketches inform each slice's required design map; they are not production declarations.

```scala
enum PhotoScope:
  case Chapter(id: ChapterId)
  case Album(id: AlbumId)

type CapturedAt = java.time.LocalDateTime

enum ImportStatus:
  case Pending, Running, Complete, Failed

final case class ChapterImport(
    chapter: ChapterId, status: ImportStatus, unsupported: Int
)

trait PhotoBrowser[F[_]]:
  def inventory(scope: PhotoScope): F[PhotoBrowser.InventoryResult]
  def refresh(scope: PhotoScope): F[PhotoBrowser.RefreshResult]

object PhotoBrowser:
  enum InventoryResult:
    case Found(photos: Vector[Photo], unplaced: Int, imports: Vector[ChapterImport])
    case NotFound
    case Failed
  enum RefreshResult:
    case Started, AlreadyRunning, NotFound, Failed
```

| Consumer | Effectful owner | Responsibility |
| --- | --- | --- |
| Chapter save / scoped Refresh / recovery | Import application | Schedule/coalesce; keep image processing outside chapter locks. |
| Import application | Source inventory | Enumerate/read JPEGs; validate containment and stable source evidence. |
| Import application | Thumbnail generation | Encode bounded orientation-correct JPEGs; justify the chosen image library before introduction. |
| Import application | Photo persistence | Atomically commit current-generation metadata and placement invalidation. |
| Import application / image delivery | Thumbnail storage | Publish/read/delete files; protect references and in-flight work. |
| HTTP inventory / Refresh | Photo browser | Resolve scope ownership; aggregate per-chapter status and fan out Refresh. |

## SQLite target

Extend `backend/src/main/resources/db/V1__create_photography_schema.sql` through Flyway. Preserve user databases; never edit applied migrations or reset data.

| Record | Target fields / constraints |
| --- | --- |
| `photo` | Retain ID/chapter/source/caption and unique chapter/source; add source hash and ready thumbnail dimensions/bytes with positive/bounded `CHECK`s. |
| Capture time | Nullable fixed-width ISO local date/time text with nine fractional digits; source/SQLite adapters validate their representations; lexical order matches local chronology. |
| Ready thumbnail | Location/dimensions/bytes all present together or absent together; explicit `IS NULL` / `IS NOT NULL` checks. |
| Placement | Retain photo foreign key `ON DELETE CASCADE`; never cascade to slots. |

```sql
CREATE TABLE chapter_photo_import (
  chapter_id TEXT PRIMARY KEY NOT NULL
    REFERENCES chapter (id) ON DELETE CASCADE,
  generation INTEGER NOT NULL CHECK (generation >= 0),
  status TEXT NOT NULL
    CHECK (status IN ('pending', 'running', 'complete', 'failed')),
  unsupported_count INTEGER NOT NULL CHECK (unsupported_count >= 0)
) STRICT;
```

Generation checks and photo writes share one transaction. Thumbnail bytes use `CHECK (thumbnail_bytes BETWEEN 1 AND 300000)` alongside ready-field null checks.

Replace `Photo.maybeCapturedAt: Option[Instant]` with optional camera-local time. Preserve legacy `captured_at_ms` metadata during additive migration; populate local capture time only from source EXIF, never an assumed timezone.

## Gates and archive

Before each slice: caller-first design map, criteria/edge-case test projection and observed expected failures. Define HTTP wire contracts in OpenAPI before exposing slice 5.

Before each PR: applicable AGENTS.md gates, both fresh sequential reviews, dispositions and independent repair verification. Count the complete diff before each review.

After all slices merge: compare the real journey with the retained spike, apply every Doc Sync entry and archive both documents. Retain the runnable spike through the archive PR's merge.
