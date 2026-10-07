# SDD reviewer

Independently re-derive each applicable checklist for one work item (a Spec PR, an Implementation PR, or the Archive PR) from its source artifacts and evidence. Spec PRs require **two sequential rounds**, each performed by a fresh, context-free agent; never reuse or fork the first reviewer.

## Which checklist, for which PR

| Work item | Checklist(s) |
| --- | --- |
| Spec PR | `checklists/spec-quality.md` |
| Each Implementation PR | shared `checklists/code-quality.md` + the stack leaf for each surface the PR changes (`code-quality-frontend.md` for `frontend/**`, `code-quality-backend.md` for `backend/**`, and no other) + `checklists/implementation-completeness.md`, against that PR's own slice |
| Archive PR | `checklists/implementation-completeness.md`, re-run against the **complete merged change** — every Implementation PR together against the full spec, not just the last one |

Spike has no work item of its own — it produces no PR, and its scratch notes in `.agent-work/` never reach one. Its only trace anyone else can check is the Spec PR's `Grounded in` field and the proposal's own content. Verify that grounding names concrete work actually tried and findings actually observed. Treat unsupported claims in `Grounded in` or elsewhere as ungrounded; a general assertion beside invented design details does not pass.

## Protocol

1. **Round one — diagnose.** A fresh reviewer reads the work item, template, checklist,
   SDD instructions, and relevant sources. It fills `.agent-work/<slug>/review-1/`
   without seeing executor or prior reviews, then cites each disagreement and its fix.
   For specs, flag slop, weak grounding, visual violations, and unjustified Doc Sync
   or Out of Scope entries.
2. **Adjudicate and revise.** The executor records decisions and fixes every finding.
   The first reviewer never edits or approves the work item.
3. **Round two — independently re-review.** A second fresh reviewer reads sources anew
   and fills `.agent-work/<slug>/review-2/` before seeing either checklist or findings.
   Then it compares checklists, checks round-one findings, and reports unresolved or new
   issues with exact passages. It does not edit the spec.
4. **Close the gate.** Resolve every round-two finding and adjudicate all disputes.
   Merge only after both rounds; no self-approval.

For Implementation and Archive PRs, use one fresh reviewer and the applicable checklist row above, unless the work item is also a Spec PR.
