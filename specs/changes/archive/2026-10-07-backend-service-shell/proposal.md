# Backend local service foundation

**Date:** 2026-10-07

**Classification:** feature

**Grounded in:** Startup already completes SQLite migrations before waiting, and the application already owns its resources. This change makes that readiness boundary observable and adds local execution and coverage gates; it introduces no uncertain domain behavior or algorithm that needs a separate spike.

## What & Why

The service exposes HTTP readiness only after SQLite migrations and listener startup succeed. A local launcher and Dagger coverage gate make the backend reliable to run and verify as later API slices are added.

## Domain / Design Notes

Health means SQLite migration and listener startup succeeded; it does not imply product features are ready. The application releases database and listener resources on shutdown.

The service listens on `127.0.0.1:8080` by default. `PHOTOGRAPHY_HOST` and `PHOTOGRAPHY_PORT` override those settings through `application.conf`; PureConfig reads them as ip4s `Host` and `Port` values. Invalid configuration or a failed bind aborts startup.

Coverage enforces 100% statement and branch totals over measured backend code, with temporary exclusions for `photography.app.runtime`, the SQLite adapter package, and the currently untested domain ID/location classlikes listed in `backend/build.sbt`. Remove those class exclusions as behavior tests cover the types. The local launcher stores its database in Git-ignored `.local/`.

## Acceptance Criteria

- After successful database migration and listener startup, `GET /health` returns HTTP 200 with JSON `{"status":"ok"}`.
- The complete `photography` configuration in `application.conf` loads successfully with its defaults.
- With no host or port overrides, the service binds to `127.0.0.1:8080`.
- `dagger call backend-check` generates the Scala coverage report and fails unless statement and branch totals are each 100%; runtime and SQLite packages plus specifically named domain ID/location classlikes are temporarily excluded.
- CI runs `python3 -m unittest discover -s scripts -p 'test_*.py'` for local-launcher unit tests, and any failure fails the pipeline.
- `python3 scripts/local.py` creates a Git-ignored `.local/` directory with owner-only access, stores the local database there, and prevents concurrent launcher instances.
- Before starting the service, the launcher verifies Java and sbt are available and the selected port can be bound; a failed preflight exits without starting the child.
- The launcher passes `PHOTOGRAPHY_HOST` and `PHOTOGRAPHY_PORT` to the service.
- The launcher probes `/health` at the selected address, using loopback for wildcard binds.
- After readiness, the launcher prints the local service URL.
- The launcher waits up to 180 seconds for readiness. If the backend exits before readiness or the deadline expires, it exits with an error and stops the backend and its child processes.
- Interrupting or otherwise exiting the launcher stops its child processes.
- After launcher exit, the single-instance lock is released so a later launch can proceed.

## Doc Sync

- `specs/design.md` — Architecture constraints: record the HTTP driving boundary and that readiness follows successful migration and listener startup.
- `specs/operational.md` — replace the scaffold-only status with the local service entry point, readiness contract, and host/port overrides.
- `specs/testing.md` — backend strategy: record the enforced 100% statement and branch coverage policy, its temporary runtime/SQLite and per-class domain exclusions, and `dagger call backend-check` as the enforcing gate.
- `CONTRIBUTING.md` — Local development: point to `python3 scripts/local.py` as the local service launcher.
