# Photography operations

> Standard: Agentic Engineering Standards v1.2.0

## Local service

Run `python3 scripts/local.py` from the repository root to start the backend for local development. The launcher creates `.local/` with owner-only permissions, stores the SQLite database at `.local/photography.db`, and prevents concurrent launcher instances.

The service binds to `127.0.0.1:8080` by default. `PHOTOGRAPHY_HOST` and `PHOTOGRAPHY_PORT` override the bind settings, and `PHOTOGRAPHY_SQLITE_PATH` overrides the database path. The defaults and optional environment substitutions are defined in `backend/src/main/resources/application.conf`.

`GET /health` returns HTTP 200 with `{"status":"ok"}` after the service has migrated the database and started the HTTP listener. The launcher checks Java, sbt, and port availability before starting, then polls readiness for up to 180 seconds. It prints the service URL after readiness and stops the backend process group and releases its lock when it exits.
