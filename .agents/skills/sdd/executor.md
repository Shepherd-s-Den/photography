# SDD executor

Does the work for one phase at a time, in order, as sequenced by [`SKILL.md`](SKILL.md). Each phase below names its checklist or template — read that file; do not restate it here.

## Classify

Decide `feature` (planned change, refactor, migration — you already know the new behavior) or `investigation` (bug/incident — the spec is a trail toward a root cause). Name the branch `feature/<slug>` or `investigation/<slug>` and record the classification in `.agent-work/<slug>/checklist.md`.

Then decide whether a spec is required: draft the Doc Sync entry first — one line naming a doc, section, and specific fact. A non-empty entry means write the spec; a pure refactor or internal cleanup with nothing to put there doesn't need one. Only the maintainer may waive this requirement, explicitly and for a specific change — record the decision (or waiver) in `.agent-work/<slug>/checklist.md`.

Also make the complexity call here, before the spec (`DESIGN-PRINCIPLES.md` §7): whether this behavior is served by growing an existing flow or needs new machinery, and whether a unit the change will cross grows past a single responsibility and must split as part of it. "You don't need this" and "split this first" are valid, in-scope outcomes. Record the call in `.agent-work/<slug>/checklist.md`; by spec time it is already decided, and the spec only reflects it.

## Spike

Follow the Spike workflow in [`SKILL.md`](SKILL.md). This file adds no separate spike procedure.

## Spec PR

For a feature, wait until the user has run the local spike and confirmed the intended behavior before preparing or finalizing the proposal. Carry that confirmed behavior into the spec; a review pass over an unconfirmed draft does not satisfy the Spec PR gate.

Read [`templates/change-spec.md`](templates/change-spec.md) fresh — never recreate it from memory. Read the closest approved precedent spec for density; derive current facts from living docs and confirmed findings.

**Technical contract first.** Make the feature's mechanism the core of `Domain / Design Notes`: concrete use cases, orchestration, consumed ports, domain/persistence changes and sequence diagrams.

Show each trigger, the orchestrating use case and its injected collaborators, boundary calls/results, asynchronous acknowledgments, publication order and failure paths. Include UI delivery/reconnection when background work changes visible state.

Use compact Scala signatures/types, SQLite constraints and sequence diagrams where they settle an actual contract. Verify named library/framework capabilities against source; explain dependencies by the capability they provide.

The section is complete when a reader can trace every trigger through effects and committed state to the UI without inventing a missing owner or transition. Add prose only for guarantees the technical contracts leave ambiguous.

Keep concrete adapter internals, file layouts and slice execution notes in ignored work evidence; the single proposal owns the agreed technical contracts.

- **Feature:** current → new behavior, acceptance criteria, invariants, tradeoffs, Doc Sync.
- **Investigation:** hypotheses and evidence for/against each, narrowing to a proven root cause — the spec's `What & Why` ends up stating that root cause and the hypotheses it ruled out.

Self-check against [`checklists/spec-quality.md`](checklists/spec-quality.md), then satisfy `SKILL.md`'s PR-opening gate before publishing the proposal. Execution notes remain ignored; implementation starts only after the Spec PR is reviewed and merged.

## Tests

For backend changes, complete the backend checklist's Model and boundary audit before projecting tests. Record that evidence in the local checklist.

Derive tests from the merged spec's acceptance criteria and enumerated edge cases, before writing implementation code. A test must trace to a spec statement and fail only when that stated behavior changes — never reverse-engineered from code already written. Follow `CONTRIBUTING.md`'s testing conventions for suite naming and tiering.

## Implementation PR(s)

Implement the approved behavior. Split into multiple PRs on separable seams, or when one PR would exceed a focused review session — never to dodge a gate. Each PR links the merged spec.

Make the tests pass, then run the gates `AGENTS.md` already defines (backend/frontend/pipeline commands and `dagger call verify`) — do not repeat those commands here, and never weaken a gate to pass one. Self-check against the shared [`checklists/code-quality.md`](checklists/code-quality.md) plus the stack leaf for each surface this PR changes ([`code-quality-frontend.md`](checklists/code-quality-frontend.md) for `frontend/**`, [`code-quality-backend.md`](checklists/code-quality-backend.md) for `backend/**`, and no other), and [`checklists/implementation-completeness.md`](checklists/implementation-completeness.md) (against this PR's own slice) before requesting internal review. Satisfy `SKILL.md`'s PR-opening gate before opening the PR for maintainer review.

## Archive PR

Before archiving, run the implemented journey locally and compare it with the approved spec and the user's spike confirmation. Resolve any mismatch first.

After every Implementation PR has merged: apply each `Doc Sync` entry verbatim to the named living doc and section (`specs/*.md`, or `ci/specs/*.md` for pipeline changes) — prune obsolete or duplicated content rather than appending. Move the spec to `specs/changes/archive/YYYY-MM-DD-<slug>/`.

Before opening this PR, self-check [`checklists/implementation-completeness.md`](checklists/implementation-completeness.md) against the **complete merged change**, not just the last PR, then satisfy `SKILL.md`'s PR-opening gate. This PR introduces no new product behavior.
