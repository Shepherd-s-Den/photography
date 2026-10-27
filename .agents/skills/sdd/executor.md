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

Read [`templates/change-spec.md`](templates/change-spec.md) fresh — never recreate it from memory. Read the closest approved precedent when one exists; the current template and `spec-quality.md` govern altitude and density. Derive current facts from living docs and confirmed findings.

**Behavior/contract first.** Trace the concrete trigger through observable outcomes, committed state, failure order and UI delivery. Use diagrams or tables where they resolve a flow or contract question; add only prose that clarifies remaining guarantees.

State changed domain, persisted-data and boundary guarantees and who enforces them. Distinguish database constraints from application rules and verify constraint mechanisms against current sources.

Keep signatures, module/file layouts, adapter internals and slice execution notes in the ignored implementation design map. The proposal owns agreed behavior and domain contracts; its structure follows intent rather than a diff walkthrough.

When a spike preceded this phase, ground design notes and any real alternatives in its findings.

- **Feature:** current → new behavior, acceptance criteria, invariants, tradeoffs, Doc Sync.
- **Investigation:** hypotheses and evidence for/against each, narrowing to a proven root cause — the spec's `What & Why` ends up stating that root cause and the hypotheses it ruled out.

Self-check against [`checklists/spec-quality.md`](checklists/spec-quality.md), then satisfy `SKILL.md`'s PR-opening gate before publishing the proposal. Execution notes remain ignored; implementation starts only after the Spec PR is reviewed and merged.

## Tests

Before writing tests or production code for each slice, build a short design map in `.agent-work/<slug>/`: module responsibility, consuming boundary and proposed call/signature/result; invariants and enforcing boundary; effects and dependency owners/lifetimes; policy authority; owning suite; exact source/test precedents inspected. Use the blocking questions in [`checklists/code-quality.md`](checklists/code-quality.md), not a proposed file list.

Derive caller-first APIs from a concrete trigger-to-UI sequence and real callers' available inputs and consumed results; distinguish application operations from driven ports. Before adding a method, try an existing boundary or local derivation. Walk scenarios through every signature and parameter using `reviewer.md`'s seam-definition evidence; prove necessity, not just a possible caller. Source-verify dependency effects, resource lifetimes and transaction guarantees, and apply the stack's API/type conventions.

Check each row against the consuming boundary and applicable stack leaf. Stop on an unanswered question or failed condition; resolve the API or ownership before implementation. Apply `SKILL.md`'s adjudication rule for departures. Update the map when a new module or changed boundary emerges.

Project acceptance scenarios from the merged spec's criteria and enumerated edge cases before implementation. Tests of architectural/type guarantees trace to their governing rule at the owning module or seam; product specs need not restate implementation constraints. Follow `CONTRIBUTING.md`'s testing conventions rather than reverse-engineering tests from the code written.

## Implementation PR(s)

Implement the approved behavior within the checked design map. Split into multiple PRs on separable seams, or when one PR would exceed a focused review session — never to dodge a gate. Each PR links the merged spec.

Make the tests pass, then run the gates `AGENTS.md` defines; never weaken a gate to pass one. Self-check the shared quality checklist, stack leaves selected under `SKILL.md` and [`implementation-completeness.md`](checklists/implementation-completeness.md) against this slice. Replace proposed design-map calls with actual symbols and consumption. Run a second whole-diff pass for callable surfaces, declarations, syntax, capabilities and test structure before the review protocol.

Run the slice locally against its real boundaries and obtain the maintainer's behavior confirmation before opening its PR, as required by `SKILL.md`. Keep local runs isolated from production state unless the maintainer explicitly authorizes that access.

The handoff identifies the exact baseline and candidate revision or content manifest, every changed/untracked file, design-map answers, checklist evidence, local confirmation and command results. Missing evidence is an open gate. Follow [`reviewer.md`](reviewer.md) for the independent review, repair verification and missed-rule recovery.

## Archive PR

Before archiving, run the implemented journey locally and compare it with the approved spec and the user's spike confirmation. Resolve any mismatch first.

After every Implementation PR has merged: apply each `Doc Sync` entry verbatim to the named living doc and section (`specs/*.md`, or `ci/specs/*.md` for pipeline changes) — prune obsolete or duplicated content rather than appending. Move the spec to `specs/changes/archive/YYYY-MM-DD-<slug>/`.

Before opening this PR, self-check [`checklists/implementation-completeness.md`](checklists/implementation-completeness.md) against the **complete merged change**, not just the last PR, then satisfy `SKILL.md`'s PR-opening gate. This PR introduces no new product behavior.
