# Photography SQLite persistence

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0. Lifetime: open from creation through implementation, archived per [the SDD skill's Archive PR phase](../../../.agents/skills/sdd/SKILL.md).

**Date:** 2026-10-06

**Classification:** feature

**Grounded in:** The archived domain contract defines UUID identities, three spread kinds, typed slot and placement variants, and assigns cross-record and lifecycle rules to use cases. Plant-journal demonstrates configuration-driven database location and applying pending migrations before program construction. A migration can be exercised against SQLite directly, before an application data-access boundary exists.

## What & Why

The Scala backend defines the photography domain but cannot persist its records. This change establishes an initial durable representation whose structure can evolve as concrete use cases establish query and transaction needs.

## Domain / Design Notes

- Spread and template geometry uses InDesign points, with the top-left of the full two-page spread as its origin; positive x moves right and positive y moves down.
- Identity, ownership, ordering, and other values used in relationships or lookups remain relational. JSON is reserved for values that are opaque to use cases and never queried independently.
- Use cases own business validation and lifecycle behavior, including explicit cleanup of dependent records.

## Alternatives Considered

Porting the Rust schema verbatim was rejected because its persisted identities and spread kinds do not match the current domain contract.

## Tradeoffs Accepted

Callers needing sub-millisecond timestamp ordering cannot recover that precision after persistence; current photography workflows do not require it.

## Acceptance Criteria

- The initialization command reads a configurable database location with a local default. It requires the configured parent directory to exist, applies all pending migrations, and exits successfully. Invalid configuration, an unavailable database location, or a migration failure is reported and produces an unsuccessful exit.
- Re-running the command against an already initialized database succeeds without losing application data or reapplying completed migrations.
- The application schema represents the current domain's reference data, album content, gear selections, templates, spreads, slots, covers, placements, sessions, and editing locks. UUID identity columns use `TEXT`; timestamp columns use integer Unix milliseconds.
- Database constraints reject invalid storage types and enum values, missing referenced records, duplicate reference codes within their kinds, duplicate chapter positions and source folders within an album, duplicate photo sources within a chapter, duplicate spread positions, multiple covers for an album, multiple placements for a photo across spread and cover placements, and multiple placements for each photo-spread or cover slot.
- A migration-level test runs the real versioned migrations against SQLite, inspects the resulting application schema, explicitly enables foreign-key enforcement on its test connection and verifies orphan inserts fail, and exercises enum and uniqueness constraints.
- The backend test gate runs the migration-level test.

## Doc Sync

- `specs/contracts.md` — Contract inventory: replace the no-schema statement with the current-domain SQLite persistence contract, its millisecond timestamp precision, relational ownership, and database-versus-use-case responsibilities.
- `specs/design.md` — Domain model / geometry: record the top-left origin and x-right/y-down direction for the full two-page spread.
- `specs/design.md` — Architecture constraints: record that the initialization command applies pending migrations before exiting and that future application composition applies them before constructing programs.
- `specs/operational.md` — Runtime storage: document the configurable database location and local default, the requirement that its parent directory exists, and initialization failure behavior.
- `specs/testing.md` — Strategy: document direct SQLite migration-level coverage; defer domain-value round-trip and application-connection foreign-key enforcement tests until application database access exists.
- `CONTRIBUTING.md` — Local development: document that `cd backend && sbt run` initializes the database from configuration and exits, and that `cd backend && sbt test` runs the migration-level tests.
- `AGENTS.md` — Gates: run backend tests, including migration-level coverage, in the backend gate.
- `ci/specs/design.md` — Component ownership: state that backend component verification runs the backend test task.
- `ci/specs/testing.md` — Strategy: include backend migration-level tests in the containerized backend check.

## Out of Scope

- Importing data or migration history from files created by the unreleased Rust backend.
