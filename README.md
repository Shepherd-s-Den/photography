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

SQLite applies additive migrations to the current V1 baseline, preserving saved albums, country identities and spread geometry while adding reference catalogs and chapter integrity constraints. Databases from earlier, checksum-incompatible V1 development schemas still require explicit resolution; migrations do not invent missing album years. Preserve existing databases and select a fresh `PHOTOGRAPHY_SQLITE_PATH` for isolated development.

The supported runtime is one backend instance. Its shared chapter-manager mutex coordinates concurrent editors' read/decision/write operations; SQLite transactions independently guarantee atomic chapter metadata, country associations, opening slots and supplied order. This does not establish multi-process business-rule coordination.
