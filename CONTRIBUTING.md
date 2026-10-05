# Contributing

> Standard: Agentic Engineering Standards v1.2.0

Architecture and design rationale live in [DESIGN-PRINCIPLES.md](DESIGN-PRINCIPLES.md). This document covers the toolchain, conventions, workflow, and definition of done. Markdown prose is soft-wrapped with one line per paragraph.

## Working in the repo

**Stack.** Scala 3 backend, using Typelevel libraries and Tagless Final for effectful application boundaries; SolidJS and TypeScript for the frontend; a TypeScript Dagger module for affected-component CI. This is an initial scaffold: the backend has no application dependencies and the frontend is a static HTML entry point.

**Setup.** Install the pinned toolchain (Java 25, Scala 3.8, Node 24) with [mise](https://mise.jdx.dev): `mise install`. Docker is required for Dagger's containerized checks. Commands below assume mise is shell-activated; otherwise prefix them with `mise exec --`.

The repository's `.githooks/pre-push` hook is active and runs `dagger call verify` before pushes.

### Local development

Compile the backend:

```sh
cd backend && sbt compile
```

Run the same component checks used in CI:

| Task | Command |
| --- | --- |
| Backend gate | `dagger call backend-check` |
| Frontend gate | `dagger call frontend-check` |
| Pipeline tests | `dagger call pipeline-check` |
| Pipeline module checks | `npm ci --prefix .dagger && dagger develop && npm --prefix .dagger run verify` |
| Affected checks | `dagger call verify` |
| All checks | `dagger call verify --all` |

| Path | What |
| --- | --- |
| `backend/` | Scala 3 service scaffold; intended to use Typelevel and Tagless Final. |
| `frontend/` | SolidJS and TypeScript frontend scaffold. |
| `.dagger/` | TypeScript Dagger CI module. |
| `specs/` | Living product design, contracts, testing, and operational context. |
| `ci/specs/` | Living documentation for the CI pipeline. |
| `.agents/skills/` | Shared coding-agent skills, including spec-driven development. |

## Documentation style

- Keep documentation short, technical, and concise. Maximize signal and avoid restating facts owned elsewhere.
- Integrate new lessons into the rule that owns them. Refine or replace stale guidance instead of appending a chronology of discoveries.
- Describe observable behavior and guarantees positively.

## Naming

- Optional fields, values, and parameters use names that make optionality apparent, such as `maybeDescription`.
- Values whose types do not reveal their role are named at the call site; self-describing values remain positional.
- In TypeScript, use named imports when they keep the import readable. Keep framework imports named when compiler, lint, or reactivity semantics depend on those bindings.

## Composition and abstraction

- Apply locality of behavior and knowledge-based DRY from [DESIGN-PRINCIPLES.md](DESIGN-PRINCIPLES.md#3-fractal-design): deduplicate a rule or contract whose divergence would be a bug, not code that merely has the same shape today.
- Before extracting shared code, name the responsibility it owns and the callers that must obey the same invariant. If an extraction mainly replaces straightforward code with callbacks, options, or configuration, keep the behavior local.
- A user interface component owns the behavior and accessible state of its controls. State transitions must not discard focus or hide the reason an action is unavailable.

## Scala composition

- Express effectful application boundaries as traits parameterized by an effect type, such as `F[_]`; keep pure domain values independent of runtime effects.
- Keep business orchestration visible in the public service method. Similar workflows should have visibly similar structure; do not hide their ordering or compensation inside helpers.
- Give each helper one responsibility and return the narrowest type that describes it. A persistence or domain-step helper must not construct the caller's public result.
- Keep effectful sequencing linear. Avoid deeply nested `flatMap`, matches, or conditionals; extract a meaningful step or model the additional state explicitly.
- Model success, expected short-circuiting, and failure as distinct states when all three exist. Keep error handling explicit and preserve the primary failure if compensation also fails.
- Design algebra and data types so impossible states are unrepresentable. Prefer exhaustive types over open-ended strings and unreachable branches.

## Development workflow — SDD

Every product change follows the **SDD skill** at [`.agents/skills/sdd/SKILL.md`](.agents/skills/sdd/SKILL.md):

- **Feature** (planned change, refactor, migration): a change spec states current → new behavior, acceptance criteria, invariants, and tradeoffs; then tests are projected from the spec before implementation.
- **Investigation** (bug, incident): hypotheses and evidence lead to a proven root cause; the archived spec records the root cause and rejected hypotheses.

The phases are **classify → spike when needed → spec → tests → implement → sync & archive**. The normal workflow uses a reviewed Spec PR, one or more Implementation PRs, and a final Archive + Living Docs PR. The archive step introduces no new product behavior.

- **Specs** live under [`specs/changes/<slug>/proposal.md`](specs/changes/) and are archived under `specs/changes/archive/YYYY-MM-DD-<slug>/`. Living product context is in `specs/{design,contracts,testing,operational}.md`; CI context is in `ci/specs/`.
- **Work notes** live in the git-ignored `.agent-work/<slug>/` directory and are not included in a PR.

## Branching and pull requests

- Use one branch per change: `feature/<slug>` or `investigation/<slug>`.
- Keep implementation PRs focused on one reviewable behavior or seam. A PR references its reviewed change spec and identifies the intended review focus.
- Commit subjects are imperative and no longer than 72 characters. Explain the reason in the body when the diff alone does not.

## Testing conventions

- Add tests with each observable behavior and exercise the boundary that owns it. Prefer behavior assertions over implementation details.
- Backend tests use the ecosystem's selected Scala test framework and should cover pure domain rules, effectful service behavior, and concrete adapters at their real seams.
- Frontend tests use accessible user-facing interactions. Keep pure derivation tests separate from component wiring tests.
- Pipeline tests use Vitest and cover affected-component selection and other pure pipeline decisions. Containerized Dagger checks verify that the selected build hooks work together.
- Name tests for the behavior they guarantee. Keep scenario setup local and assertions readable.
- Composition roots that only wire already-tested parts need not be forced through artificial unit tests.

## Definition of done

A change is done when its required change spec is reviewed; tests projected from its acceptance criteria pass; the affected component gates and `dagger call verify` pass; living documents named in Doc Sync are updated; and the change spec is archived after the implementation merges.
