# Chapter photo import and browsing

**Date:** 2026-10-26

**Classification:** feature

**Grounded in:**

- Confirmed local spike: 18 Ensana and 210 Gran Canaria photos; all thumbnails at most 300,000 bytes, maximum 299,645.
- Desktop/narrow browser checks: chronological whole-image rows, portrait emphasis, centered navigator captions, no preview and chapter-switch recovery.
- Disposable-folder checks: additions, deletions, same-name edits and unavailable versus empty folders; final Country/Countries correction has both-case coverage.

## What & Why

Chapters currently save a source folder without importing its photos. Load thumbnails automatically and browse them beside the editor, with chapter-grouped spread navigation above it.

The app records references for eventual placement of originals in InDesign. A thumbnail supports browsing; it never substitutes for a deleted original.

## Domain / Design Notes

### Source and lifetime contracts

| Concern | Contract |
| --- | --- |
| Source scope | Immediate regular `.jpg`/`.jpeg` files, case-insensitive; subfolders are not traversed and symlinks are rejected. |
| Unsupported files | Report unsupported file counts; undecodable JPEGs are skipped without per-photo UI, with at most a backend warning. |
| Photo identity | Chapter and relative source path identify a photo; same-path edits preserve identity and existing placement. |
| Ordering | Ascending EXIF capture time; missing/invalid times last; ties use case-insensitive filename, then exact filename. |
| Time meaning | Compare camera-local capture times as recorded, without inventing a timezone or using file modification time. |
| Thumbnail | One orientation-correct, uncropped JPEG, never upscaled; preserve useful detail up to a 2048px longest edge within the byte ceiling. |
| Managed storage | Persist metadata and thumbnails only; source originals remain untouched and referenced in place. |
| Ownership | The application owns import decisions; storage atomically publishes photo metadata only after its thumbnail is ready. |
| Constraints | SQLite primary keys, chapter foreign keys and unique chapter/source pairs enforce reference identity. |
| Placements | Existing photo foreign keys cascade placement deletion; slots and their geometry remain when a photo disappears. |
| Use-case rules | Source containment, current-folder ownership, scan coordination and thumbnail eligibility are enforced during import. |

### Import transitions

| Event | Observable result |
| --- | --- |
| Chapter created, including API-only creation | Save the valid chapter and start import independently of browser requests. |
| Folder changed | Saving clears old photo references and placements immediately, then starts loading the new folder. |
| Ordinary reopen | Show retained thumbnails; completed imports require explicit Refresh to discover filesystem changes. |
| Refresh | Reconcile additions, removals and changed bytes, including same-name edits with unchanged timestamps. |
| Import in progress | Completed thumbnails become available before the last file finishes; the workspace stays usable. |
| Complete directory enumeration | Remove absent photo references and placements; an empty folder is a successful empty result. |
| Directory enumeration fails | Show retryable failure; do not infer deletion from an unreadable or unavailable folder. |
| Later read/storage failure | Keep completed work visible and show retryable failure rather than reporting completion. |
| Source changes during reading | Skip that unstable result until the next Refresh, with a backend warning. |
| Concurrent Refresh | Coalesce requests for the same chapter; a superseded folder's results cannot repopulate it. |
| Restart interrupts import | Keep completed thumbnails; resume or restart unfinished work when the chapter is next opened. |
| Reference removed or thumbnail replaced | Delete the old thumbnail once no live reference uses it; cleanup failure warns and retries on the next import. |

Deletion requires complete directory enumeration, not successful decoding of every JPEG. A failed enumeration retains prior references; a saved folder change has already cleared them.

An undecodable or unstable same-path edit leaves its last completed thumbnail/reference unchanged until a later successful Refresh; a new undecodable file creates no reference.

The spike established the browsing experience, not these production guarantees. API-triggered import, progressive publication, folder replacement and recovery were clarified with the maintainer after confirmation.

## Alternatives Considered

| Tried or considered | Decision |
| --- | --- |
| Separate grid/preview images and inspection panel | Rejected after visual review: one thumbnail and browsing only. |
| Capped rows with right-side gaps | Rejected after browser comparison: full-width chronological rows. |
| First browser read initiates import | Spike shortcut; creation must import even without an open browser. |

## Invariants

- Source paths remain relative to the chapter's absolute folder and cannot escape it.
- Chapter creation preserves its atomic metadata, countries and complete opening-spread composition.
- Existing album/spread ordering, capacity, URL selection, Cover and Reader behavior remain intact.

## Tradeoffs Accepted

- Editors wait for source reads and thumbnail generation; progressive loading exposes incomplete counts until import finishes.
- Byte-limited thumbnails lose detail relative to originals; editors get fast browsing rather than inspection fidelity.

## Acceptance Criteria

### Import and synchronization

- **I1:** Creating a chapter starts import without a browser read, including when its folder is temporarily unavailable.
- **I2:** Saving a different folder clears old references and placements immediately and starts importing the new folder.
- **I3:** Supported JPEGs load from the immediate directory; unsupported files are counted and symlinks never import.
- **I4:** An undecodable JPEG is skipped without per-photo error UI; remaining photos continue loading.
- **I5:** At least one ready thumbnail is browsable while a multi-photo import still has unfinished files.
- **I6:** Refresh adds new photos and updates same-name edited bytes even when file timestamps and sizes are unchanged.
- **I7:** Confirmed deletion removes the photo and its spread/cover placement, leaving the slot empty.
- **I8:** Empty directories complete with zero photos; enumeration failure preserves prior references and offers Retry.
- **I9:** Read/storage failures retain completed work, report failure and recover through Retry.
- **I10:** Concurrent requests produce one current-folder import; late results from an older folder are discarded.
- **I11:** Unstable reads publish no mismatched thumbnail/metadata; the next Refresh imports the settled file.
- **I12:** Reload retains completed thumbnails; opening after restart recovers an interrupted import.
- **I13:** Same-path edits retain photo identity and placement; unchanged Refresh neither duplicates nor replaces photos.

### Thumbnail delivery

- **T1:** Each published thumbnail is at most 300,000 bytes; 300,001 bytes cannot be accepted as success.
- **T2:** Thumbnails preserve orientation and whole-image proportions without upscaling, retaining detail at the agreed resolution.
- **T3:** Browser image delivery uses thumbnails exclusively; application storage contains no copied originals or separate image variants.
- **T4:** Replaced/deleted thumbnails cease being current; unused files are deleted, with cleanup retried after failure.

### Photo selector

- **P1:** The selected chapter's photos occupy a wider, collapsible left selector, initially about 320px on desktop.
- **P2:** Whole uncropped photos fill every row's available width, including the final row, with 3px gutters and no padded cards.
- **P3:** Row order follows capture time and the documented filename fallback; packing never advances later photos to fill gaps.
- **P4:** Mixed rows give portraits modest emphasis, approximately one-sixth more height than landscapes, vertically centered.
- **P5:** Show current `unplaced / total` counts derived from existing spread and cover placements; unfinished import is visibly pending.
- **P6:** A compact circular icon button named `Refresh photos` matches workspace controls, has breathing room and exposes pending status.
- **P7:** Photos have no preview, inspection, enlargement, click action or placement interaction.
- **P8:** The shared grip resizes by drag or keyboard and collapses/restores on activation, retaining the chosen width during workspace use.
- **P9:** At desktop and narrow widths, navigation and editor remain usable without horizontal page overflow; reduced motion is respected.
- **P10:** Failed/malformed photo responses show readable retryable feedback; switching chapters recovers without reloading the page.
- **P11:** Collapsed controls leave the focus order; collapse transfers focus to the grip and restore keeps focus usable.

### Spread navigation and metadata

- **N1:** A noncollapsible navigator above the editor shows Synopsis then individual spreads grouped in existing chapter order.
- **N2:** Consistent layout miniatures dominate each tile; compact centered Synopsis/destination captions occupy roughly 10-20% of its height.
- **N3:** Navigator captions omit chapter numbers and dates; photo spreads remain distinguishable through accessible names.
- **N4:** First/Previous/Next/Last and position count traverse individual spreads without wrapping; endpoints are unavailable with accessible context.
- **N5:** Direct links, reload and Back preserve the selected spread and chapter; the selected tile remains discoverable.
- **N6:** Source folders remain editable in chapter details but are absent from workspace narration and the photo selector.
- **N7:** Chapter metadata labels one selected country `Country` and multiple selected countries `Countries`.

## Doc Sync

| Owning document | Section | Changed fact |
| --- | --- | --- |
| `specs/design.md` | Album workflow | Replace the outline with grouped spread navigation; record chronological photo rows, shared resizing/collapse grip, retained width, icon Refresh, folder visibility and country labels. |
| `specs/design.md` | Values | Record immediate-folder JPEG scope, photo identity and capture-time fallback. |
| `specs/design.md` | Placements and gear | Record creation/Refresh/folder-change triggers, progressive ready-only publication, coalescing, supersession and retained inventory on ordinary reopen. |
| `specs/design.md` | Placements and gear | Record original-authoritative removal of photo references/placements and single-thumbnail orientation, proportions, resolution and no-upscale guarantees. |
| `specs/contracts.md` | Contract inventory | Link the authoritative photo inventory, import-status, Refresh and thumbnail HTTP contracts, including failures and byte limits. |
| `specs/operational.md` | Local service | Document thumbnail storage configuration, source read-only access, interrupted-import recovery and unused-thumbnail cleanup. |
