# Code quality checklist

Gate for every Implementation PR, both stacks. Load the stack leaf for each changed surface: [`frontend`](code-quality-frontend.md) or [`backend`](code-quality-backend.md). `CONTRIBUTING.md` and `DESIGN-PRINCIPLES.md` remain authoritative.

## Architecture gate

Before tests or implementation, answer these questions in the local design map required by [`executor.md`](../executor.md). Identify the consuming boundary and proposed call for new operations; at self-check and review replace proposals with actual symbols and consumption. Include the complete diff and untracked files; “follows the pattern,” passing tests and coverage are not evidence of architectural fitness.

| Question | Evidence required | Blocking condition |
| --- | --- | --- |
| What responsibility does each module own? | Caller, independently changeable rules and corresponding source/test precedent read in full. | A module combines independent responsibilities, adds no owned contract/invariant/failure translation, or introduces an unverified pattern. |
| Does the API express that responsibility? | Caller inputs, result meaning and where the result is consumed. | A reusable operation returns a caller's presentation or workflow envelope rather than its own result. |
| Who owns each dependency and input? | Dependency owner and lifetime; distinguish construction inputs from invocation data. | A fixed collaborator/configuration is threaded through operations, or a caller reconstructs a collaborator's result. |
| Where can work fail or cause effects? | Each effectful operation's concrete reason; each pure operation's direct result. | Pure computation is effect-wrapped, or I/O is hidden behind a supposedly pure contract. |
| Which states are invalid, and who excludes them? | Illegal combinations, types excluding them, and the supplying boundary for cross-value guarantees. | A forbidden combination remains representable despite an available stronger type, an invariant has no enforcing owner, or a consumer repeats its port's validation. |
| Which values are policy rather than algorithms? | Authority, reader and update path for configurable business data. | Editable policy is embedded in implementation constants or silently replaced with a default. |
| What module does each suite exercise? | Existing production module, public operation, scenario and substituted collaborators. | A suite has no production module boundary or repeats a collaborator's behavior through its caller. |

An exception needs a concrete reason and maintainer authorization when it changes an agreed convention. A size justification cannot excuse mixed ownership, artificial effects or an unrelated result type.

Challenge the proposed boundary with a concrete change: which algorithm, policy, effect or consumer could change independently? Show the separate owner, or demonstrate why separation would invent a responsibility. A broad label such as “metadata preparation” is not a decomposition argument.

## Whole-diff self-check

Record `pass`, `fail` or `not applicable` for every item, with `file:symbol` evidence or the reason it does not apply. Inspect every changed production declaration and suite, not just lines mentioned by the maintainer. A correction invalidates affected answers and reviews; see [`reviewer.md`](../reviewer.md).

- [ ] For each module this PR changed, its suite was re-derived as a whole from the module's current observable behavior — the suite _is_ that projection. Existing tests were extended, merged, retargeted, or deleted to match; a net-new test exists only where a genuinely new observable behavior has no existing test that should own it.
- [ ] No two tests differ only in which parameters they fill — that is one table test.
- [ ] Each unit this PR grew was measured against `DESIGN-PRINCIPLES.md` §7's size signal. Distinct responsibilities have their own injected modules; an orchestrator sequences those modules rather than absorbing their algorithms or policy tables. A larger cohesive unit has a responsibility-based justification, not a coverage-based one.
- [ ] Re-derive fractal design for each changed method, block, and chain: sibling statements have the same abstraction level, and called APIs return what their callers need rather than anemic data requiring lower-level reconstruction. Extract a meaningful responsibility or improve the owning API when levels differ.
- [ ] Tests are Arrange / Act / Assert, each block separated by a blank line.
- [ ] Assertions are one physical line in the normal case; extract named `expectedX`/`actualX` values (decomposing nested expressions into values as needed) to make that possible.
- [ ] Test data is declared in semantic scenario order as local values; setup follows the actual reference suite's builder pattern rather than introducing a fixture framework.
- [ ] Values are named for the meaning they establish, not the helper call that produced them (`orderedChapters`, not `processed`).
- [ ] Test names interpolate the actual domain value under test rather than spelling it out, so a rename can't silently make the name wrong.
- [ ] Test names use the vocabulary exposed by the tested boundary and identify the behavior they guarantee.
