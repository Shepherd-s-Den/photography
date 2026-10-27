# Photography operations

> Standard: Agentic Engineering Standards v1.2.0

## Local service

Run `python3 scripts/local.py` from the repository root to start the current checkout for local development. The launcher creates `.local/` with owner-only permissions, stores the SQLite database at `.local/photography.db`, and prevents concurrent launcher instances.

The launcher installs locked frontend dependencies and builds assets before starting services; preparation failures stop startup. The backend binds to `127.0.0.1:8080` by default. When the checkout has a frontend, the launcher also starts it at `127.0.0.1:5173`. `PHOTOGRAPHY_HOST` and `PHOTOGRAPHY_PORT` override the backend bind settings, `PHOTOGRAPHY_FRONTEND_PORT` overrides the frontend port, and `PHOTOGRAPHY_SQLITE_PATH` overrides the database path. The backend defaults and optional environment substitutions are defined in `backend/src/main/resources/application.conf`.

Workspace folder suggestions use `/photos` as the default root, configurable through `PHOTOGRAPHY_PHOTO_ROOT` as an absolute path. Suggestions take the form `/photos/<year>/selection/<destination>`; editors can override them with a full backend-visible path. Saved chapter folders need not exist yet.

`GET /health` returns HTTP 200 with `{"status":"ok"}` after database migration, successful recovery of recorded photo writes and HTTP listener startup. Recovery failure aborts startup before import workers or HTTP are constructed. It reads recorded intents and current photo references, deletes only known unused thumbnail locations, and retains evidence when cleanup fails; it never discovers originals or enumerates thumbnail storage.

`PHOTOGRAPHY_THUMBNAIL_ROOT` overrides the absolute thumbnail root, default `/thumbnails`. Recovery preserves currently referenced thumbnails and removes each unused publication's known `.jpg` and `.tmp` artifacts idempotently. Concrete original discovery, image generation and live import journaling remain inactive; this backend slice provides persistence and startup recovery, not an enabled import journey or atomic source-change clearing.

The thumbnail root must be normalized and its path ancestors must be nonsymlinks. Keep this application-owned path and its ancestors stable while the backend runs; operations reject existing symlink redirection rather than following it.

The launcher checks its tools and ports before starting, then polls readiness for up to 180 seconds. It prints each local URL after readiness and stops its child processes and releases its lock when it exits.
