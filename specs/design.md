# Photography design

> Standard: Agentic Engineering Standards v1.2.0

## Overview

This repository is the starting point for a photography application. Product concepts and user workflows will be recorded here when established by reviewed change specs.

## Domain model

The Scala domain package defines the domain records, identities, refined scalar types, and closed alternatives. Use cases must enforce cross-record, lifecycle, and geometry invariants; adapters validate external representations and route mutations through those use cases.

### Ownership and composition

- An album has exactly one album-start spread and one cover. A chapter belongs to one album and has exactly one chapter-start spread; a photo belongs to one chapter.
- An album has at most 20 chapters with unique positions from 0 through 19. Album-start and chapter-start spreads occupy implicit position zero. Photos-only spreads have unique positions within their chapter, beginning at one. Reject an added 21st chapter as `ChapterLimitReached`, a reused chapter position as `ChapterPositionOccupied`, and a reused photos-only position as `SpreadPositionOccupied`.
- Each spread retains its template identity, and a spread's kind must match its template kind; reject a mismatched template kind as `SpreadTemplateKindMismatch`.
- Chapter source folders are relative to the album's photo root; photo sources are relative to their chapter's folder. Neither location may escape its owning root. Chapter source folders are unique within an album, and photo sources are unique within a chapter. Reject duplicate paths as `DuplicateChapterSourceFolder` or `DuplicatePhotoSource`.
- Visit periods require `start < end`. Photo dimensions are positive pixel counts. Domain names are nonempty, country codes are two uppercase ASCII letters, and country flags are absolute filesystem locations.
- Country codes, camera EXIF codes, and lens EXIF codes are unique within their respective entity kinds; reject duplicates as `DuplicateReferenceCode`.

### Templates, spreads, and covers

- Album-start and chapter-start templates have exactly one text slot; photos-only templates contain only photo slots. Every template has at least one slot. Reject templates that violate these rules as `InvalidSpreadTemplate`.
- Applying a template creates the corresponding typed spread slots and copies each slot's geometry. New photo slots have no bleed edges; only photo slots can have bleed edges. Slot order is spatial, with no separately stored order. Text slots render their owning album or chapter fields and have no separate text placement.
- Photo placements accept only photo-slot identities; a text-slot identity cannot be used for a photo placement.
- Slot boxes use nonnegative absolute positions and positive distances in InDesign points, and fit within the Saal Digital 42 × 28 cm two-page spread (`2381.102 × 765.354 pt`).
- A cover belongs to one album and has distinct front and back cover-slot identities. Cover-slot identities are separate from spread-slot identities. Cover slots have no slot boxes or bleed edges.
- The finished interior page count is twice the album's spread count. Select the smallest listed Saal Digital cover specification whose page count meets or exceeds that count; for counts above 130, use the 130-page specification. Derive cover geometry from the selected page-count specification, including its spine, wrap, and bleed dimensions. `SpineOwner` determines which panel's photo frame extends across the spine.
- A cover may remain incomplete during composition. Completing it requires placements for both cover slots; an earlier completion attempt is rejected as `IncompleteCover`. Cover placements are separate entities created when a photo is assigned.

### Placements and gear

- A photo has at most one placement in an album, and a slot has at most one placement. Duplicate photo assignments are rejected as `PhotoAlreadyPlaced`; occupied slots are rejected as `SlotOccupied`.
- Chapter-spread placements use photos from that chapter. Album-start and cover placements may use any photo in the album. Cross-chapter assignments are rejected as `PhotoFromDifferentChapter`; cross-album assignments are rejected as `PhotoFromDifferentAlbum`.
- A photo transform has a two-axis shift, mirroring, and positive zoom with no independent upper limit. A transform must cover the entire slot; otherwise reject it as `PhotoDoesNotCoverSlot`.
- A lens belongs only to an interchangeable-lens camera. Fixed-lens cameras use `CameraUse.FixedLens`; interchangeable-lens cameras use a nonempty set of their own lenses. Invalid camera/lens associations are rejected as `LensRequiresInterchangeableCamera` or `InvalidGearSelection`.

### Editor sessions

- `EditorId` is a persistent pseudonymous browser-profile identity, stable across that profile's tabs and reconnects. It is distinct from User-Agent metadata and display aliases, and is not authentication. `SessionId` identifies the active session. Each `EditorId` has at most one live session.
- A profile without a live session creates one in its first active tab. Opening another tab for that profile while a session is live offers takeover or keeping the existing tab. Keeping leaves the new tab inactive and creates no session.
- Taking over atomically removes the old session, releases its locks, and creates a new session for the same `EditorId`. The old `SessionId` can no longer edit or renew. A session is live from its last heartbeat until (but not including) its derived expiry.

Business-rule rejections use `DomainRejection`. Missing records, lock/session outcomes, infrastructure failures, and changed/unchanged mutation reporting are use-case concerns.

## Architecture constraints

- The backend is Scala 3 and follows Typelevel and Tagless Final principles.
- The frontend is intended to use SolidJS and TypeScript.
