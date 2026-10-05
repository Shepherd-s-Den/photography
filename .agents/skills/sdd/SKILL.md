---
name: sdd
description: >-
  Spec-driven development for this repo. Use for every product change — features (planned changes, refactors, migrations) and investigations (bugs, incidents). Classifies the change, then sequences classify → (spike) → spec → tests → implement → sync & archive against the Agentic Engineering Standards. Trigger when asked to add a feature, fix a bug, change behaviour, or "write a change spec".
---

# SDD — spec-driven development

Standard: Agentic Engineering Standards v1.2.0. This file is the orchestrator: it classifies the change and sequences the phases below. [`executor.md`](executor.md) does each phase's work; [`reviewer.md`](reviewer.md) independently gates it. A spec is not the first step. Treating it as one — writing it before the problem space is understood — is why specs so often turn out wrong; understanding frequently requires trying to solve the problem, not just reasoning about it. Phase 2 (Spike) exists to force that understanding whenever it isn't already there. Scale ceremony to the change — a one-line behavior tweak needs a short spec and a couple of tests, a new subsystem needs the full treatment — but the phase order and every gate below are non-negotiable. Complexity is held down as the system grows, not after: the build-vs-reuse call and any split (`DESIGN-PRINCIPLES.md` §7) are decided at Classify, before the spec, and measured again at the code-quality gate.

## Setup

Before Phase 1, create `.agent-work/<slug>/checklist.md` listing every gate below plus the full text of each checklist that will apply. For an Implementation PR that means the shared [`checklists/code-quality.md`](checklists/code-quality.md) plus the stack leaf for each surface the PR changes (`frontend/**` → frontend, `backend/**` → backend) — never the other stack's leaf. Check items off as you go. A checked box is a claim to verify, not proof by itself — see [`reviewer.md`](reviewer.md) for how it gets verified.

Re-read that checklist before every phase's work, including after any interruption, tangent, or unrelated request. Do not open a PR, hand off for review, or start the next phase while an earlier gate sits unchecked — creating the checklist once is not the same as following it.

## Adjudication

When executor and reviewer disagree, this file decides — the reviewer only reports, the executor's work is already done. Weigh the disputed artifact against the checklist item, pick a side, then direct a fix or record a justified exception in `.agent-work/<slug>/checklist.md`. A phase gate needing that checklist waits on this.

## Phases

1. **Classify** — `feature` or `investigation`; branch named to match. Per AGENTS.md, a spec is required only when the change adds or revises knowledge a living doc should record; only the maintainer may waive that requirement, explicitly. Gate: the classification, the spec requirement (or waiver), and the build-vs-reuse/split-now call (`DESIGN-PRINCIPLES.md` §7) are recorded.
2. **Spike** — is the problem and its solution already understood well enough to spec directly, or does the space need exploring first (an unclear problem, a design of uncertain tractability, an algorithm, anything not yet statable in concrete domain types and ports)? When unclear, explore by actually trying to solve it — throwaway code, a prototype of the hard part, working an algorithm by hand — until the domain shape, the ports, and the real edge cases are known, not guessed. Gate: the skip-or-spike decision, and any findings, are recorded in `.agent-work/<slug>/` and carried into the spec; nothing from this phase is required to pass `AGENTS.md`'s gates or to survive into the Implementation PR.
3. **Spec PR** — reviewed and merged before any implementation starts. Self-check isn't review. Gate: a second, independently-filled [`checklists/spec-quality.md`](checklists/spec-quality.md) copy is filed against the executor's, every disagreement is adjudicated (see Adjudication), and the PR is merged.
4. **Tests** — projected from the merged spec, before implementation. Gate: every acceptance criterion and enumerated edge case maps to a test; changed tests fail for the right reason first.
5. **Implementation PR(s)** — one or more, each within the approved spec. Gate: every gate in `AGENTS.md` exits zero; the shared [`checklists/code-quality.md`](checklists/code-quality.md) plus the stack leaf for each surface this PR changes, and [`checklists/implementation-completeness.md`](checklists/implementation-completeness.md) (this PR's slice), pass.
6. **Archive + Living Docs PR** — after every Implementation PR has merged; introduces no new product behavior. Gate: every `Doc Sync` entry applied verbatim; [`checklists/implementation-completeness.md`](checklists/implementation-completeness.md) passes against the complete merged change; the spec is moved to `specs/changes/archive/YYYY-MM-DD-<slug>/`.

Phase detail: [`executor.md`](executor.md). Review protocol: [`reviewer.md`](reviewer.md). Change-spec template: [`templates/change-spec.md`](templates/change-spec.md). `superpowers:brainstorming`, `superpowers:writing-plans`, and `superpowers:test-driven-development` are available when a design or test set is non-obvious.
