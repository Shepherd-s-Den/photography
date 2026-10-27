# SDD reviewer

Independently re-derive each applicable checklist for one work item (a Spec PR, an Implementation PR, or the Archive PR) from its source artifacts and evidence. Each work item has **one independent review phase**, started by one fresh, context-free agent.

The same reviewer verifies repairs and affected late changes within that phase. Completion requires a final-candidate assessment with every finding adjudicated and every accepted repair independently verified.

## Which checklist, for which PR

| Work item | Checklist(s) |
| --- | --- |
| Spec PR | `checklists/spec-quality.md` |
| Each Implementation PR | shared `checklists/code-quality.md` + the stack leaf for each surface the PR changes (`code-quality-frontend.md` for `frontend/**`, `code-quality-backend.md` for `backend/**`, and no other) + `checklists/implementation-completeness.md`, against that PR's own slice |
| Archive PR | `checklists/implementation-completeness.md`, re-run against the **complete merged change** — every Implementation PR together against the full spec, not just the last one |

Spike has no work item of its own — it produces no PR, and its scratch notes in `.agent-work/` never reach one. Its only trace anyone else can check is the Spec PR's `Grounded in` field and the proposal's own content. Verify that grounding names concrete work actually tried and findings actually observed. Treat unsupported claims in `Grounded in` or elsewhere as ungrounded; a general assertion beside invented design details does not pass.

## Review priorities

**Architecture and conventions are gates, not optional commentary.** Read `DESIGN-PRINCIPLES.md`, `CONTRIBUTING.md` and applicable stack rules before judging completeness against the spec.

| Area | Required evidence |
| --- | --- |
| Convention following | Check the complete changed surface for naming, types, declarations, composition, formatting/readable density, errors and test structure; cite the governing rule for violations. |
| Design affinity | Trace responsibility, dependency direction, fixed collaborator injection, policy ownership and resource lifetimes against the repository's principles and real precedents. |
| Seam definition | Name each consumer, the work it needs, inputs/results, effects and enforcing owner; challenge pass-through abstractions, adapter leakage, split decisions and duplicated authority. |
| Behavioral correctness | Prove criteria and edge cases at their owning boundaries, including concurrency, failure, cancellation and delivery; passing tests alone do not prove the preceding rows. |

Report each area as pass, fail or not applicable with source evidence. For a Spec PR, judge proposed contracts; for implementation, inspect every changed declaration/call and its real consumer.

## Protocol

1. **Derive independently.** Launch a fresh reviewer with the request, scope, baseline/candidate, authoritative sources and applicable checklists. Before executor evidence or prior findings, derive responsibilities, callers/results, invariants, effects, dependency owners and test boundaries from source and the complete diff. Challenge each seam: can either side change independently, and does each rule have one enforcing owner? Ground answers in `DESIGN-PRINCIPLES.md` §§1, 3 and 5; record the first-pass map and concrete counterexamples before reconciliation.
2. **Challenge and reconcile.** Compare executor evidence with that independent map. Report every priority area and checklist item as pass, fail or not applicable with source evidence. Include all changed/untracked files in a coverage ledger; name unread or unresolved areas.
3. **Adjudicate findings.** The reviewer reports exact violations, their governing rule and the caller or ownership consequence; it never edits the candidate or grants merge approval. The orchestrator fixes accepted findings and records evidence for rejected ones. Keep reports/dispositions in `.agent-work/<slug>/review/`.
4. **Verify final repairs.** Send repairs and late changes back to the same reviewer for an affected-surface recheck against the exact final candidate; rerun affected gates. This is completion of the one review phase, not another fresh review.
5. **Publication:** the orchestrator verifies the final checklist evidence, independent assessment and repair verification before applying `SKILL.md`'s PR-opening gate. Missing or disputed evidence remains a blocker. Merge remains a separate maintainer decision.
