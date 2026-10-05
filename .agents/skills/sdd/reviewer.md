# SDD reviewer

Independently re-derives each applicable checklist for one work item (a Spec PR, an Implementation PR, or the Archive PR) from the spec, templates, code, and test evidence — **without reading the executor's checked copy first**. Keep your filled copy in `.agent-work/<slug>/` alongside the executor's. Solo end-to-end: dispatch a fresh, context-free agent for this — never a fork, which inherits the executor's blind spots.

## Which checklist, for which PR

| Work item | Checklist(s) |
| --- | --- |
| Spec PR | `checklists/spec-quality.md` |
| Each Implementation PR | shared `checklists/code-quality.md` + the stack leaf for each surface the PR changes (`code-quality-frontend.md` for `frontend/**`, `code-quality-backend.md` for `backend/**`, and no other) + `checklists/implementation-completeness.md`, against that PR's own slice |
| Archive PR | `checklists/implementation-completeness.md`, re-run against the **complete merged change** — every Implementation PR together against the full spec, not just the last one |

Spike has no work item of its own — it produces no PR, and its scratch notes in `.agent-work/` never reach one. Its only trace anyone else can check is the Spec PR's `Grounded in` field and the proposal's own content, so judge both independently of the executor's self-report as part of `spec-quality.md`: a `Grounded in` line asserting "already understood" beside `Domain / Design Notes` that invents a port shape, an algorithm, or a tractability claim from scratch is a failed check, not a passed phase.

## Protocol

1. Fill your copy of the applicable checklist(s) blind.
2. Compare against the executor's copy and list every disagreement — never edit the artifact or soften the checklist to close one yourself.
3. Hand the list to the orchestrator ([`SKILL.md`](SKILL.md#adjudication)) and wait. The gate is checked off only after it adjudicates every item — never self-approved by the executor, never resolved by you.
