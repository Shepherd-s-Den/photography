---
name: sdd
description: >-
  Use for product changes, investigations, change specs and living-document synchronization. Features start with a user-confirmed local spike before their spec.
---

# SDD — spec-driven development

This file sequences classify → spike → spec → tests → implementation → local verification and archive. [`executor.md`](executor.md) defines each phase; [`reviewer.md`](reviewer.md) independently gates it. Spike code is local evidence, never an implementation candidate. Investigations classify first and spike when needed. Complexity and any split (`DESIGN-PRINCIPLES.md` §7) are decided at Classify and checked again at the code-quality gate.

## Change discipline

Make the smallest change that satisfies the request. Keep each rule in one owning document; update other docs only when their owned facts change, and keep changes proportional to the product slice.

**Living context:** when planning Doc Sync, authoring living docs or reviewing their changes, read the [living-doc quality policy](../../../specs/templates/README.md) and each affected document's template. They own the strategic altitude and knowledge-placement gate; detailed acceptance evidence stays with the change.

**Spec amendments during implementation:** ask the maintainer before changing an approved spec. Once authorized, amend it in the Implementation PR when implementation reveals a changed contract. Update the affected criteria before their tests and implementation; include the amendment in the independent review against `spec-quality.md` as well as the implementation checklists.

## Setup

**Feature lifecycle:** keep spec, implementation and archive on one `feature/<slug>` branch in the primary worktree, preserving existing edits. Spikes follow the procedure below; fresh review agents follow `reviewer.md`.

**Slice loop:** complete the current slice and the PR-opening gate below, then open its PR against `main`. Wait for the maintainer-authorized merge, fetch updated `main`, and rebase the same feature branch onto it before starting the next slice. Retain only work not already merged; after a squash merge, reconcile the merged slice's commits rather than replaying them. Verify that the branch includes updated `main` with no already-merged slice commits remaining, and record the new baseline in the local checklist. Continue the next slice on that same branch through the final archive PR.

For each feature, pin a stable `main` revision. Keep spike snapshots, scratch data, and phase evidence under ignored `.agent-work/<slug>/`; use the real app and its local launcher, adding only the behavior under exploration.

Before Phase 1, create `.agent-work/<slug>/checklist.md` tracking lifecycle gates and linking applicable source checklists with their revision or content fingerprint. Record each item's status and evidence there rather than copying rule text. Classify every changed/untracked file: `backend/**` and `frontend/**` load [`checklists/code-quality.md`](checklists/code-quality.md) plus their respective stack leaves; a mixed slice loads both.

Re-read that checklist before every phase's work, including after any interruption, tangent, or unrelated request. Do not open a PR, hand off for review, or start the next phase while an earlier gate sits unchecked — creating the checklist once is not the same as following it.

**Architecture before implementation:** follow [`executor.md`](executor.md#tests)'s caller-first design-map gate before tests or production changes. Review evidence, final repairs and handling missed rules follow [`reviewer.md`](reviewer.md).

**PR-opening gate:** complete the executor's self-checks and the single independent review phase in [`reviewer.md`](reviewer.md), adjudicate every finding, independently verify final repairs, and rerun affected gates before publishing or updating a ready PR. Record evidence and dispositions against the final candidate in the local checklist. “Stop once the PR is open” is an endpoint, not a waiver of these prerequisites; only an explicit maintainer waiver changes them.

## Spike

Create a dedicated spike worktree at the pinned `main` revision before exploration. Run the real app there through `python3 scripts/local.py`, leaving spike code uncommitted.

Keep scratch data and evidence under ignored `.agent-work/<slug>/spike/` in the primary checkout. Record the worktree's absolute path, base, launch command, URL and findings in the primary checklist for orchestrator inspection and reruns.

Build against the same compiler, type, lint, format, test, and app-start checks used by the product. Skip only coverage during exploration: run frontend tests with `npx vitest run` rather than the coverage-enabled package script, and run backend tests without the coverage task. Carry confirmed findings into the Spec PR.

Retain the runnable spike through implementation and final local comparison. After the Archive + Living Docs PR merges, stop its owned services and remove that feature's spike worktree.

## Adjudication

When executor and reviewer disagree, the orchestrator adjudicates against the governing rule. Rejecting a finding requires evidence that the rule is satisfied or inapplicable; accepting it requires a fix. Departing from a violated rule requires explicit maintainer authorization cited in the local checklist. A rationale alone is not an exception, and the gate remains blocked until resolved.

## Phases

Before drafting a spec, read the archived [Album dashboard and open](../../../specs/changes/archive/2026-10-24-album-dashboard-and-open/proposal.md) for useful flow diagrams and observable criteria; the current template and quality checklist govern altitude and density. Derive product facts from current living docs.

1. **Classify** — `feature` or `investigation`; branch named to match. Per AGENTS.md, a spec is required only when the change adds or revises knowledge a living doc should record; only the maintainer may waive that requirement, explicitly. Gate: the classification, the spec requirement (or waiver), and the build-vs-reuse/split-now call (`DESIGN-PRINCIPLES.md` §7) are recorded.
2. **Spike** — once per feature, before its spec, using the workflow above. Gate: the user confirms the journey and its findings are recorded for the spec; reuse that confirmation across its Implementation PRs.
3. **Spec PR** — After spike confirmation, follow [`executor.md`](executor.md#spec-pr)'s behavior/contract-first guidance using the active model, living docs and confirmed findings. Run the Markdown wrapping check on the proposal and every changed Markdown file; failure blocks the PR. Pass `spec-quality.md`, keep criteria observable and Doc Sync minimal, complete the independent review in [`reviewer.md`](reviewer.md), adjudicate findings, and merge before implementation.
4. **Tests** — projected from the merged spec, before implementation. Gate: every acceptance criterion and enumerated edge case maps to a test; changed tests fail for the right reason first.
5. **Implementation PR(s)** — one or more, each within the approved spec. Gate: run the slice locally against its real boundaries and obtain maintainer confirmation of its behavior before opening its PR; every gate in `AGENTS.md` exits zero; [`checklists/code-quality.md`](checklists/code-quality.md) plus the stack leaf for each surface this PR changes, and [`checklists/implementation-completeness.md`](checklists/implementation-completeness.md) (this PR's slice), pass.
6. **Local verification, then Archive + Living Docs PR** — after every Implementation PR has merged, run the implemented journey through the local application and check it against the approved spec and the behavior the user confirmed in the spike. Resolve discrepancies before archiving. The Archive PR introduces no new product behavior. Gate: the local journey matches the spec; every `Doc Sync` knowledge delta is integrated at its owning section under the living-doc quality policy; [`checklists/implementation-completeness.md`](checklists/implementation-completeness.md) passes against the complete merged change; the spec is moved to `specs/changes/archive/YYYY-MM-DD-<slug>/`.

Phase detail: [`executor.md`](executor.md). Review protocol: [`reviewer.md`](reviewer.md). Change-spec template: [`templates/change-spec.md`](templates/change-spec.md). `superpowers:brainstorming`, `superpowers:writing-plans`, and `superpowers:test-driven-development` are available when a design or test set is non-obvious.
