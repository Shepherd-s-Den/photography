# Execution

## Classify

- Record feature or investigation, approved scope, baseline and affected owners in ignored repository-root `.agent-work/<slug>/state.md`; use the matching branch prefix.
- Before each phase and after interruption, read work state and resolve earlier open gates.
- Draft the durable knowledge delta using [context policy](context.md). A nonempty delta requires a reviewed spec; otherwise record preserved behavior and engineering rules as the review basis and skip Spec/Archive PRs. Only the maintainer may waive a required spec.
- Reclassify when scope changes. Investigations establish the root cause from evidence before selecting a repair.
- Investigations record each tested hypothesis and the evidence accepting or rejecting it.
- Record phase timing separately for exploration, implementation, automated checks, review/repairs and approval waiting; this is evidence, not a diary.

## Precedent and optional experiment

- Read the closest complete implementation, concrete caller and owning suite for each distinct seam. Record what stays the same and the precise contract gap.
- Reuse sufficient contracts; evolve their existing owner when insufficient; separate only an independently changing responsibility. A new endpoint or caller alone establishes neither necessity nor ownership.
- Expand investigation only to answer a named question affecting behavior, ownership or feasibility. Settled decisions remain settled unless new evidence contradicts them.
- Default to no spike. Existing UX is inspected or demonstrated in the current application.
- For material uncertainty, propose question, smallest experiment, evidence and budget; obtain approval before execution.
- An approved disposable experiment defaults to Haiku 5.5, targets 10 minutes and stops at 15 minutes of active work. Continuing, model substitution or escalation needs approval; if that model is unavailable, report it.
- Validate the experiment once against its question using relevant product checks; coverage may be skipped.
- Keep experiments isolated and uncommitted; retain them only for an identified comparison, then stop owned services and remove only their resolved worktree.

## Spec

- Use [the template](templates/change-spec.md), current living sources and confirmed evidence. Describe behavior and contracts, not signatures or code layout.
- Project normal, failure and boundary outcomes; distinguish database constraints from application decisions. Include only useful diagrams or unique prose.
- Review with the spec template before publication; implementation follows its authorized merge.

## Design approval

- Before tests or production changes, present [the delta map](templates/design-map.md) in chat for explicit maintainer approval.
- Account for every changed domain model, use case and driving/driven port, including existing APIs; signatures follow source-backed ownership decisions.
- Bypass only when all three layers are unchanged, with source evidence. Spike, spec and previous slice approvals do not substitute.
- When later evidence changes an approved owner or contract, reopen the affected map before implementation; approved spec amendments also require authorization.
- After an authorized spec amendment, update its criteria before their tests or implementation.
- Include that amendment in the current slice's independent review.

## Implement and test

- Adapt the precedent within the approved current slice.
- For new behavior, batch its tests and confirm they fail for the intended missing behavior before the production change.
- Finish a coherent code-and-test change before running its targeted validation; do not compile or test after each edited file.
- Other early checks require a named compiler/type uncertainty.
- Rerun checks after a meaningful repair, not after unrelated edits; publication still requires all applicable gates.
- Map acceptance criteria and enumerated edges to owning tests/slices; deferred guarantees retain explicit owners. Adapt existing scenarios unless their observable contract changes.
- Core tests substitute ports; adapters cross their real HTTP/database/filesystem/process/configuration seam; frontend exercises delivered boundaries and accessibility.
- Follow [conventions](conventions.md). Run the smallest existing checks covering the change, then required publication gates; preserve compiler, formatter, lint and coverage enforcement.
- Perform one template-guided self-check, then [independent review](reviewer.md). A correction reopens affected decisions and consequences, not unrelated settled work.
- Demonstrate the delivered layer with isolated state and obtain maintainer confirmation before its PR; core/adapters do not require an unfinished UI.

## Complete

- After all implementation merges, verify the integrated local journey against the approved review basis and obtain confirmation; resolve discrepancies.
- For spec-backed work, integrate every Doc Sync delta at its owning section, prune superseded content and archive under `specs/changes/archive/YYYY-MM-DD-<slug>/`.
- The archive introduces no behavior; apply the archive/context review to the complete merged change and resulting documents.
- Persist the candidate, approvals, test mapping, command results, findings and reviewer channel in state; resume from that state after interruption.
