# SDD executor

Does the work for one phase at a time, in order, as sequenced by [`SKILL.md`](SKILL.md). Each phase below names its checklist or template — read that file; do not restate it here.

## Classify

Decide `feature` (planned change, refactor, migration — you already know the new behavior) or `investigation` (bug/incident — the spec is a trail toward a root cause). Name the branch `feature/<slug>` or `investigation/<slug>` and record the classification in `.agent-work/<slug>/checklist.md`.

Then decide whether a spec is required: draft the Doc Sync knowledge delta first — one line naming the owning doc, section and durable change. Apply `SKILL.md`'s living-context policy when deciding what belongs there. A non-empty entry means write the spec; a pure refactor or internal cleanup with nothing to put there doesn't need one. Only the maintainer may waive this requirement, explicitly and for a specific change — record the decision (or waiver) in `.agent-work/<slug>/checklist.md`.

Also make the complexity call here, before the spec (`DESIGN-PRINCIPLES.md` §7): whether this behavior is served by growing an existing flow or needs new machinery, and whether a unit the change will cross grows past a single responsibility and must split as part of it. "You don't need this" and "split this first" are valid, in-scope outcomes. Record the call in `.agent-work/<slug>/checklist.md`; by spec time it is already decided, and the spec only reflects it.

## Spike

Follow the Spike workflow in [`SKILL.md`](SKILL.md). This file adds no separate spike procedure.

## Spec PR

For a feature, wait until the user has run the local spike and confirmed the intended behavior before preparing or finalizing the proposal. Carry that confirmed behavior into the spec; a review pass over an unconfirmed draft does not satisfy the Spec PR gate.

Read [`templates/change-spec.md`](templates/change-spec.md) fresh — never recreate it from memory. Read the closest approved precedent when one exists; the current template and `spec-quality.md` govern altitude and density. Derive current facts from living docs and confirmed findings.

**Behavior/contract first.** Trace the concrete trigger through observable outcomes, committed state, failure order and UI delivery. Use diagrams or tables where they resolve a flow or contract question; add only prose that clarifies remaining guarantees.

State changed domain, persisted-data and boundary guarantees and who enforces them. Distinguish database constraints from application rules and verify constraint mechanisms against current sources.

Keep signatures, module/file layouts, adapter internals and slice execution notes in the ignored implementation design map. The proposal owns agreed behavior and domain contracts; its structure follows intent rather than a diff walkthrough.

Draft Doc Sync as the change to the enduring system model, boundary ownership, testing strategy or operating model. Link precise contract/data changes to their authoritative artifact. The proposal's acceptance criteria remain feature evidence, not the outline of a new living-doc section.

When a spike preceded this phase, ground design notes and any real alternatives in its findings.

- **Feature:** current → new behavior, acceptance criteria, invariants, tradeoffs, Doc Sync.
- **Investigation:** hypotheses and evidence for/against each, narrowing to a proven root cause — the spec's `What & Why` ends up stating that root cause and the hypotheses it ruled out.

Self-check against [`checklists/spec-quality.md`](checklists/spec-quality.md), then satisfy `SKILL.md`'s PR-opening gate before publishing the proposal. Execution notes remain ignored; implementation starts only after the Spec PR is reviewed and merged.

## Tests

Before tests or production code, complete [`checklists/code-quality.md`](checklists/code-quality.md#architecture-gate)'s ordered architecture decision gate in `.agent-work/<slug>/design-map.md`. The gate owns the required flow reconstruction, reuse/evolution/separation evidence and whole-architecture assessment. Resolve every blocking condition before recording signatures or projecting tests; apply `SKILL.md`'s adjudication rule for departures.

Use the accepted decisions to derive operations, inputs/results, enforcing owners and owning test seams under the applicable stack conventions. Reopen the gate when new evidence changes an owner, acquisition path, retained result or dependency; a proposed caller/signature is not an accepted decision.

Project acceptance scenarios from the merged spec's criteria and enumerated edge cases before implementation. Tests of architectural/type guarantees trace to their governing rule at the owning module or seam; product specs need not restate implementation constraints. Follow `CONTRIBUTING.md`'s testing conventions rather than reverse-engineering tests from the code written.

## Implementation PR(s)

Implement the approved behavior within the checked design map and [`SKILL.md`](SKILL.md#setup)'s implementation sequence. Split further within those stages when needed for a focused review; each PR links the merged spec.

Make the tests pass, then run the gates `AGENTS.md` defines; never weaken a gate to pass one. Self-check the shared quality checklist, stack leaves selected under `SKILL.md` and [`implementation-completeness.md`](checklists/implementation-completeness.md) against this slice. Replace proposed design-map calls with actual symbols and consumption. Run a second whole-diff pass for callable surfaces, declarations, syntax, capabilities and test structure before the review protocol.

Apply [`SKILL.md`](SKILL.md#setup)'s slice-evidence gate before opening the PR. Keep local runs isolated from production state unless the maintainer explicitly authorizes that access.

The handoff identifies the exact baseline and candidate revision or content manifest, every changed/untracked file, design-map answers, checklist evidence, local confirmation and command results. Missing evidence is an open gate. Follow [`reviewer.md`](reviewer.md) for the independent review, repair verification and missed-rule recovery.

## Archive PR

Before archiving, run the implemented journey locally and compare it with the approved spec and the user's spike confirmation. Resolve any mismatch first.

After every Implementation PR has merged, read `SKILL.md`'s living-context policy, each affected template and the complete current owning document. Verify current facts against merged source before writing.

Integrate each `Doc Sync` knowledge delta into its owning section (`specs/*.md`, or `ci/specs/*.md` for pipeline changes). Synthesize at the document's strategic altitude rather than copying proposal text. Preserve precise details in their authoritative contracts or source, and prune stale explanations. Keep a local ledger mapping every delta to its integrated section or authoritative pointer.

Read the resulting documents as a new contributor: the system model and operating decisions must be clear without reconstructing the feature history. Apply the living-doc publication check to the whole affected documents, then move the spec to `specs/changes/archive/YYYY-MM-DD-<slug>/`.

Before opening this PR, self-check [`checklists/implementation-completeness.md`](checklists/implementation-completeness.md) against the **complete merged change**, not just the last PR, then satisfy `SKILL.md`'s PR-opening gate. This PR introduces no new product behavior.
