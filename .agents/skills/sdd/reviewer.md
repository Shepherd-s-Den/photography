# SDD reviewer

Independently re-derive each applicable checklist for one work item (a Spec PR, an Implementation PR, or the Archive PR) from its source artifacts and evidence. Each work item has **one independent review phase**, started by one fresh, context-free agent. Retain that reviewer for targeted repair verification within the same phase.

Repair verification and affected late-change checks complete that phase. Completion requires a final-candidate assessment with every finding adjudicated and every accepted repair independently verified.

## Which checklist, for which PR

| Work item | Checklist(s) |
| --- | --- |
| Spec PR | `checklists/spec-quality.md` |
| Each Implementation PR | Shared quality + stack leaves selected under `SKILL.md` + implementation completeness, against this slice. Re-derive every item from source, not the executor's statuses. |
| Archive PR | `checklists/implementation-completeness.md`, re-run against the **complete merged change** — every Implementation PR together against the full spec, not just the last one |

Spike has no work item of its own — it produces no PR, and its scratch notes in `.agent-work/` never reach one. Its only trace anyone else can check is the Spec PR's `Grounded in` field and the proposal's own content. Verify that grounding names concrete work actually tried and findings actually observed. Treat unsupported claims in `Grounded in` or elsewhere as ungrounded; a general assertion beside invented design details does not pass.

## Review priorities

**Architecture and conventions are gates, not optional commentary.** Read `DESIGN-PRINCIPLES.md`, `CONTRIBUTING.md` and applicable stack rules before judging completeness against the spec.

| Area | Required evidence |
| --- | --- |
| Convention following | Check the complete changed surface for naming, types, declarations, composition, formatting/readable density, errors and test structure; cite the governing rule for violations. |
| Design affinity | Trace responsibility, dependency direction, fixed collaborator injection, policy ownership and resource lifetimes against the repository's principles and real precedents. |
| Seam definition | Inventory every method and parameter: triggering flow, input supplier, consumed fields, cardinality, result consumer and enforcing owner. Try deleting each method/argument through existing boundaries or loaded data; justify retained batches and scope independently of identity. Check domain vocabulary, success-value inputs, operation-owned results, adapter responsibility and real transaction/resource semantics. |
| Behavioral correctness | Prove criteria and edge cases at their owning boundaries, including concurrency, failure, cancellation and delivery; passing tests alone do not prove the preceding rows. |

Report each area as pass, fail or not applicable with source evidence. For a Spec PR, judge observable domain/boundary contracts at the altitude required by `spec-quality.md`; signature-level seam evidence belongs to implementation. For implementation, inspect every changed declaration/call and its real consumer.

## Protocol

1. **Derive independently.** Launch a fresh reviewer with the request, scope, baseline/candidate, authoritative sources and applicable checklists. Before executor evidence or prior findings, derive responsibilities, callers/results, invariants, effects, dependency owners and test boundaries from source and the complete diff. Apply the shared architecture gate's ownership, sharing, layer-removal and seam checks. Ground answers in `DESIGN-PRINCIPLES.md` §§1, 3 and 5; record the first-pass map and concrete counterexamples before reconciliation.
2. **Challenge and reconcile.** Compare executor evidence with that independent map. Record blocking counterexamples and justified controls: unnecessary batch/scope, result envelopes as inputs, global work in a per-item flow and duplicated relational behavior must fail when present. Report every priority area/checklist item with source evidence; include all changed/untracked files in a coverage ledger and name unresolved areas.
3. **Adjudicate findings.** The reviewer reports exact violations, their governing rule and the caller or ownership consequence; it never edits the candidate or grants merge approval. The orchestrator fixes accepted findings and records evidence for rejected ones. Keep reports/dispositions in `.agent-work/<slug>/review/`.
4. **Verify final repairs.** The same independent reviewer rechecks repairs and affected late changes against the exact final candidate; rerun affected gates and record the snapshot each assessment covers. Keep the phase open until every accepted repair is independently verified; this completes the one phase rather than starting another whole-work-item review.
5. **Publication:** the orchestrator verifies the final checklist evidence, independent assessment and repair verification before applying `SKILL.md`'s PR-opening gate. Missing or disputed evidence remains a blocker. Merge remains a separate maintainer decision.

## What blocks a pass

Architectural and convention violations are findings even when tests pass and no runtime defect is demonstrated. Apply the shared architecture questions and selected stack rules before checking implementation coverage; a tested implementation is not evidence that its boundaries are correct.

Review prompts must not pre-label disputed architecture as correct, supply the implementer's size justifications as conclusions, or filter out required conventions as “style noise.” A reviewer may reject a finding with evidence; it may not narrow the governing checklist to high-severity runtime bugs.

When a maintainer identifies a violation missed by a review, record the missed rule and failed reasoning, reopen its gate, and apply the corrected check to the complete diff. Replaying the known-bad decision must now produce a blocking finding; adding prose without demonstrating that result does not validate the revised gate.

For ownership/seam corrections, replay three failure modes: independently changeable resource rules centralized in one client, thin wrappers retaining that centralized owner, and an extra adapter that only orchestrates our own ports with a fictional seam suite. Also check the valid counterexample: a direct adapter may make several external calls to fulfill one existing port without another production layer. Record each violated rule and caller consequence, rather than a name-based blacklist or a passing-test claim.
