# AGENTS.md

> Standard: Agentic Engineering Standards v1.2.0

The engineering standard applies to everyone, not only agents. Read [DESIGN-PRINCIPLES.md](DESIGN-PRINCIPLES.md) for architecture and [CONTRIBUTING.md](CONTRIBUTING.md) for conventions and workflow. Use [README.md](README.md) for repository orientation and [specs/](specs/) for living product knowledge.

## Change documentation

Changes that add or revise durable product behavior include a reviewed proposal under `specs/changes/`. Keep the proposal aligned with observable behavior and update the living documents it identifies.

## Gates

- A backend change passes `dagger call backend-check` (`sbt compile`).
- A frontend change passes `dagger call frontend-check` (the static entry point check; expand it as the app is scaffolded).
- A pipeline change passes the module's type, lint, format, and test checks (`npm ci --prefix .dagger && dagger develop && npm --prefix .dagger run verify`) and `dagger call pipeline-check`.
- Every change passes `dagger call verify`; use `dagger call verify --all` when the change affects shared behavior or affected-component selection is uncertain.

## Engineering boundaries

- Keep domain and application behavior independent of concrete infrastructure. Express effectful boundaries as Typelevel algebras and compose them at the application boundary.
- Keep compiler, formatter, test, and coverage rules explicit. Do not weaken an existing gate to make a change pass.
- Keep dependencies focused and justify new production dependencies in the change spec.
- Keep tests at observable domain or adapter seams. Prefer exercising real boundaries over mocking internal implementation details.
- Do not commit secrets or local credentials.
