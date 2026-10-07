---
name: sdd
description: >-
  Use for product changes (features, refactors, migrations) and investigations (bugs, incidents). Every feature starts with a local spike confirmed by the user before its spec. Trigger when asked to add or change behavior, fix a bug, investigate an incident, or write a change spec.
---

# SDD — spec-driven development

This file sequences classify → spike → spec → tests → implementation → local verification and archive. [`executor.md`](executor.md) defines each phase; [`reviewer.md`](reviewer.md) independently gates it. Spike code is local evidence, never an implementation candidate. Investigations classify first and spike when needed. Complexity and any split (`DESIGN-PRINCIPLES.md` §7) are decided at Classify and checked again at the code-quality gate.

## Change discipline

Make the smallest change that satisfies the request. Keep each rule in one owning document; update other docs only when their owned facts change, and keep changes proportional to the product slice.

## Setup

For each feature, pin a stable `main` revision. Keep spike source, dependencies, configuration, and scratch data under ignored `.agent-work/<slug>/spike/`; use the real app and its local launcher, adding only the behavior under exploration.

Before Phase 1, create `.agent-work/<slug>/checklist.md` listing every gate below plus the full text of each checklist that will apply. For an Implementation PR that means the shared [`checklists/code-quality.md`](checklists/code-quality.md) plus the stack leaf for each surface the PR changes (`frontend/**` → frontend, `backend/**` → backend) — never the other stack's leaf. Check items off as you go. A checked box is a claim to verify, not proof by itself — see [`reviewer.md`](reviewer.md) for how it gets verified.

Re-read that checklist before every phase's work, including after any interruption, tangent, or unrelated request. Do not open a PR, hand off for review, or start the next phase while an earlier gate sits unchecked — creating the checklist once is not the same as following it.

## Spike

Record the stable `main` commit, then create a disposable worktree from it with `git worktree add --detach .agent-work/<slug>/worktree <commit>`. Put the spike in the checked-out app's normal source paths and leave it uncommitted. `python3 scripts/local.py` runs the current checkout as-is; it does not select, inject, or configure spike code. The separate worktree keeps local data isolated.

Build against the same compiler, type, lint, format, test, and app-start checks used by the product. Skip only coverage during exploration: run frontend tests with `npx vitest run` rather than the coverage-enabled package script, and run backend tests without the coverage task. Iterate on the real journey until the user confirms it. Record the worktree's base commit, command, URL, decision, and findings in `.agent-work/<slug>/checklist.md`; carry the findings into the Spec PR. Keep the worktree until the implementation has merged, then remove it.

## Adjudication

When executor and reviewer disagree, this file decides — the reviewer only reports, the executor's work is already done. Weigh the disputed artifact against the checklist item, pick a side, then direct a fix or record a justified exception in `.agent-work/<slug>/checklist.md`. A phase gate needing that checklist waits on this.

## Phases

1. **Classify** — `feature` or `investigation`; branch named to match. Per AGENTS.md, a spec is required only when the change adds or revises knowledge a living doc should record; only the maintainer may waive that requirement, explicitly. Gate: the classification, the spec requirement (or waiver), and the build-vs-reuse/split-now call (`DESIGN-PRINCIPLES.md` §7) are recorded.
2. **Spike** — every feature gets a spike using the workflow above. Gate: the user confirms the journey and its findings are recorded for the spec.
3. **Spec PR** — Start after the user confirms the spike. Use the active model, living docs,
   and spike as authority. Draft visual structure first; add only necessary prose and pass
   the visual-form gate in `spec-quality.md`. Keep criteria observable and Doc Sync minimal.
   Complete both independent review rounds in [`reviewer.md`](reviewer.md), adjudicate every
   finding, then merge before implementation.
4. **Tests** — projected from the merged spec, before implementation. Gate: every acceptance criterion and enumerated edge case maps to a test; changed tests fail for the right reason first.
5. **Implementation PR(s)** — one or more, each within the approved spec. Gate: every gate in `AGENTS.md` exits zero; the shared [`checklists/code-quality.md`](checklists/code-quality.md) plus the stack leaf for each surface this PR changes, and [`checklists/implementation-completeness.md`](checklists/implementation-completeness.md) (this PR's slice), pass.
6. **Local verification, then Archive + Living Docs PR** — after every Implementation PR has merged, run the implemented journey through the local application and check it against the approved spec and the behavior the user confirmed in the spike. Resolve discrepancies before archiving. The Archive PR introduces no new product behavior. Gate: the local journey matches the spec; every `Doc Sync` entry is applied verbatim; [`checklists/implementation-completeness.md`](checklists/implementation-completeness.md) passes against the complete merged change; the spec is moved to `specs/changes/archive/YYYY-MM-DD-<slug>/`.

Phase detail: [`executor.md`](executor.md). Review protocol: [`reviewer.md`](reviewer.md). Change-spec template: [`templates/change-spec.md`](templates/change-spec.md). `superpowers:brainstorming`, `superpowers:writing-plans`, and `superpowers:test-driven-development` are available when a design or test set is non-obvious.
