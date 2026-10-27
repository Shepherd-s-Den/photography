# Chapter photo import and browsing

**Date:** 2026-10-27

**Classification:** feature

**Grounded in:** Confirmed 18/210-photo spike; 228-photo Synopsis/Cover selectors; source-mutation, chronological-layout and thumbnail-only delivery checks.

## What & Why

Chapters currently store only a source folder. Import their photo references and thumbnails; Synopsis and Cover show photos from every chapter in the album.

## Domain / Design Notes

```mermaid
flowchart LR
  Album --> Chapter
  Chapter --> Photo["Photo: identity + relative source"]
  Photo --> Thumbnail["One thumbnail: at most 300,000 bytes"]
  Photo --> Original["Original: read-only, in place"]
  Chapter --> ChapterSelector["Chapter selector"]
  Album --> AlbumSelector["Synopsis / Cover: list every chapter's photos"]
  ChapterSelector --> Photo
  AlbumSelector --> Photo
```

### Persisted contracts

| Record | SQLite enforcement | Application contract |
| --- | --- | --- |
| Photo | Primary key; chapter foreign key; unique chapter/source | Stable identity across same-path edits; relative source stays inside its chapter folder. |
| Thumbnail metadata | Positive dimensions and bytes in `1..300000` via `CHECK`; ready fields all present or all absent via explicit null checks | Publish only after the thumbnail exists; never store copies of originals. |
| Import state | Chapter primary/foreign key; nonnegative generation/count and closed statuses via `CHECK` | Persist pending/running/complete/failed state; reject superseded-generation writes. |
| Placement | Photo foreign key with cascading deletion | Remove references on confirmed source deletion; preserve empty slots and their geometry. |

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

`chapter_photo_import.status` persists the Scala `ImportStatus` values in lowercase. This is chapter-level job state, not per-photo ownership or placement state.

SQLite photo metadata stores chapter ID, relative original path, source hash, capture time, source dimensions and current thumbnail key/hash/dimensions/bytes/quality; image bytes live on the NAS.

Current-generation checks and photo writes are atomic. Content hashes detect changed bytes even when timestamps and sizes match; same-path edits retain photo ID and placement.

Capture time uses nullable fixed-width ISO local date/time text with nine fractional digits; source/SQLite adapters validate representations and preserve chronological ordering.

Migrations preserve existing databases and legacy capture metadata. Populate local capture time from source EXIF, never reinterpret an epoch value using an assumed timezone.

### Domain values

```scala
enum ImportStatus:
  case Pending, Running, Complete, Failed

enum ThumbnailQuality:
  case Low, Standard, High
```

| Value | Contract |
| --- | --- |
| Capture time | Camera-local EXIF date/time; missing/invalid dates become undated photos, not skipped JPEGs; never infer timezone or modification time. |
| Order | Capture time ascending, undated last; case-insensitive filename, exact filename, then chapter ID break ties. |
| Source | Immediate regular JPEG files (`.jpg`/`.jpeg`, case-insensitive); no recursion or symlink imports. |
| Thumbnail | One orientation-correct, uncropped JPEG; no upscale; selected quality sets resolution/byte ceilings, not target file size. |
| Ownership | Every photo belongs to exactly one chapter. Synopsis/Cover list all chapters' photos without changing ownership; chapter spreads list their chapter. |
| Counts / Refresh | Counts describe the displayed photos; Synopsis/Cover Refresh requests each chapter's import. Placement actions remain a later feature. |

### Thumbnail configuration and delivery

| Quality | Longest edge | Maximum encoded bytes |
| --- | ---: | ---: |
| Low | 1024px | 75,000 |
| Standard (default) | 1536px | 150,000 |
| High | 2048px | 300,000 |

```hocon
photography.thumbnails {
  quality = "standard"
  quality = ${?PHOTOGRAPHY_THUMBNAIL_QUALITY}
  root = "/thumbnails"
  root = ${?PHOTOGRAPHY_THUMBNAIL_ROOT}
}
```

PureConfig decodes the quality enum and absolute storage root at startup; unknown quality or invalid roots fail startup explicitly. Domain/use-case code receives typed settings.

Production mounts originals read-only and the thumbnail root read/write on the NAS array. SQLite stores locations/metadata only; temporary and final thumbnail writes use that same filesystem.

Refresh regenerates when source bytes or configured quality differ; ordinary opens retain completed images. Presets never create parallel published variants.

Use content/version-specific thumbnail URLs with `Cache-Control: private, max-age=31536000, immutable`; changed output receives a new URL. Inventory/status responses remain uncached.

The browser fetches thumbnails near the viewport rather than all album images eagerly. Preset ceilings bound transfer size; pixel ceilings bound decoded-image size.

### Use cases and ports

The following are effectful boundary contracts, not concrete adapter structure. Result alternatives below are closed; storage/transport representations stay at their adapters.

```scala
trait ChapterPhotoImporter[F[_]]:
  def refresh(chapter: ChapterId): F[RefreshResult]
  def recover(chapter: ChapterId): F[RecoveryResult]

trait PhotoBrowser[F[_]]:
  def listChapter(chapter: ChapterId): F[PhotoInventoryResult]
  def listAlbum(album: AlbumId): F[PhotoInventoryResult]
  def readThumbnail(photo: PhotoId, version: ThumbnailVersion): F[ThumbnailReadResult]

trait PhotoSources[F[_]]:
  def scan(folder: SourceFolder): F[SourceScanResult]
  def read(folder: SourceFolder, source: RelativeLocation): F[SourceReadResult]

trait ThumbnailGenerator[F[_]]:
  def generate(source: SourcePhoto, quality: ThumbnailQuality): F[ThumbnailGenerationResult]

trait ThumbnailFiles[F[_]]:
  def store(image: EncodedThumbnail): F[ThumbnailStoreResult]
  def read(key: ThumbnailKey): F[ThumbnailReadResult]
  def list(): F[ThumbnailListingResult]
  def delete(key: ThumbnailKey): F[ThumbnailDeleteResult]

trait PhotoRepository[F[_]]:
  def listChapter(chapter: ChapterId): F[PhotoInventoryResult]
  def listAlbum(album: AlbumId): F[PhotoInventoryResult]
  def referencedThumbnails(keys: Set[ThumbnailKey]): F[ReferencedThumbnailsResult]
  def begin(chapter: ChapterId, expected: ImportGeneration, quality: ThumbnailQuality): F[ImportStartResult]
  def saveReady(run: ImportRun, photo: ReadyPhoto): F[ImportWriteResult]
  def remove(run: ImportRun, photos: Set[PhotoId]): F[ImportWriteResult]
  def finish(run: ImportRun, status: ImportStatus, unsupported: UnsupportedCount): F[ImportWriteResult]
```

| Contract value / result | Meaning / alternatives |
| --- | --- |
| `ImportRun` / `ImportGeneration` | Chapter ID, saved folder, nonnegative generation and selected quality / nonnegative generation value; subsequent writes compare current generation. |
| `SourcePhoto` | One stable read's content, hash, dimensions and optional local capture time; the encoder consumes that content, never rereads the path. |
| `ReadyPhoto` | Chapter-owned identity/source metadata plus a successfully stored thumbnail key, version/hash, dimensions, bytes and quality. |
| `EncodedThumbnail` | JPEG bytes and validated positive dimensions/selected-preset byte limit. |
| `ThumbnailKey` / `ThumbnailVersion` | Managed relative file key / immutable output identifier, never original-source browser URLs. |
| `UnsupportedCount` | Nonnegative count of unsupported files in a complete scan. |
| `RefreshResult` / `RecoveryResult` | Queued or Coalesced / Resumed or Unchanged; both also distinguish ChapterNotFound and Failed. |
| `PhotoInventoryResult` | Found: ready chapter-owned photos, counts, per-chapter saved folder/generation/status/unsupported count; or NotFound / Failed. |
| `SourceScanResult` | Complete: candidate paths and unsupported count; or Failed. Partial enumeration is never Complete. |
| `SourceReadResult` | Read: `SourcePhoto`; or Undecodable / Unstable / Failed. Invalid EXIF dates produce a Read with absent capture time. |
| `ThumbnailGenerationResult` | Generated: `EncodedThumbnail`; or Failed. Encoding failure is not an undecodable-source skip. |
| `ThumbnailStoreResult` / `ThumbnailReadResult` | Stored: key/version / Read: JPEG bytes; or Failed. Reads also distinguish NotFound. |
| `ThumbnailListingResult` / `ThumbnailDeleteResult` | Listed keys / Deleted or AlreadyAbsent; both distinguish Failed. |
| `ReferencedThumbnailsResult` | Found: requested keys referenced anywhere in the database, across all albums; or Failed, which forbids cleanup deletion. |
| `ImportStartResult` | Started: `ImportRun` with atomically incremented generation and Running status; or Superseded / ChapterNotFound / Failed. |
| `ImportWriteResult` | Saved / Superseded / ChapterNotFound / Failed; stale writes never change current state. |

Chapter save commits Pending with the folder/generation; the importer runs afterward, including API-only callers. Browser reads request recovery for unfinished displayed chapters.

The importer owns comparison, deletion decisions and coordination; repositories atomically write supplied decisions. Image work stays outside the shared chapter-mutation lock.

`PhotoBrowser` consumes repositories and thumbnail storage; HTTP consumes `PhotoBrowser`/`ChapterPhotoImporter`. No transport or storage adapter calls another adapter.

### Import lifecycle

```mermaid
sequenceDiagram
  participant I as Importer
  participant S as Original directory
  participant G as JPEG generator
  participant N as NAS thumbnails
  participant D as SQLite
  I->>D: Claim current folder/generation, Running
  I->>S: Enumerate immediate files completely
  S-->>I: Present paths + unsupported count
  I->>D: Read previous references/hashes/quality
  loop Every JPEG candidate
    I->>S: Read stable content + metadata + hash
    alt New photo, changed hash or quality
      I->>G: Encode same content within preset limits
      G-->>I: JPEG + dimensions + byte count
      I->>N: Write temporary file, atomically publish immutable key
      N-->>I: Stored key/version
      I->>D: Guard generation, commit photo + thumbnail metadata
    else Unchanged
      I->>I: Keep existing reference and thumbnail
    end
  end
  I->>D: Guard generation, remove absent references/placements
  I->>D: Guard generation, Complete + unsupported count
  I->>N: Delete unreferenced, inactive thumbnail files
```

| Persisted status | Entry / exit |
| --- | --- |
| Pending | Chapter creation/folder save commits work; claim advances to Running. |
| Running | One current chapter job; ready-photo commits become browsable independently. |
| Complete | Full enumeration/reconciliation finished; Refresh starts another generation. |
| Failed | Directory/read/encoding/file-publication/database failure; retain completed work and offer Retry with a new generation. |

Per-chapter coordination covers claim/read/decision/write and in-flight cleanup protection. Folder changes invalidate older generations; stale completion/failure cannot overwrite newer status.

Cleanup lists managed keys and queries their references across all albums before excluding in-flight keys. Publication coordinates with reference-check/deletion so cleanup cannot race a new reference.

SQLite and NAS have no shared transaction: store the file before committing its reference. A failed/superseded commit leaves an unused file for cleanup, never a dangling ready reference.

A crash after commit retains the ready image; a crash before commit leaves only an orphan. Recover Pending/interrupted Running on next open; cleanup excludes live references and in-flight files.

| Event | Result |
| --- | --- |
| Folder change | Atomically clear previous photo references/placements and advance the import generation with the saved folder. |
| Refresh / Retry | Start current-folder work; running imports publish ready photos progressively. |
| Complete enumeration | Remove absent references/placements; an empty directory succeeds. |
| Enumeration failure | Preserve previous references; report retryable failure, never an inferred empty folder. |
| Decode failure / unstable read | Skip replacement; keep a prior completed version, if any; no per-photo UI, at most a backend warning. |
| Overlapping requests | Coalesce per chapter; old-folder results cannot publish after a folder change. |
| Ordinary open | Read retained inventory; recover interrupted imports, otherwise rescan only on Refresh. |
| Thumbnail replaced / reference removed | Delete unused thumbnails; protect live references and active imports; warn and retry failed cleanup on the next import. |

The spike's adapter is disposable evidence. Backend-triggered import, progressive publication and durable coordination are production contracts, not claimed spike implementations.

## Acceptance Criteria

| ID | Observable outcome |
| --- | --- |
| I1 | Chapter creation starts import without a browser request; unavailable folders do not undo chapter creation. |
| I2 | Saving a changed folder clears old references/placements and starts its import. |
| I3 | Import immediate-folder JPEGs; count unsupported files and reject symlinks. |
| I4 | Skip undecodable JPEGs without per-photo UI; continue remaining files. |
| I5 | Ready thumbnails appear before a multi-photo import finishes. |
| I6 | Refresh detects additions and changed bytes, including unchanged filenames, timestamps and file sizes. |
| I7 | Confirmed deletion removes the photo and spread/cover placement, leaving its slot empty. |
| I8 | Empty folders succeed; failed enumeration preserves references and offers Retry. |
| I9 | Read/storage failures retain completed work and expose retryable failure. |
| I10 | Overlapping chapter requests share current work; superseded-folder results cannot publish. |
| I11 | Unstable reads warn without publishing mixed metadata/image; Refresh imports settled files. |
| I12 | Completed thumbnails survive reload/restart; next open recovers interrupted work. |
| I13 | Same-path edits preserve identity/placement; unchanged Refresh is idempotent. |
| T1 | Each preset accepts its exact byte ceiling and rejects ceiling + 1; no output exceeds 300,000 bytes. |
| T2 | Preserve orientation/proportions without upscaling at the stated resolution policy. |
| T3 | Browser delivery uses one thumbnail per photo; managed storage contains no copied originals or alternate variants. |
| T4 | Cleanup removes retired/crash-orphan files, preserves live/in-flight files across albums, and retries failures. |
| T5 | Environment overrides flow through HOCON/PureConfig; invalid settings fail startup, and unset quality selects Standard. |
| T6 | Quality changes regenerate on Refresh without changing photo identity/placement or publishing multiple variants. |
| T7 | Near-viewport loading and immutable version URLs permit cached reuse; changed thumbnails cannot reuse stale content URLs. |
| P1 | Collapsible left selectors show chapter photos on chapter spreads and all chapters' photos on Synopsis/Cover. |
| P2 | Every row, including the last, fills its width with whole uncropped images, small gutters and no padded cards. |
| P3 | Order displayed photos by capture time/filename with missing/invalid dates last; never reorder photos to fill gaps. |
| P4 | Mixed rows give portraits modest emphasis without cropping or nesting. |
| P5 | Show displayed photos' `unplaced / total`, derived from spread/cover placements, with pending import status. |
| P6 | Compact icon button `Refresh photos` reconciles the chapter, or every chapter on Synopsis/Cover, and exposes pending status. |
| P7 | No photo preview, inspection, enlargement, click action or placement interaction. |
| P8 | One grip resizes by pointer/keyboard and collapses/restores the chosen width during workspace use. |
| P9 | Narrow layouts avoid page overflow; reduced-motion preferences are respected. |
| P10 | Malformed/failed inventory responses show retryable feedback; chapter switching recovers without reload. |
| P11 | Collapse removes hidden controls from focus order and transfers focus to the grip. |
| N1 | Noncollapsible above-editor navigation shows Synopsis, then individual spreads grouped by chapter. |
| N2 | Consistent layout miniatures have compact centered Synopsis/destination captions. |
| N3 | Captions omit chapter numbers/dates; accessible names distinguish photo spreads. |
| N4 | First/Previous/Next/Last and position count traverse without wrapping; endpoints expose accessible context. |
| N5 | Direct links, reload and Back retain selection; the selected tile stays discoverable. |
| N6 | Source folders remain editable in details, absent from workspace narration. |
| N7 | One selected country displays `Country`; multiple display `Countries`. |

## Invariants

- Preserve atomic chapter/opening-spread creation, album order/capacity, Cover and Reader behavior.

## Doc Sync

| Document | Section | Changed fact |
| --- | --- | --- |
| `specs/design.md` | Album workflow | Chapter-owned photos shown chapter-locally or across all chapters on Synopsis/Cover, accessible controls and grouped spread navigation. |
| `specs/design.md` | Values | JPEG eligibility, stable chapter/source identity, camera-local capture time and deterministic display ordering. |
| `specs/design.md` | Placements and gear | Progressive import lifecycle, coordination, original-authoritative reference removal and single-thumbnail policy. |
| `specs/operational.md` | Production deployment | NAS read-only original/read-write thumbnail mounts, quality/root environment configuration, interrupted-import recovery and unused-thumbnail cleanup. |
