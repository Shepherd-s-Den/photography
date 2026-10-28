# Photography operations

> Standard: Agentic Engineering Standards v1.2.0

## Local service

Run `python3 scripts/local.py` from the repository root to start the current checkout for local development. The launcher creates `.local/` with owner-only permissions, stores the SQLite database at `.local/photography.db`, and prevents concurrent launcher instances.

The launcher installs locked frontend dependencies and builds assets before starting services; preparation failures stop startup. The backend binds to `127.0.0.1:8080` by default. When the checkout has a frontend, the launcher also starts it at `127.0.0.1:5173`. `PHOTOGRAPHY_HOST` and `PHOTOGRAPHY_PORT` override the backend bind settings, `PHOTOGRAPHY_FRONTEND_PORT` overrides the frontend port, and `PHOTOGRAPHY_SQLITE_PATH` overrides the database path. The backend defaults and optional environment substitutions are defined in `backend/src/main/resources/application.conf`.

Workspace folder suggestions use `/photos` as the default root, configurable through `PHOTOGRAPHY_PHOTO_ROOT` as an absolute path. Suggestions take the form `/photos/<year>/selection/<destination>`; editors can override them with a full backend-visible path. Saved chapter folders need not exist yet.

`GET /health` returns HTTP 200 with `{"status":"ok"}` after the service has migrated the database, reconciled pending photo-write intents and started the HTTP listener. The launcher checks its tools and ports before starting, then polls readiness for up to 180 seconds. It prints each local URL after readiness and stops its child processes and releases its lock when it exits.

## Production deployment

Mount original photo directories read-only and the application thumbnail root read/write on the NAS. Thumbnail temporary/final writes share that filesystem; SQLite stores locations and metadata, not image bytes. Back up the database together with its referenced thumbnail storage; original files remain independently owned.

| Environment setting | Default | Contract |
| --- | --- | --- |
| `PHOTOGRAPHY_PHOTO_ROOT` | `/photos` | Absolute root for chapter-folder suggestions; saved folders are full backend-visible paths. |
| `PHOTOGRAPHY_THUMBNAIL_ROOT` | `/thumbnails` | Absolute writable managed-thumbnail root, separate from read-only originals. |
| `PHOTOGRAPHY_THUMBNAIL_QUALITY` | `standard` | `low`: 1024px/75,000 bytes; `standard`: 1536px/150,000 bytes; `high`: 2048px/300,000 bytes. |

Settings flow through HOCON and PureConfig into typed configuration. Unknown quality or invalid roots fail startup explicitly. Presets bound the longest edge and encoded bytes, not a target output size; Refresh applies changed quality while retaining photo identity and placement.

Startup migrates SQLite and reconciles pending photo-write intents before accepting requests or starting workers. Reconciliation preserves referenced files and deletes only journaled unused locations; failed reference reads/cleanup block readiness and retain evidence. It does not scan originals or regenerate photos. Opening/reconnecting displays retained photos; explicit Refresh retries interrupted import.

Serve `/api/albums/{id}/photo-events` as an uncached long-lived `text/event-stream`, with proxy buffering disabled and response timeouts allowing the stream to remain open. The server sends heartbeat comments every 15 seconds and `X-Accel-Buffering: no`; preserve streaming and reconnect behavior through the reverse proxy. SSE contains invalidations, never thumbnail bytes.

Inventory/status responses use `Cache-Control: no-store`; managed thumbnail URLs use `private, max-age=31536000, immutable` with content-version ETags. Changed output gets a new URL. Preserve these headers and keep browser/proxy original-file access separate from thumbnail delivery.
