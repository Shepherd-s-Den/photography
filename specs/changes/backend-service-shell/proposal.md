# Backend local service foundation

> Standard: Agentic Engineering Standards v1.2.0. Lifetime: open from creation through implementation, archived per the [SDD skill](../../../.agents/skills/sdd/SKILL.md).

**Date:** 2026-10-07

**Classification:** feature

**Grounded in:** [Current startup](../../../backend/src/main/scala/photography/app/runtime/Main.scala) migrates SQLite then waits; [resource composition](../../../backend/src/main/scala/photography/app/runtime/AppResourcesBuilder.scala) already exists. The health route adds no domain behavior or uncertain algorithm. Prior art confirms a [100% Scoverage gate](https://github.com/cosminci/plant-journal/blob/d224e84a2b7f4e8e63aa135b832dc7eaa36ab365/backend/build.sbt), [180-second local runner](https://github.com/cosminci/plant-journal/blob/d224e84a2b7f4e8e63aa135b832dc7eaa36ab365/scripts/local.py), and [http4s/Cats Effect setup](https://git.corp.adobe.com/experience-platform/profile-hub-ingestion-services/blob/2cacad5a1a47c98eb6a5d0d85d548de5f424b043/ingest-controller/build.sbt).

## What & Why

The backend has no HTTP service, local launcher, or coverage gate. Establish these foundations so later API slices can be developed and exercised end to end.

## Domain / Design Notes

Health means SQLite migration and listener startup succeeded; it does not imply product features are ready. The application releases database and listener resources on shutdown.

The service listens on `127.0.0.1:8080` by default. An absent or empty `PHOTOGRAPHY_HOST` uses that default; a nonempty value is a bind address (an IP literal or hostname resolving to an address on this machine), not a URL. Explicit wildcard addresses `0.0.0.0` and `::` are allowed. `PHOTOGRAPHY_PORT` must be an integer from 1 through 65535. Invalid configuration or a failed bind prevents readiness and surfaces as a startup failure.

Coverage measures statements and branches across backend production code, excluding only `photography.app.runtime`. The launcher stores its database in Git-ignored `.local/` and does not use NAS or frontend state.

## Acceptance Criteria

- After successful database migration and listener startup, `GET /health` returns HTTP 200 with JSON `{"status":"ok"}`.
- A database migration failure prevents the service from reporting healthy and remains a startup failure.
- Without overrides, or when `PHOTOGRAPHY_HOST` is empty, the service binds to `127.0.0.1`; `PHOTOGRAPHY_PORT` defaults to `8080`.
- A nonempty `PHOTOGRAPHY_HOST` selects a local IP/interface address or the explicit wildcard `0.0.0.0`/`::`.
- URL syntax, an unresolved hostname, or an address not available on this machine fails startup.
- `PHOTOGRAPHY_PORT` accepts only an integer in `1..65535`; malformed and out-of-range values fail startup.
- The backend validation gate generates a Scala coverage report and fails unless statement and branch totals are each 100%; only `photography.app.runtime` is excluded from measurement.
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
- `specs/testing.md` — backend strategy: record the enforced 100% statement and branch coverage policy, its sole package exclusion, and `cd backend && sbt backend-check` as the command that enforces it.
- `CONTRIBUTING.md` — Local development: point to `python3 scripts/local.py` as the local service launcher.

## Out of Scope

- NAS database refresh and frontend build/watch are not part of this backend-only launcher.
