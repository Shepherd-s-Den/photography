# Chapter photo import implementation slices

The proposal owns behavior. This plan assigns delivery responsibility; every slice starts only after the preceding PR merges and the lifecycle branch is reconciled.

Each Implementation PR must contain at most **1,000 additions plus deletions, including tests** against its merge base. Count the complete diff before either review round.

If a slice exceeds that ceiling, split at a real responsibility and revise ownership before implementation; never omit tests or split formatting from its behavior.

## Delivery sequence

| Slice | Responsibility and consumers | Criterion ownership | Independently verifiable outcome |
| --- | --- | --- | --- |
| 1 | Source inventory and JPEG metadata at the filesystem boundary | I3, I4, I11; ordering portion of P3 | Real temporary folders prove scope, symlinks, unsupported counts, EXIF ordering and unstable-read rejection. |
| 2 | Thumbnail generation and managed-file lifecycle | T1, T2, T4 | Real JPEG fixtures prove orientation, dimensions, exact byte boundary, replacement and safe cleanup/retry. |
| 3 | Persistent photo inventory and placement invalidation | I7, I13; durable-publication portion of I12 | SQLite seam proves uniqueness, stable IDs, atomic ready-image metadata and cascade deletion without deleting slots. |
| 4 | Import orchestration and single-instance coordination | I1, I2, I5, I6, I8-I12 | Application scenarios prove progressive results, triggers, deletion only after enumeration, coalescing and folder-version rejection. |
| 5 | Same-origin photo API, image delivery and runtime composition | T3; public integration of I1-I13 and T1-T4 | Real HTTP/runtime seams prove API-only creation starts work, status/Refresh/recovery work and originals cannot be served. |
| 6 | Photo-selector state and progress integration | P5-P7, P10 | Component scenarios prove progressive counts, Refresh/Retry, no photo action and failure isolation across chapter switches. |
| 7 | Chronological row layout and selector sizing/accessibility | P1-P4, P8, P9, P11 | Browser/layout tests prove full ordered rows, whole images, gutters, shared-grip resize, width restoration/retention and focus behavior. |
| 8 | Grouped spread navigation and metadata presentation | N1-N7 | Component/browser scenarios prove individual navigation, centered miniature captions, URL history and editable-only folders. |

Each row owns all enumerated failure cases for its criteria. Slice 5 integrates earlier guarantees rather than redefining their policy; slice 7 consumes slice 1 ordering without duplicating it.

## Design and validation obligations

| Boundary | Required design-map decision before tests |
| --- | --- |
| Source inventory | Caller-owned typed file results; filesystem containment, metadata decoding and change evidence stay at this boundary. |
| Thumbnail generation | Encoding is independent of scanning, synchronization and HTTP; choose and justify the image library in the first consuming slice. |
| Managed files | Own safe publication and removal; never delete a file still referenced by another photo or active import. |
| Persistence | Represent ready thumbnails and current source generation; atomically enforce supplied decisions and identity constraints. |
| Import application | Own triggers, progressive lifecycle, supersession and restart policy; coordinate with chapter mutations across the supported single backend instance. |
| HTTP | Consume application contracts only; validate wire data and define the OpenAPI operations/status representations before exposing them. |
| Frontend | Keep transport validation, import state, layout calculation and navigation responsibilities at their existing owning seams. |

Use existing photo identity, location, dimensions and placement contracts where they fit. Separate scanning, encoding, file storage and orchestration rather than transplanting the spike adapter.

No production dependency is selected by this Spec PR. A proposed image library must document its required JPEG/EXIF capability and pass maintainer review before introduction.

Each slice projects its criteria and edge cases into tests first, records the caller-first design map, and follows the current local shared/stack checklists.

Run the applicable AGENTS.md component gates and `dagger call verify`, both sequential independent reviews, dispositions and independent repair verification before each PR.

## Completion and archival

After all eight slices merge, compare the real local journey with the approved proposal and retained confirmed spike. Include source mutation, progressive loading and restart scenarios absent from the spike.

The separate Archive + Living Docs PR applies every Doc Sync entry, checks complete merged coverage and archives the proposal and this plan. Retain the spike until that PR merges.
