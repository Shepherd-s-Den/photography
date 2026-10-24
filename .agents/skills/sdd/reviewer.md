# SDD reviewer

Independently re-derive each applicable checklist for one work item (a Spec PR, an Implementation PR, or the Archive PR) from its source artifacts and evidence. Every work item requires **two sequential rounds**, each performed by a fresh, context-free agent; never reuse or fork the first reviewer.

## Which checklist, for which PR

| Work item | Checklist(s) |
| --- | --- |
| Spec PR | `checklists/spec-quality.md` |
| Each Implementation PR | shared `checklists/code-quality.md` + the stack leaf for each surface the PR changes (`code-quality-frontend.md` for `frontend/**`, `code-quality-backend.md` for `backend/**`, and no other) + `checklists/implementation-completeness.md`, against that PR's own slice |
| Archive PR | `checklists/implementation-completeness.md`, re-run against the **complete merged change** — every Implementation PR together against the full spec, not just the last one |

Spike has no work item of its own — it produces no PR, and its scratch notes in `.agent-work/` never reach one. Its only trace anyone else can check is the Spec PR's `Grounded in` field and the proposal's own content. Verify that grounding names concrete work actually tried and findings actually observed. Treat unsupported claims in `Grounded in` or elsewhere as ungrounded; a general assertion beside invented design details does not pass.

## Protocol

1. **Review, twice in sequence.** Each round is the same: the orchestrator launches a fresh reviewer that re-derives every applicable checklist and checks the work item against its sources, the wrapping check, and, for specs, the spec-quality items. Round two starts from round one's findings and dispositions. The reviewer only reports exact findings with passages; it never edits or approves. Results go in `.agent-work/<slug>/review-<n>/`.
2. **Adjudicate and fix after each round.** The orchestrator decides each finding, fixes accepted ones, and records justified exceptions for rejected ones. Then it launches the next round.
3. **Publication:** report both rounds and their dispositions to the orchestrator for `SKILL.md`'s PR-opening gate. Review the local branch before publication; a GitHub PR is not required to run these rounds. Merge remains a separate maintainer decision.

Every work item (Spec, Implementation, and Archive PR) gets both rounds in step 1, each performed by a new fresh reviewer, using the applicable checklist row above. A single reviewer never satisfies the review gate for any work item.
