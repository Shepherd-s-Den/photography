# Contributing

> Standard: Agentic Engineering Standards v1.2.0

Architecture and design rationale live in [DESIGN-PRINCIPLES.md](DESIGN-PRINCIPLES.md). This document covers engineering conventions and the expected contribution quality.

## Stack and setup

The backend is Scala 3 and follows Typelevel and Tagless Final principles for effectful application boundaries. The frontend uses SolidJS and TypeScript. Dagger runs affected-component CI.

Install the pinned Java 25, Scala 3.10, and Node 24 toolchain with [mise](https://mise.jdx.dev): `mise install`. Docker is required for Dagger's containerized checks.

### Local development

Run `python3 scripts/local.py` from the repository root for local development. It installs locked frontend dependencies, builds the assets, and starts the backend and frontend development server.

Use Dagger for remote checks; its backend gate installs and builds the frontend automatically before packaging the complete asset tree with the backend.

| Task | Command |
| --- | --- |
| Backend gate | `dagger call backend-check` |
| Frontend quality and build gates | `dagger call frontend-check` |
| Pipeline tests | `dagger call pipeline-check` |
| Pipeline module checks | `npm ci --prefix .dagger && dagger develop && npm --prefix .dagger run verify` |
| Affected checks | `dagger call verify` |
| All checks | `dagger call verify --all` |

Enable the local pre-push hook with `git config core.hooksPath .githooks`; it runs affected checks before each push.

| Path | What |
| --- | --- |
| `backend/` | Scala 3 backend and HTTP API. |
| `frontend/` | SolidJS frontend and tests. |
| `.dagger/` | TypeScript Dagger CI module. |
| `specs/` | Living product design, contracts, testing, and operational context. |
| `ci/specs/` | Living documentation for the CI pipeline. |

## Documentation style

- Keep documentation short, technical, and concise. Maximize signal and avoid restating facts owned elsewhere.
- Integrate new lessons into the rule that owns them. Refine or replace stale guidance instead of appending a chronology of discoveries.
- Describe observable behavior and guarantees positively.
- Keep each Markdown prose paragraph and list item on one source line; let the renderer wrap it.

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
- Use Cats syntax for value construction (`.pure[F]`, `.some`, `.asRight`, `.asLeft`, and related operations) instead of explicit typeclass calls or `Some`/`Right`/`Left` constructors where syntax applies.
- Keep business orchestration visible in the public service method. Similar workflows should have visibly similar structure; do not hide their ordering or compensation inside helpers.
- Give each helper one responsibility and return the narrowest type that describes it. A persistence or domain-step helper must not construct the caller's public result.
- Keep effectful sequencing linear. Avoid deeply nested `flatMap`, matches, or conditionals; extract a meaningful step or model the additional state explicitly.
- Model success, expected short-circuiting, and failure as distinct states when all three exist. Keep error handling explicit and preserve the primary failure if compensation also fails.
- Design algebra and data types so impossible states are unrepresentable. Prefer exhaustive types over open-ended strings and unreachable branches.

## Change proposals

Proposals live under [`specs/changes/<slug>/proposal.md`](specs/changes/) and are archived under `specs/changes/archive/YYYY-MM-DD-<slug>/` after implementation. State current and intended behavior, observable acceptance criteria, invariants, tradeoffs, and specific living-document updates.

## Development workflow — SDD

Every product change follows the [SDD skill](.agents/skills/sdd/SKILL.md). Its phases are **classify → spike when needed → spec → tests → implement → sync & archive**.

- A **Spec PR** contains the reviewed proposal and merges before implementation.
- One or more **Implementation PRs** deliver the approved behavior and pass the applicable gates.
- An **Archive + Living Docs PR** synchronizes documentation and archives the completed spec after implementation merges.
- Work notes and review checklists live in the git-ignored `.agent-work/<slug>/` directory.

## Pull requests

- Follow the [SDD feature lifecycle](.agents/skills/sdd/SKILL.md#setup) for branch naming and reuse across successive PRs.
- Keep implementation PRs focused on one reviewable behavior or seam. Link the merged proposal and identify the intended review focus.
- Commit subjects are imperative and no longer than 72 characters. Explain the reason in the body when the diff alone does not.

## Testing conventions

- Add tests with each observable behavior and exercise the boundary that owns it.
- Prefer complete-value assertions and scenario-local setup over assertions on implementation details.
- Keep test names in the vocabulary of the behavior exposed by the tested boundary.

## Definition of done

A change is done when its proposal is reviewed, tests projected from its acceptance criteria pass, applicable component gates pass, living documents named in Doc Sync are updated, and the completed spec is archived.
