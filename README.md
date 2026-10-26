# Photography

A photography application for managing persisted albums and opening their metadata, with a SolidJS frontend and a Scala 3 backend.

- [DESIGN-PRINCIPLES.md](DESIGN-PRINCIPLES.md) — architecture and engineering principles.
- [CONTRIBUTING.md](CONTRIBUTING.md) — toolchain, workflow, conventions, and definition of done.
- [AGENTS.md](AGENTS.md) — operational guardrails for coding agents.
- [.agents/skills/sdd/SKILL.md](.agents/skills/sdd/SKILL.md) — spec-driven development workflow.
- [backend/](backend/) — Scala 3 backend and HTTP API built with sbt.
- [frontend/](frontend/) — application frontend.
- [.dagger/](.dagger/) — affected-component CI checks used by GitHub Actions.
- [specs/](specs/) — living product design, contracts, testing, and operations context.
- [ci/specs/](ci/specs/) — living documentation for the CI pipeline.
- [LICENSE](LICENSE) — MIT.

## Local SQLite development

The undeployed application separates the Flyway schema (V1) from its authoritative reference-data seeds (V2): 249 countries/territories and 27 Saal chapter-start layouts. For a checksum-incompatible development database, select a fresh `PHOTOGRAPHY_SQLITE_PATH` or explicitly recreate only the identified disposable database. Retain spike data and unrelated databases.

The supported runtime is one backend instance. The chapter manager owns chapter integrity rules; its shared mutex coordinates concurrent editors' read/decision/write operations. SQLite provides basic relational constraints and transactions for atomic chapter metadata, country associations, opening slots and supplied order.
