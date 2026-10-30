# <Title>

<!-- Draft diagrams/tables first; add only necessary prose. The blocking visual-form gate is in checklists/spec-quality.md. -->

**Date:** <YYYY-MM-DD>

<!-- Required. `feature` (planned change, refactor, migration) or `investigation` (bug/incident).
Must match the branch prefix. -->

**Classification:** <feature|investigation>

<!-- Required. What a spike actually tried and found, or a specific, checkable reason no
exploration was needed — never a bare assertion like "already understood." -->

**Grounded in:** <finding or reason>

<!-- One sentence describing the change. -->

## What & Why

<!-- Current behavior → new behavior and why, at behavior/intent/domain-contract altitude.
Keep signatures, code structure, file layouts and diff walkthroughs in implementation evidence. -->

## Domain / Design Notes

<!-- Optional. Include when domain, persisted-data or boundary contracts change. State
observable guarantees and who enforces them; name constraint mechanisms when needed to
make the contract testable. Follow executor.md's Behavior/contract first criterion. -->

## Alternatives Considered

<!-- Optional. Real alternatives and why they were rejected. -->

## Invariants

<!-- Optional. Pre-existing guarantees that must remain true, pass/fail language. This change's
own rules go in Acceptance Criteria, not here. -->

- <invariant>

## Tradeoffs Accepted

<!-- Optional. Name the concrete downside and who bears it. No real cost means no entry. -->

## Acceptance Criteria

<!-- Required. Each item externally observable/testable, including failure, boundary, and
accessibility behavior where relevant. -->

- <criterion>

## Doc Sync

<!-- Required. Read specs/templates/README.md and the affected living-doc templates first.
Each entry is a knowledge delta, not copy-ready prose: the durable change to the system model,
boundary ownership, testing strategy or operating model. Point precise details to their
authoritative artifact. Acceptance criteria and per-scenario evidence stay with this change.
Say "None" if no living knowledge changes. -->

- <doc> — <section>: <durable knowledge to revise>; <detail authority when needed>

## Out of Scope

<!-- Optional, max two bullets. Only what a reader would reasonably wonder about — not a
restatement of what Acceptance Criteria already excludes. -->
