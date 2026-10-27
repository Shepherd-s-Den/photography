# AGENTS.md

> Standard: Agentic Engineering Standards v1.2.0

The engineering standard applies to everyone, not only agents. Read [DESIGN-PRINCIPLES.md](DESIGN-PRINCIPLES.md) for architecture and [CONTRIBUTING.md](CONTRIBUTING.md) for conventions and workflow. Use [README.md](README.md) for repository orientation, [specs/](specs/) for living product knowledge, and the shared [SDD skill](.agents/skills/sdd/SKILL.md) for product changes.

## How work happens here

Classify each change through the SDD skill before implementation. A reviewed change spec is required when a change adds or revises knowledge that belongs in a living document. Only the maintainer may explicitly waive that requirement. Do not introduce product behavior outside a reviewed change spec when one is required.

## Gates

- A backend change passes `dagger call backend-check`.
- A frontend change passes `dagger call frontend-check`.
- A pipeline change passes the module's type, lint, format, and test checks (`npm ci --prefix .dagger && dagger develop && npm --prefix .dagger run verify`) and `dagger call pipeline-check`.
- Every change passes `dagger call verify`; use `dagger call verify --all` when the change affects shared behavior or affected-component selection is uncertain.
- Pull-request CI and the enabled pre-push hook run `bash scripts/verify <base-commit-or-ref>` for Markdown, pipeline-module and affected-component gates. See [pipeline operations](ci/specs/operational.md#local-push-verification) for hook setup and revision requirements.

## Engineering boundaries

- Keep domain and application behavior independent of concrete infrastructure. Express effectful boundaries as Typelevel algebras and compose them at the application boundary.
- Keep compiler, formatter, test, and coverage rules explicit. Do not weaken an existing gate to make a change pass.
- Keep dependencies focused and justify new production dependencies in the change spec.
- Keep tests at observable domain or adapter seams. Prefer exercising real boundaries over mocking internal implementation details.
- Do not commit secrets or local credentials.
