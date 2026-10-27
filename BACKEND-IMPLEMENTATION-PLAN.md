# Photography full-stack implementation plan

This is the single, local-only progress tracker for rebuilding the album layout tool. Keep it at the repository root, update it between agent sessions, and record PR links when work is merged. Do not commit this tracker.

Status: `[ ]` not started, `[-]` in progress, `[x]` merged and complete.

## Product direction

Rebuild the SolidJS album-layout experience piece by piece. Treat the backup as evidence of user journeys and ergonomics, not as an API or persistence contract. The new application owns album and editing behavior; SQLite is authoritative for persistent application data. Keep image originals in configured photo storage and persist their identity and authored metadata in SQLite. Do not replace the old JSON album file with a JSON blob in the new database.

The backup UI journeys reviewed so far are the album dashboard and startup/open flow; photo discovery and browsing; spread/template navigation and editing; cover composition; synopsis display; export; and save/conflict/presence feedback. Its local/NAS mode switch and album-copy synchronization are legacy behaviors, not requirements to reproduce by default. The backup has a dashboard but no dedicated chapter-management screen; the new Scala domain does model chapters, so how those appear in the rebuilt UI is an explicit design decision below.

## How to deliver each product slice

Use completed Slice 1 as the delivery reference: [archived spec](specs/changes/archive/2026-10-24-album-dashboard-and-open/proposal.md), focused implementation PRs #52/#55/#56/#57, and [archive + living docs PR #58](https://github.com/cosminci/photography/pull/58). The local [SDD skill](.agents/skills/sdd/SKILL.md) governs the lifecycle.

1. **Spike and confirmation:** explore the real journey and risky domain/persistence/boundary assumptions in a dedicated uncommitted spike worktree; get the user's confirmation before the spec. Retain it for comparison until the archive PR merges. Feature delivery stays in the primary checkout.
2. **Spec PR:** describe the complete cross-stack journey, observable contracts and Doc Sync. Resolve known scope, URL, request/response, validation, ordering, loading/empty/error/retry and focus decisions up front. Use tables and diagrams; keep implementation layout and task breakdown out of the proposal. Amend during implementation only for genuine discoveries, before affected tests.
3. **Implementation decomposition:** after the spec merges, split its delivery into focused, dependency-ordered PRs at meaningful behavior or adapter seams. Choose the split from the work rather than fixing a PR count or creating adapter-specific specs. Derive seam scenarios before implementation and verify each assembled slice.
4. **Archive + living docs PR:** after all implementation PRs merge, run the complete local persisted journey, apply Doc Sync and archive the spec. Add no new product behavior.

The frontend owns rendering, keyboard/pointer interactions, and ephemeral UI state. Application rules and persistent mutations go through backend use cases; do not send whole-album snapshots as the write contract. Keep ordinary reads and commands on HTTP. Add WebSocket only if a concrete same-album presence interaction needs live updates.

Use the current gates in `AGENTS.md`; Dagger validates remote delivery and `scripts/local.py` automatically prepares and runs the local app. Every PR completes two sequential fresh internal reviews with adjudicated findings before publication. Keep one feature branch in the primary checkout throughout the lifecycle; after each maintainer merge, reconcile squash-merged work onto fresh main without replaying it.

## Prerequisite — frontend, coverage, and spike foundation

Keep this repository-infrastructure PR separate from Slice 1. The maintainer explicitly waives a change spec for this setup-only PR; it adds no product behavior.

- [x] Establish the SolidJS/TypeScript project structure, informed by Plant Journal but sized for this application.
- [x] Add the frontend build, type, test, and coverage reporting needed by the Dagger checks.
- [x] Make Dagger build/verification exercise the real frontend and enforce the repository's coverage policy.
- [x] Keep the spike procedure in the SDD skill: start from pinned `main` in the primary checkout, use normal app paths and the generic local launcher, run normal quality checks except coverage, and iterate with the user before the spec.
- [x] Merge this prerequisite before the Slice 1 implementation PRs.

**Status:** Setup PR #19 is merged: https://github.com/cosminci/photography/pull/19. It adds the SolidJS/TypeScript foundation and keeps spike procedure in the SDD skill without prototype-specific launcher behavior or an SBT change.

## Prerequisite — Service foundation

This is infrastructure, not a user-facing vertical slice. Complete the reviewed service-foundation change before adding product behavior.

- [x] Spec PR #16 — https://github.com/cosminci/photography/pull/16; its proposal commit `fde90f5` is in live `origin/main`.
- [x] Merge the approved proposal before implementation, as required by SDD.
- [x] Implement the approved SQLite migration/startup and HTTP readiness lifecycle (implementation commit `b443d9f` is in `origin/main`).
- [x] Establish the 100% Scala statement/branch coverage gate through `dagger call backend-check`.
- [x] Ensure Dagger verification exercises the real SolidJS app, its tests and production asset build.

**Status:** service-foundation PR #16 is merged. Its proposal, implementation, and archive commits (`fde90f5`, `b443d9f`, `d8a4e90`) are present in live `origin/main`.

**Done when:** the composed service starts only after required resources are ready, exposes readiness, shuts down resources cleanly, and passes its Dagger checks.

## Slice 1 — Album dashboard and open

**Journey:** a user sees persisted albums, creates one, and opens the selected album; returning later or reloading still shows the same album.

**Status:** merged and complete as of 2026-10-24. Spec PR #28, SQLite adapter #52, HTTP adapters #55, frontend asset serving #56, SolidJS dashboard/management/open and launcher automation #57, and archive + living docs #58 are merged. Earlier #29/#30/#48 delivery assumptions are historical, not outstanding work. The complete persisted browser journey and Dagger gates pass.

**Domain correction:** creation persists an `Album`, its album-start `Spread`, and a `Cover` as distinct domain objects in one transaction. `Spread` is the single spread model; Slice 1 reads only the Album resource, with composition reads deferred.

**Frontend**

- [x] Build the SolidJS + TypeScript application entry point and typed same-origin API client.
- [x] Build the confirmed calm azure/turquoise dashboard with unified 42:28 cover/title cards, loading and retryable errors. Keep one top-right Add action and a blank empty collection; show existing albums only, not a template gallery.
- [x] Open the selected card at `/albums/{id}` with read-only title/synopsis below navigation, without a read-only label or creation timestamp. Composition viewing remains for the workspace slice.
- [x] Paginate newest-first albums at six per page with centered Previous / Page N / Next controls.
- [x] Slide cards directionally while retaining the current page during loading.
- [x] Add albums through a centered name dialog using the lowest-ID AlbumStart template and both empty cover sides, without seeding a chapter.
- [x] Delete only after explicit irreversible confirmation; clamp pagination after deletion.
- [x] Keep `/` and `/albums/{id}` addressable; native add/rename/delete dialogs restore focus and support keyboard/pointer use. Separate dialog URLs were not part of the approved delivered contract.
- [x] Document five album operations and template listing, exact metadata responses, validation/pagination/errors, and same-origin asset/SPA behavior in the approved spec and living OpenAPI contract. Album GET excludes composition.
- [x] Keep local/NAS mode and synchronization controls outside this journey.

**Backend and persistence**

- [x] Decide the create response and album-list summary from the current domain, using stable album IDs rather than copying backup slugs or wire fields.
- [x] Implement list, create, and Album-resource read as use cases with explicit absent/invalid/failure outcomes.
- [x] Make creation transactional and persist the Album, required album-start Spread, and front/back Cover slots as separate domain objects.
- [x] Seed four legacy synopsis-right AlbumStart layouts; creation selects the lowest-ID layout without a client-supplied template ID.
- [x] Implement SQLite reads/writes and HTTP routes; test empty listing, create/read-after-write, malformed IDs/names, missing albums, and rollback on failed initialization.
- [x] Define which summary fields are genuinely useful and derivable (for example, spread/photo counts). Do not fabricate backup-only fields such as a content hash or modification timestamp if the new schema does not own them.
- [x] Serve the built frontend resource tree and preserve extensionless SPA deep links while keeping `/api/*` available to JSON routes.

**Completion evidence:** the full backend-served create/list/open/reload/rename/delete, paging, responsive/error/focus/native-animation journey passed after #57 merged; local user database files were preserved. Two fresh whole-feature archive reviews completed without findings before #58. Local evidence remains under `.agent-work/album-archive/`; the archived proposal is the worked example linked from SDD.

## Slice 2 — Album workspace structure

**Journey:** an editor opens an album, understands its organization, and selects the part of the album to work on.

**SQLite delivery baseline (2026-10-26):** PR #60 is squash-merged at `a1e01d298145c57ffd56db8979e37ba101095d08`; its published head `d673682` has an identical tree. Fetched main over the existing SSH remote and reconciled the same feature branch with autostash, preserving local edits and retaining no merged core commits. SQLite implementation has not started: first resolve whether concurrency guarantees are scoped to one shared manager Mutex or require an authorized revision/coordination contract change for independent writers. This supersedes historical pending-core and adapter-policy descriptions below.

**SQLite candidate (2026-10-26):** implemented atomic chapter/reference/opening/supplied-order writes, isolated metadata changes,249 ISO countries/27 exact Saal opening layouts, preserved-V1 additive migration/integrity and shared runtime composition. Maintainer confirmed one backend instance for concurrent UI users; shared-manager coordination is tested separately from database rollback/integrity, without multi-process decision claims. Final backend/all-component gates pass65 backend tests/100% measured coverage;28 SQLite/architecture seam tests pass. Two fresh full review rounds plus independent final repair verification close all findings. SQLite publication/merge is the next lifecycle gate; HTTP/frontend/archive remain deferred. Existing local guidance/archive edits, data, untracked tracker/script and retained spike remain excluded.

**SQLite published:** [PR #61](https://github.com/cosminci/photography/pull/61) is OPEN/ready at signed commit `513fd0f4ca0deceec1210a1c0b04510a87502dbc`. Exact hosted head/body verified; normal SSH push passed verification hooks. No force push or merge. Await maintainer merge before fetching/reconciling the same feature branch for HTTP.

**Hosted SQLite checks:** changed-component verification and all CodeQL checks pass; the Dependabot-only auto-merge job is intentionally skipped. The SQLite delivery is ready for maintainer review/merge.

**Current ownership correction:** [PR #60](https://github.com/cosminci/photography/pull/60) remains the core delivery. Signed correction `0d9d18078584373b856f418b681971d43a225dfb` is pushed; exact hosted head/description and all required checks are verified, PR OPEN/ready/CLEAN. ChapterManager directly decides capacity, Home/type preservation, folder ownership and chapter order under a shared F-bound mutex; public create/update expose orchestration. Persistence receives typed metadata and ordered IDs, never a policy callback. Two fresh sequential read-only reviews independently close the original blockers and additional changed-country test gap; current proposal rendered-density verification and final full Dagger pass52 backend tests with100% measured coverage. Original immutable-year, arbitrary-title and multi-country amendments remain. Concrete chapter persistence and real-database concurrency/rollback proof remain the next SQLite PR. No merge or next delivery; wait for maintainer merge confirmation.

**Published maintainer cleanup:** Signed commit `44575a6c1af05e7c9e9f30f4b3f253dc25da4e17` is pushed to PR #60. Redundant API commentary is removed; critical sections explain their races. Operation names use verbs, immutable fixtures are values, and ambiguous arguments are named across backend/launcher callers, with frontend/pipeline renames propagated. Two fresh reviews plus independent final repair verification close all findings. Final module/full Dagger gates, normal pre-push hooks and hosted checks pass; exact hosted head matches. Preserved local files/data/spike remain excluded and unchanged. No merge or next delivery.

**Preserved local state:** Seven guidance edits remain local/uncommitted; the archived dashboard proposal's extra Doc Sync, original data, untracked plan/script and all20 retained spike source hashes are preserved. Owned verification services are stopped. Previous repair commit99d4b97 is superseded by the current amendment above.

**Preserved running spike:** uncommitted source at `/Users/ciobanu/Personal/photography/.agent-work/album-workspace-structure/spike/worktree`, detached at `35a1ee7`; retain through implementation and archive merge. From the primary checkout run `python3 -u .agent-work/album-workspace-structure/spike/run.py`, delegating to that worktree's `scripts/local.py`, isolated scratch SQLite, backend 8090 and frontend 5174. The sample remains http://127.0.0.1:5174/albums/3a7654cf-d7b0-44d6-a9f2-84370d4cee78. Source hashes, snapshot, browser journey and motion evidence are beside the worktree. The full persisted journey and motion checks passed after transfer. Original user database and prior SDD/archive edits remain unchanged.

**Confirmed presentation:** retain one workspace heading, `A year in photographs / <year>`. Albums initially use `Journey of the Shepherds <year>` but can be freely renamed; cover shows the saved title. Cover credits are `by Cosmin & Mara` through 2025 and `by Cosmin, Mara & Iacob` from 2026. Year is required at creation and immutable. The retained spike predates these maintainer corrections.

**NAS folder decision:** persist one required backend-visible full path, for example `/photos/2027/selection/Home`, suggested from album year/destination and overridable. The chapter has no separately writable relative folder or root. Folder existence and photo discovery remain outside this slice. The retained spike's root-plus-relative-folder representation predates this correction.

**Current navigation decision:** after trying the header action, the user chose a toggle rather than continuing the three alternatives. The running Spreads / Cover toggle remembers the selected interior part while switching; Cover hides the chapter outline. Home shows `Throughout the year` instead of dates. Once Home exists, chapter-type selection disappears from add/edit dialogs; new chapters are vacations, and editing Home retains its kind.

**Country selection:** all 249 ISO 3166-1 country/territory entries are populated in scratch persistence during automated preparation. The picker has search at the top, filters names/codes in the browser, and supports keyboard selection and explicit no-match feedback. Slice 2 remains chapter structure/selection and read-only composition; spread editing and template selectors stay in Slice 4.

**Progressive setup:** creation asks for year before persistence, defaulting to the browser's current year. Add chapter initially shows only the type selector when Home is available; choosing a type reveals its fields. When Home already exists, add opens directly as a vacation. Home has country and photo folder, with no location or date inputs. New chapters default to Romania (editable); edits retain the saved country.

**Date correction:** display and enter dates as `dd/mm/yyyy`. Vacation date years default to the album year but are independently editable; trips may fall outside that nominal year or cross New Year. Both frontend and backend reject impossible/reversed dates without imposing album-year bounds. Home still spans its album year. Validation uses readable inline feedback rather than browser-native tooltip bubbles.

**Canvas-first refinement:** the workspace uses nearly the full window width with restrained outer gutters, compact headers and 6px preview framing for spreads/cover. Metadata has small muted labels, darker semibold values and subtle group separators. Sidebar-local chevrons collapse/reopen the outline. No Edit chapter button: selecting a chapter establishes the editing context; clicking its metadata opens chapter details.

**Global reader:** one toolbar control works from both Cover and Spreads. Actual browser fullscreen hides workspace chrome/metadata and fits the physical canvas without stretching. The sequence starts with Cover, then follows persisted spreads across chapters without wrapping; left/right arrows navigate and Escape or Exit reader retains the current selection. Reader controls appear on pointer hover or keyboard focus. Spread composition remains read-only in this slice.

**Sidebar motion:** match plant-journal's restrained slide: 180ms ease horizontally on desktop, 260ms vertically on narrow screens, with composition space changing smoothly alongside it. Reduced-motion users get an immediate transition; hidden chapter controls are inert and focus transfers to the visible toggle.

**Chapter opening initialization:** the user chose automatic default selection, like album creation, rather than a template picker in Add chapter. Seed the 27 existing chapter-start layouts; the spike defaults to `Text + 2P + 2L` (four photos and a text column). Existing spread geometry stays unchanged. Template selection remains in Slice 4.

**Frontend delivery:** the maintainer requested a website favicon in this slice's frontend implementation PR; include it in the spec and that seam's verification.

**Capacity correction:** published to PR #59 in signed commit `05ab58e` after two fresh amendment reviews without actionable findings, fresh rendered-density/wrapping checks and passing Dagger/pre-push gates. The maintainer removed the artificial chapter cap. Saal capacity is 65 interior spreads / 130 pages, counting all opening and photo spreads, excluding Cover. The spec covers the final available opening and full-album rejection; remove the old chapter-position ceiling across core and adapters during implementation.

**Implementation sequence:** after spec merge confirmation, deliver domain + use cases + ports together, then one focused PR per affected adapter (SQLite before HTTP), then frontend including favicon. Work sequentially on the same feature branch in the primary checkout; wait for maintainer merge confirmation and reconcile fresh main after every PR. Keep composition wiring in the seam that activates each adapter; every PR must compile and pass its gates independently.

**Next-phase prompt:** `.agent-work/album-workspace-structure/next-phase-prompt.txt` contains the copy-ready handoff, starting with verification that #59 merged and ending at publication of the backend-core PR.

- [x] Spec PR #59 merged and feature branch reconciled onto fresh main.
- [x] Delivery order fixed: core (domain/use cases/ports), SQLite, HTTP, frontend including favicon, then separately reviewed archive. Wait for merge confirmation and reconcile the same branch after each PR.
- [x] Backend core PR #60 merged; same feature branch reconciled to its squash commit on updated main.
- [-] SQLite adapter PR #61 is published; await maintainer merge. Implementation/reviews/final gates complete. Atomic writes, supplied order, capacity boundary, positions beyond20, multi-country isolation, Home/folder constraints and exact catalogs have real SQLite seam proof.
- [ ] HTTP adapter PR: implement required-year creation/metadata/year changes, canonical-title compatibility, workspace/chapter routes, exact source decoding/error contracts and owning configuration/composition.
- [ ] Frontend PR: confirmed SolidJS workspace journey/favicon, assembled local demonstration and real persisted browser proof.
- [ ] Derive contract tests and observe relevant failures before implementation; exercise real persistence and adapter seams.
- [ ] Implement year/title, chapter/reference mutations and read-only workspace contracts.
- [ ] Deliver the confirmed SolidJS journey, addressable navigation, Reader and favicon; show assembled UI before its implementation PR.
- [ ] Verify the complete persisted journey against the spec and retained spike, synchronize living docs and archive.

**Done when:** the chosen album organization is visible and navigable, and every displayed chapter/reference value comes from persisted domain state.

## Slice 3 — Photo library and discovery

**Journey:** an editor discovers photos in the selected chapter's configured source, browses previews, and can tell which photos are already placed.

- [ ] Build the photo panel with folder filters, chronological/name ordering, used/unused indication, progress, empty state, and explicit scan errors.
- [ ] Define configured photo roots and allowed relative paths before exposing discovery. Never accept an arbitrary absolute path from a browser request.
- [ ] Implement discovery and metadata extraction behind filesystem ports; persist photo identity, dimensions, capture time, source folder, and authored caption metadata in SQLite.
- [ ] Generate or serve thumbnails through a separate storage/cache boundary with explicit cache invalidation behavior.
- [ ] Repeated discovery must not duplicate photos or erase user-authored data. Define stale/missing-file behavior and supported/corrupt image handling.
- [ ] Test with temporary image files and SQLite, including traversal/symlink escapes, unsupported files, repeat scans, and missing sources.

**Done when:** the user can scan an allowed source and browse stable, persisted photo records and previews without the browser owning filesystem access or album persistence.

## Slice 4 — Spread navigation and layout

**Journey:** an editor moves through an album's spreads, chooses a suitable layout, and changes the spread sequence.

- [ ] Rebuild the template selector, category filtering, hide/unhide, lock behavior, active-template focus, and bleed controls.
- [ ] Rebuild spread navigation, the filmstrip, active-spread state, add/remove/reorder actions, guide visibility, and keyboard navigation.
- [ ] Implement template reads and spread append/apply/reorder operations through use cases and SQLite ports.
- [ ] Preserve occupied-photo content when changing a layout, or return an explicit conflict when a requested layout would discard it.
- [ ] Keep UI scale, panel collapse, and guide visibility as browser preferences unless they affect shared album content.
- [ ] Test ordering, contextual templates, hidden/locked templates, occupied slots, and the boundary between album-start and chapter spreads.

**Done when:** an editor can navigate and shape the spread sequence using the selected chapter's persisted templates without losing existing placements.

## Slice 5 — Photo placement and editing

**Journey:** an editor assigns, moves, swaps, adjusts, captions, and removes photos on a spread.

- [ ] Rebuild drag/drop from the photo library to a slot and between slots; distinguish move, swap, and replacement outcomes.
- [ ] Add crop/zoom/pan, horizontal flip, reset, caption editing/placement, and per-edge bleed controls.
- [ ] Implement assignment, move, update, and removal as atomic use cases; enforce album/chapter ownership, one-placement constraints, and surface edit ownership.
- [ ] Define caption update semantics so keep, set, and clear cannot be confused; do not overwrite shared photo caption data from a stale placement projection.
- [ ] Keep gesture previews local while dragging, then submit a coherent command. Define undo/redo behavior over commands or domain changes; do not restore whole JSON snapshots.
- [ ] Test concurrent/invalid placement, cross-chapter moves, caption clearing, crop boundaries, and undo/redo outcomes.

**Done when:** each visible edit is an explicit backend command, reload returns the same composition, and domain conflicts cannot silently discard another edit.

## Slice 6 — Album narrative and synopsis

**Journey:** an editor reads and updates the album synopsis and chapter descriptions, and sees the resulting text/metadata in the relevant layout preview.

- [ ] Rebuild the synopsis panel and text-slot preview, including country flags where supported.
- [ ] Replace filesystem Markdown as an implicit source of truth with explicit SQLite-backed album/chapter content; define any one-time import separately if existing NAS content is to be reused.
- [ ] Implement synopsis/description reads and writes with validation, locking, and a defined save/error state.
- [ ] Test blank content, multi-line text, Unicode/diacritics, country associations, and reload.

**Done when:** narrative content has one authoritative persisted representation and the preview reflects saved state.

## Slice 7 — Cover composition

**Journey:** an editor assigns front/back photos, adjusts their framing, chooses the spine owner, and checks print guides.

- [ ] Rebuild the cover workspace, front/back slots, spine-owner control, crop/zoom/pan/reset, guide overlays, and generated title preview.
- [ ] Implement cover read and mutations through locked, transactional use cases and SQLite adapters.
- [ ] Derive dimensions and frames from the active cover specification; do not duplicate print geometry in the HTTP or frontend boundary.
- [ ] Test empty sides, one/both assigned photos, spine ownership, valid page counts, crop bounds, and incomplete-cover behavior.

**Done when:** cover layout reloads from persisted state and its geometry agrees with the print/export projection.

## Slice 8 — Print preview and export

**Journey:** a user requests a print-ready representation and downloads the output accepted by the existing InDesign consumer.

- [ ] Inspect the actual consumer and fixture before fixing the new export contract.
- [ ] Rebuild the export action and visible progress/error/success feedback.
- [ ] Assemble ordered chapters, spreads, placements, narrative, cover, and photo references from SQLite-backed state.
- [ ] Implement pure layout/cover transformations separately from serialization and any file-writing adapter.
- [ ] Test page-count boundaries, missing photos, incomplete layouts/covers, encoding, deterministic output, and consumer compatibility.

**Done when:** export is reproducible from persisted domain state, matches the real consumer contract, and does not mutate the album.

## Slice 9 — Same-album collaboration (conditional)

**Journey:** if multiple editors work on one album, each can tell when another editor is present or owns the surface they need.

- [ ] Before implementing live events, identify the exact UI interaction that needs them and why HTTP reads or refresh are insufficient.
- [ ] Build session/lease and surface-lock behavior with explicit frontend states for available, owned-by-me, owned-by-another-editor, expired, and conflict.
- [ ] Keep normal reads and mutations on HTTP. If needed, add a WebSocket that only reports same-album presence/ownership; it must not become a general album state-sync or command channel.
- [ ] Test two clients in one album, isolation between albums, stale-session/lease expiry, reconnect, and disconnect cleanup.

**Done when:** either the need is disproved and the slice is explicitly deferred, or the specific same-album presence interaction works without general-purpose WebSocket synchronization.

## Later operational decisions

- [ ] Decide whether existing NAS JSON albums or Markdown descriptions need a one-time import. Do not infer an import format or mutate the live NAS data before inspecting representative files and agreeing on a safe read-only procedure.
- [ ] NAS access guidance found in the local home-automation project uses its SSH helper and `NAS_HOST` / `NAS_SSH_KEY` configuration; `ABS_SSH_LOCAL=1` selects local-shell mode. No NAS connection, database read, or snapshot has been made, and no photography-specific safe import procedure has been verified.
- [ ] Decide the supported deployment topology, SQLite location/backup policy, and configured photo-root access before documenting or implementing NAS deployment.
- [ ] Add operational packaging, startup/restart/backup guidance, and an end-to-end smoke journey only after those deployment choices are agreed.

## Session handoff

Current checkout: `feature/album-workspace-structure` at signed SQLite delivery `513fd0f4ca0deceec1210a1c0b04510a87502dbc`, based on verified PR60 squash merge `a1e01d298145c57ffd56db8979e37ba101095d08`. Published [PR #61](https://github.com/cosminci/photography/pull/61) contains only the SQLite seam and necessary composition/documentation. Both fresh full reviews and independent final repair verification are complete; final backend/full Dagger gates pass65 backend tests/100% measured coverage. Original user edits, untracked tracker/script and retained spike remain preserved.

Stop at the published SQLite PR; never merge it. After maintainer merge confirmation, fetch fresh main and reconcile this same branch without replaying squash-merged work before HTTP, then frontend/favicon and final archive. SQLite now has separate real transaction/rollback, reference/composition preservation and shared-backend concurrency evidence. The supported topology is one backend instance with a shared chapter-manager Mutex; no multi-process business coordination is claimed. Gear, photo rendering and print-accurate covers remain later scope.

The spike launcher session exited with code 130 after spec publication; do not assume its services are running. Source, scratch data and automated launch command remain preserved in the worktree documented above. Keep it uncommitted and rerunnable until implementation is compared and the archive PR merges; remove its owned runtime/worktree only then. Preserve prior edits, scratch user albums and the original database. Do not restore old branches/stashes. Seeded coordinates are full-spread percentages; preserve the physical spread ratio.

At the end of each session, update this file with the active slice, merged PR links, unresolved decisions, and the next unchecked seam. A product slice is complete only after its UI and real backend work are merged and the assembled journey is verified through Dagger; an isolated API or mock-backed screen is not a completed slice.
