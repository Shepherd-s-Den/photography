# Album dashboard and open

**Date:** 2026-10-07

**Classification:** feature

**Grounded in:**

- UI spike: six-album pages, centered add/edit popups, directional paging, and card-to-read-only-open.
- Persistence spike: transactional creation, blank-title rejection, and rollback; V1 has no built-in template.
- User decision: defer composition viewing; Slice 1 reads only the Album resource, whose immutable `createdAt` is stored with the album in SQLite.

## What & Why

Replace the static entry point with a persisted-album dashboard at `/` where users add or rename albums, then open each album read-only at `/albums/{id}`.

## Domain / Design Notes

- The frontend is SolidJS/TypeScript. Use a typed, same-origin API client; fakes are test-only.

| Method and path | Request | Success |
|---|---|---|
| `GET /api/albums?page={page}` | `page` defaults to `1`; six per page | `200 {albums: Album[], page, pageSize, totalAlbums, totalPages}` |
| `POST /api/albums` | `{title}` | `201 Album` |
| `PATCH /api/albums/{id}` | `{title}` | `200 Album` |
| `GET /api/albums/{id}` | — | `200 Album` for that album only |
| `DELETE /api/albums/{id}` | — | `204` no body |

- `Album` is `{id, title, synopsis, createdAt}`; `createdAt` is immutable and stored on the Album row.
- HTTP adapters own local Circe codecs for request and response representations; application and domain types are not serialized directly.
- An out-of-range page returns the last page; an empty collection returns page `1` with `totalPages: 0`.
- Generate a stable UUID and immutable UTC `createdAt` for each album; list newest-first.
- Seed one built-in `AlbumStart` template in V1; clients supply no template ID.
- Album titles are nonempty after trimming; create and rename trim input before validation.
- SQLite enforces declared primary/foreign keys and unique chapter positions, with foreign-key checks enabled on every connection.
- V1 does not enforce template-kind, required-slot, or cover-side shape; creation validates these composition rules.
- Creation validates and writes the complete initial shape in one transaction.
- Album responses contain only Album fields; associated composition data is not part of their response.

New albums have one full-spread text slot, empty cover placements, and no chapters; slot and placement associations use stable IDs and arrays preserve domain order.

```mermaid
flowchart TD
Album["Album · empty synopsis"] --> Start["AlbumStart · position 0"]
Start --> TextSlot["One full-spread text slot"]
Album --> Cover["Cover · front spine"]
Cover --> Front["Empty front slot"]
Cover --> Back["Empty back slot"]
Album --> Chapters["No chapters on creation"]
```

## Acceptance Criteria

### Dashboard

- `/` shows “Album Layout Studio” with subdued “Album dashboard”.
- Show six newest-first cards per page and centered Previous / Page / Next controls.
- Page changes slide directionally; keep the current page visible while loading.
- Cards use a 42:28 cover area, bottom title extension, compact type, and turquoise focus halo.
- Empty covers use a turquoise/azure placeholder; selecting a card opens `/albums/{id}`.
- Show loading, successful-empty, and retryable list-error states.

### Add and edit

- Center both popups; support keyboard and pointer use, restoring focus on close.
- Trim titles; blank input shows validation and remains available to correct.
- Blank or whitespace-only titles return `400` with validation feedback; no title-specific error code.
- Create closes its popup and adds a card; opening remains a separate user action.
- Rename updates only the title; failure preserves the edit input and shows an error.
- Delete requires explicit confirmation; cancel leaves the album unchanged.
- Successful deletion removes the card; pagination falls back to the last available page.

### Open and persist

- Selecting, directly opening, or reloading `/albums/{id}` loads that album read-only.
- `GET /api/albums/{id}` returns only the requested Album `{id, title, synopsis, createdAt}`.
- Show loading until the album arrives; failed loads never render partial success.
- New albums have no chapters and empty start/cover placements.
- Create the valid initial composition atomically using the seeded built-in template.
- Malformed or unknown IDs on read, rename, or delete share `404 album_not_found`.
- Service failures use `500 service_failure`.
- Service failures offer Retry; missing albums offer a route back to the dashboard.
- The backend serves app assets and API on one origin; `/api/*` remains JSON.
- A browser journey creates, lists, opens, reloads, renames, and deletes using real persistence.

## Doc Sync

- `specs/design.md` — Architecture constraints: state that all backend APIs use REST over HTTP.
- `specs/contracts.md` — Contract inventory: point to the generated OpenAPI specification for the album API contract.
