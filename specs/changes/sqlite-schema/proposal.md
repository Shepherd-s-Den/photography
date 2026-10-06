# Photography SQLite schema and startup migrations

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0. Lifetime: open from creation through implementation, archived per [the SDD skill's Archive PR phase](../../../.agents/skills/sdd/SKILL.md).

**Date:** 2026-10-06

**Classification:** feature

**Grounded in:** The active Scala model and `specs/design.md` define the records, variants, and use-case-owned rules. Plant-journal and ingest-controller both apply Flyway migrations before constructing programs. Real migrations can run against SQLite without a persistence adapter.

## What & Why

The service has no SQLite schema. This change stores the active photography model. The schema can evolve as implemented use cases reveal real read and transaction needs.

## Domain / Design Notes

- Use `STRICT` tables for storage types and `CHECK` constraints for enum values. Use primary and foreign keys for identity and references, and `UNIQUE` constraints for natural keys, owner-local ordering, and placements. Application connections enable foreign-key enforcement.
- Use cases enforce the 20-chapter limit and positions `0..19`, and keep paths within their album or chapter roots.
- Chapter-spread placements use photos from that chapter; album-start and cover placements use photos from that album. Use cases also validate template-kind compatibility, geometry bounds, gear consistency, cover completeness, and session and lock lifecycle.
- Use cases clean up dependent records. Do not use triggers or cascading cleanup.
- Keep identity, ownership, order, and lookup fields relational; use JSON only for opaque values. Geometry uses InDesign points, with the top-left of the full two-page spread as its origin; x increases right and y increases down.

## Alternatives Considered

Copying the Rust schema was rejected because it does not match the active Scala model's identities and variants.

## Tradeoffs Accepted

Consumers needing sub-millisecond precision lose it after persistence.

## Acceptance Criteria

- Startup uses a configurable SQLite path with a local default and applies pending migrations before building application programs. A missing parent directory or migration failure stops startup.
- Restarting preserves application data and does not reapply completed migrations.
- A fresh database stores every persistent record and variant in the active Scala model. IDs use canonical UUID text and timestamps use integer Unix milliseconds.
- Invalid storage types and enum values fail insertion. Application connections enable foreign-key enforcement, so missing references fail.
- Reference codes are unique within their kinds.
- Chapter positions and folders are unique within an album. Photo source paths are unique within a chapter.
- Spread positions are unique within their owners. Each album has at most one cover.
- A photo has at most one placement across its album's spreads and cover. Each spread or cover slot has at most one photo.
- A migration-level test runs real Flyway migrations on SQLite, checks schema constraints with foreign-key enforcement enabled, and verifies a rerun preserves data.
- A real SQLite resource test verifies application connections reject missing references.
- A real startup test verifies migration ordering and failure behavior.

## Doc Sync

- `specs/contracts.md` — Contract inventory: point to `backend/src/main/resources/db/` as the authoritative SQLite schema.
- `specs/design.md` — Architecture constraints: state that the app maintains the database schema with Flyway on startup.
- `specs/design.md` — Geometry: record the top-left origin and x-right/y-down directions for the full two-page spread.
- `specs/testing.md` — Strategy: state that database-seam integration tests initialize the schema before exercising database access.
- `specs/testing.md` — Test names: use the behavior vocabulary exposed by the tested boundary.

## Out of Scope

- Importing data or migration history from the unreleased Rust backend.
