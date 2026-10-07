# <Title>

<!-- Boundary clarifications or risk callouts attach inline to the section they qualify. -->

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
Avoid code structure and diff walkthroughs; name a tool only when it is part of the agreed
behavior. -->

## Domain / Design Notes

<!-- Include when domain, persisted-data, or boundary contracts change. State the guarantees
and who enforces them; name constraint mechanisms when that makes the contract testable. -->

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

<!-- Required. Name only docs that need a durable update. Point to the authoritative artifact
instead of repeating its contents. Say "None" if no living doc changes. -->

- <doc> — <section>: <specific knowledge to add or revise>

## Out of Scope

<!-- Optional, max two bullets. Only what a reader would reasonably wonder about — not a
restatement of what Acceptance Criteria already excludes. -->
