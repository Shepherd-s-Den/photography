# SDD executor

Does the work for one phase at a time, in order, as sequenced by [`SKILL.md`](SKILL.md). Each phase below names its checklist or template — read that file; do not restate it here.

## Classify

Decide `feature` (planned change, refactor, migration — you already know the new behavior) or `investigation` (bug/incident — the spec is a trail toward a root cause). Name the branch `feature/<slug>` or `investigation/<slug>` and record the classification in `.agent-work/<slug>/checklist.md`.

Then decide whether a spec is required: draft the Doc Sync entry first — one line naming a doc, section, and specific fact. A non-empty entry means write the spec; a pure refactor or internal cleanup with nothing to put there doesn't need one. Only the maintainer may waive this requirement, explicitly and for a specific change — record the decision (or waiver) in `.agent-work/<slug>/checklist.md`.

Also make the complexity call here, before the spec (`DESIGN-PRINCIPLES.md` §7): whether this behavior is served by growing an existing flow or needs new machinery, and whether a unit the change will cross grows past a single responsibility and must split as part of it. "You don't need this" and "split this first" are valid, in-scope outcomes. Record the call in `.agent-work/<slug>/checklist.md`; by spec time it is already decided, and the spec only reflects it.

## Spike

This is the load-bearing decision in this skill, not busywork before the "real" phase. A spec is a proposal for how to solve an understood problem — diagrams, domain types, ports, pseudocode where the design is algorithmic or efficiency-sensitive. Writing one before that understanding exists is exactly why specs go bad: it is an intricate floorplan drawn without ever having walked the site, and the building turns out untractable once someone tries to construct it. Understanding a problem space often requires trying to solve it, not just thinking hard about it in the abstract.

So decide, explicitly: is the problem and its solution already understood well enough to write the spec directly — you know exactly what to solve, how, and its acceptance criteria? Then skip straight to Spec PR. Otherwise — an unclear problem, a design of uncertain tractability, an algorithm, or any proposal you can't yet state in concrete domain types and ports — spike first.

When spiking: actually try to solve it — throwaway code, a prototype of the hard part, working the algorithm by hand against real APIs — until you can state, concretely, the domain types, the ports, the real edge cases, and whether the approach is tractable at all. This code is disposable: it never needs to pass `AGENTS.md`'s gates, is never the Implementation PR's diff, and is not reviewed.

Either way, the decision must show up somewhere it can be checked. `.agent-work/<slug>/checklist.md` is scratch and never reaches the PR, so the real record is the Spec PR's own `Grounded in` field: name the actual finding, or the actual reason none was needed — never a bare "already understood." Carry a spike's findings into `Domain / Design Notes` and `Alternatives Considered` too — never the same sentence twice — grounded in what you actually found rather than renewed first-principles guessing.

## Spec PR

Read [`templates/change-spec.md`](templates/change-spec.md) fresh — never recreate it from memory. Read the closest approved precedent spec (the archived capability-boundaries-and-doc-policy spec, for domain/port changes) for altitude and density; match its concision, not its exact headings. When a spike preceded this phase, ground `Domain / Design Notes` and `Alternatives Considered` in its findings.

- **Feature:** current → new behavior, acceptance criteria, invariants, tradeoffs, Doc Sync.
- **Investigation:** hypotheses and evidence for/against each, narrowing to a proven root cause — the spec's `What & Why` ends up stating that root cause and the hypotheses it ruled out.

Self-check against [`checklists/spec-quality.md`](checklists/spec-quality.md), then open the Spec PR — the proposal and planning artifacts only, no production code. Self-check is prep, not review — hand off to [`reviewer.md`](reviewer.md) before anyone merges. Do not start implementation until it is reviewed and merged.

## Tests

Derive tests from the merged spec's acceptance criteria and enumerated edge cases, before writing implementation code. A test must trace to a spec statement and fail only when that stated behavior changes — never reverse-engineered from code already written. Follow `CONTRIBUTING.md`'s testing conventions for suite naming and tiering.

## Implementation PR(s)

Implement the approved behavior. Split into multiple PRs on separable seams, or when one PR would exceed a focused review session — never to dodge a gate. Each PR links the merged spec.

Make the tests pass, then run the gates `AGENTS.md` already defines (backend/frontend/pipeline commands and `dagger call verify`) — do not repeat those commands here, and never weaken a gate to pass one. Self-check against the shared [`checklists/code-quality.md`](checklists/code-quality.md) plus the stack leaf for each surface this PR changes ([`code-quality-frontend.md`](checklists/code-quality-frontend.md) for `frontend/**`, [`code-quality-backend.md`](checklists/code-quality-backend.md) for `backend/**`, and no other), and [`checklists/implementation-completeness.md`](checklists/implementation-completeness.md) (against this PR's own slice) before requesting review.

## Archive PR

After every Implementation PR has merged: apply each `Doc Sync` entry verbatim to the named living doc and section (`specs/*.md`, or `ci/specs/*.md` for pipeline changes) — prune obsolete or duplicated content rather than appending. Move the spec to `specs/changes/archive/YYYY-MM-DD-<slug>/`.

Before opening this PR, self-check [`checklists/implementation-completeness.md`](checklists/implementation-completeness.md) against the **complete merged change**, not just the last PR — this is the hard stop that catches a spec archived next to an incomplete implementation. This PR introduces no new product behavior.
