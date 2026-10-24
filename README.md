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

The initial V1 schema is still edited in place. A local database created with an older V1 must be recreated before running the updated application; existing database contents are not migrated by this development baseline.
