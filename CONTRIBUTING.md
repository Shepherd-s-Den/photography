# Contributing

> Standard: Agentic Engineering Standards v1.2.0

Architecture and design rationale live in [DESIGN-PRINCIPLES.md](DESIGN-PRINCIPLES.md). This document covers engineering conventions and the expected contribution quality.

## Stack direction

The application is planned around a Scala 3 backend using Typelevel libraries and Tagless Final for effectful application boundaries, with a SolidJS and TypeScript frontend. Keep implementation choices aligned with the living documents and reviewed proposals.

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

## Change proposals

Proposals live under [`specs/changes/<slug>/proposal.md`](specs/changes/) and are archived under `specs/changes/archive/YYYY-MM-DD-<slug>/` after implementation. State current and intended behavior, observable acceptance criteria, invariants, tradeoffs, and specific living-document updates.

## Pull requests

- Use one branch per change, named `feature/<slug>` or `investigation/<slug>`.
- Keep implementation PRs focused on one reviewable behavior or seam. Link the reviewed proposal and identify the intended review focus.
- Commit subjects are imperative and no longer than 72 characters. Explain the reason in the body when the diff alone does not.

## Testing conventions

- Add tests with each observable behavior and exercise the boundary that owns it.
- Prefer complete-value assertions and scenario-local setup over assertions on implementation details.
- Keep test names in the vocabulary of the behavior exposed by the tested boundary.

## Definition of done

A change is done when its proposal is reviewed, its acceptance criteria are satisfied, relevant tests pass, and the living documents it names are synchronized with the delivered behavior.
