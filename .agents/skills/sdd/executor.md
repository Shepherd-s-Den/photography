# SDD executor

Does the work for one phase at a time, in order, as sequenced by [`SKILL.md`](SKILL.md). Each phase below names its checklist or template — read that file; do not restate it here.

## Classify

Decide `feature` (planned change, refactor, migration — you already know the new behavior) or `investigation` (bug/incident — the spec is a trail toward a root cause). Name the branch `feature/<slug>` or `investigation/<slug>` and record the classification in `.agent-work/<slug>/checklist.md`.

Then decide whether a spec is required: draft the Doc Sync entry first — one line naming a doc, section, and specific fact. A non-empty entry means write the spec; a pure refactor or internal cleanup with nothing to put there doesn't need one. Only the maintainer may waive this requirement, explicitly and for a specific change — record the decision (or waiver) in `.agent-work/<slug>/checklist.md`.

Also make the complexity call here, before the spec (`DESIGN-PRINCIPLES.md` §7): whether this behavior is served by growing an existing flow or needs new machinery, and whether a unit the change will cross grows past a single responsibility and must split as part of it. "You don't need this" and "split this first" are valid, in-scope outcomes. Record the call in `.agent-work/<slug>/checklist.md`; by spec time it is already decided, and the spec only reflects it.

## Spike

Every feature gets a spike, including one that seems understood on paper. Pin and record the stable `main` commit, then layer only the missing journey onto that real app in ignored `.agent-work/<slug>/spike/`. Keep frontend sources and dependencies in `frontend/`, optional backend route composition in `backend/`, and scratch data in `data/`.

Set `.agent-work/active-prototype` to the slug and run `python3 scripts/local.py`. The launcher starts the real backend with an isolated SQLite database, starts the spike frontend, and opens its local URL. When backend behavior is needed, provide `backend/photography/spike/Main.scala`; the launcher sets `PHOTOGRAPHY_PROTOTYPE_MODE=1` only for that backend process, compiling ignored spike sources and relaxing compiler, Wartremover, and coverage enforcement for the local run. Ordinary builds and Dagger checks leave the flag unset. Without that entry point, the launcher runs the stable backend with normal checks.

Keep the spike focused and disposable. No tests, coverage, code-quality checklist, or production gates apply; build only what is needed to demonstrate the journey. Keep every prototype source, config, dependency file, and scratch record under ignored `.agent-work/<slug>/`; never copy spike code into a product PR. Reuse this launcher contract for later features.

When spiking: actually try to solve it — throwaway code, a prototype of the hard part, working the algorithm by hand against real APIs — until you can state, concretely, the domain types, the ports, the real edge cases, and whether the approach is tractable at all. This code is disposable: it never needs to pass `AGENTS.md`'s gates, is never the Implementation PR's diff, and is not reviewed.

Show the user the running journey and iterate until they confirm it. Record the stable base revision, exact command, URL, decision, and findings in `.agent-work/<slug>/checklist.md`. In the Spec PR, `Grounded in` names observed findings; carry distinct design consequences into `Domain / Design Notes`.

## Spec PR

For a feature, wait until the user has run the local spike and confirmed the intended behavior before preparing or finalizing the proposal. Carry that confirmed behavior into the spec; a review pass over an unconfirmed draft does not satisfy the Spec PR gate.

Read [`templates/change-spec.md`](templates/change-spec.md) fresh — never recreate it from memory. Read the closest approved precedent spec when one exists; otherwise use the template and `spec-quality.md` for altitude and density. When a spike preceded this phase, ground `Domain / Design Notes` and `Alternatives Considered` in its findings.

- **Feature:** current → new behavior, acceptance criteria, invariants, tradeoffs, Doc Sync.
- **Investigation:** hypotheses and evidence for/against each, narrowing to a proven root cause — the spec's `What & Why` ends up stating that root cause and the hypotheses it ruled out.

Self-check against [`checklists/spec-quality.md`](checklists/spec-quality.md), then open the Spec PR — the proposal and planning artifacts only, no production code. Self-check is prep, not review — hand off to [`reviewer.md`](reviewer.md) before anyone merges. Do not start implementation until it is reviewed and merged.

## Tests

Derive tests from the merged spec's acceptance criteria and enumerated edge cases, before writing implementation code. A test must trace to a spec statement and fail only when that stated behavior changes — never reverse-engineered from code already written. Follow `CONTRIBUTING.md`'s testing conventions for suite naming and tiering.

## Implementation PR(s)

Implement the approved behavior. Split into multiple PRs on separable seams, or when one PR would exceed a focused review session — never to dodge a gate. Each PR links the merged spec.

Make the tests pass, then run the gates `AGENTS.md` already defines (backend/frontend/pipeline commands and `dagger call verify`) — do not repeat those commands here, and never weaken a gate to pass one. Self-check against the shared [`checklists/code-quality.md`](checklists/code-quality.md) plus the stack leaf for each surface this PR changes ([`code-quality-frontend.md`](checklists/code-quality-frontend.md) for `frontend/**`, [`code-quality-backend.md`](checklists/code-quality-backend.md) for `backend/**`, and no other), and [`checklists/implementation-completeness.md`](checklists/implementation-completeness.md) (against this PR's own slice) before requesting review.

## Archive PR

Before archiving, run the implemented journey locally and compare it with the approved spec and the user's spike confirmation. Resolve any mismatch first.

After every Implementation PR has merged: apply each `Doc Sync` entry verbatim to the named living doc and section (`specs/*.md`, or `ci/specs/*.md` for pipeline changes) — prune obsolete or duplicated content rather than appending. Move the spec to `specs/changes/archive/YYYY-MM-DD-<slug>/`.

Before opening this PR, self-check [`checklists/implementation-completeness.md`](checklists/implementation-completeness.md) against the **complete merged change**, not just the last PR — this is the hard stop that catches a spec archived next to an incomplete implementation. This PR introduces no new product behavior.
