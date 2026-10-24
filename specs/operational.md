# Photography operations

> Standard: Agentic Engineering Standards v1.2.0

## Local service

Run `python3 scripts/local.py` from the repository root to start the current checkout for local development. The launcher creates `.local/` with owner-only permissions, stores the SQLite database at `.local/photography.db`, and prevents concurrent launcher instances.

The launcher installs locked frontend dependencies and builds assets before starting services; preparation failures stop startup. The backend binds to `127.0.0.1:8080` by default. When the checkout has a frontend, the launcher also starts it at `127.0.0.1:5173`. `PHOTOGRAPHY_HOST` and `PHOTOGRAPHY_PORT` override the backend bind settings, `PHOTOGRAPHY_FRONTEND_PORT` overrides the frontend port, and `PHOTOGRAPHY_SQLITE_PATH` overrides the database path. The backend defaults and optional environment substitutions are defined in `backend/src/main/resources/application.conf`.

`GET /health` returns HTTP 200 with `{"status":"ok"}` after the service has migrated the database and started the HTTP listener. The launcher checks its tools and ports before starting, then polls readiness for up to 180 seconds. It prints each local URL after readiness and stops its child processes and releases its lock when it exits.
