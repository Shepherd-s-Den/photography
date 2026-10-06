# Photography SQLite schema and startup migrations

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.

**Date:** 2026-10-06

**Classification:** feature

**Grounded in:** The active Scala model defines the records and variants. Plant-journal and ingest-controller apply Flyway migrations before constructing programs.

## What & Why

The service needs a SQLite schema for the active photography model. The application applies pending Flyway migrations on startup. The schema can evolve as implemented use cases reveal real query and transaction needs.

## Domain / Design Notes

- `STRICT` tables reject incompatible storage types. `CHECK` constraints enforce enum values. Primary and foreign keys enforce identity and references. `UNIQUE` constraints enforce reference codes, owner-local ordering and paths, covers, and placements.
- IDs use `TEXT`; timestamps use integer Unix milliseconds. Geometry uses InDesign points. The top-left of the full two-page spread is the origin; x increases right and y increases down.
- The database does not enforce use-case rules such as chapter limits, path containment, placement ownership, geometry bounds, or session and lock lifecycle.
- V1 has no triggers or cascading deletes.

## Tradeoffs Accepted

Consumers lose sub-millisecond timestamp precision after persistence.

## Acceptance Criteria

- Startup reads the configured SQLite path and applies pending migrations before application composition.
- A migration failure stops startup.
- Restarting preserves data and skips completed migrations.
- The V1 schema stores the persistent records and variants in the active Scala model.
- SQLite rejects incompatible storage types and enum values.
- Primary keys, foreign keys, and unique constraints enforce the relationships and uniqueness defined by the migration.
- Application SQLite connections enforce foreign keys.

## Doc Sync

- `specs/contracts.md` — Contract inventory: point to `backend/src/main/resources/db/` as the authoritative SQLite schema.
- `specs/design.md` — Architecture constraints: state that the app maintains the database schema with Flyway on startup.
- `specs/design.md` — Geometry: record the top-left origin and x-right/y-down directions for the full two-page spread.
- `specs/testing.md` — Strategy: state that database-seam integration tests initialize the schema before exercising database access.
- `specs/testing.md` — Test names: use the behavior vocabulary exposed by the tested boundary.
