# AGENTS.md

> Standard: Agentic Engineering Standards v1.2.0

The engineering standard applies to everyone, not only agents. Read [DESIGN-PRINCIPLES.md](DESIGN-PRINCIPLES.md) for architecture and [CONTRIBUTING.md](CONTRIBUTING.md) for conventions and workflow. Use [README.md](README.md) for repository orientation and [specs/](specs/) for living product knowledge.

## Change documentation

Changes that add or revise durable product behavior include a reviewed proposal under `specs/changes/`. Keep the proposal aligned with observable behavior and update the living documents it identifies.

## Engineering boundaries

- Keep domain and application behavior independent of concrete infrastructure. Express effectful boundaries as Typelevel algebras and compose them at the application boundary.
- Keep compiler, formatter, test, and coverage rules explicit. Do not weaken an existing gate to make a change pass.
- Keep dependencies focused and justify new production dependencies in the change spec.
- Keep tests at observable domain or adapter seams. Prefer exercising real boundaries over mocking internal implementation details.
- Do not commit secrets or local credentials.
