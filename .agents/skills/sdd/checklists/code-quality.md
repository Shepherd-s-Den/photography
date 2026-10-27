# Code quality checklist

Gate for every Implementation PR, both stacks. Load the stack leaf for each changed surface: [`frontend`](code-quality-frontend.md) or [`backend`](code-quality-backend.md). `CONTRIBUTING.md` and `DESIGN-PRINCIPLES.md` remain authoritative.

## Architecture gate

Before tests or implementation, answer these questions in the local design map required by [`executor.md`](../executor.md). Identify the consuming boundary and proposed call for new operations; at self-check and review replace proposals with actual symbols and consumption. Include the complete diff and untracked files; “follows the pattern,” passing tests and coverage are not evidence of architectural fitness.

| Question | Evidence required | Blocking condition |
| --- | --- | --- |
| What responsibility does each module own? | Start from existing callers and ports; map operations to their owners before adding modules. Name independently changeable rules and read source/test precedents in full. For a derived fact, identify every consumer and its supplying owner. | A module combines independent responsibilities, adds no owned contract/invariant/failure translation, introduces an unverified pattern, or makes consumers recover a shared fact from rendered text. |
| Does the API express that responsibility? | Actual calls and construction for public/private APIs; for each input, name consumed facts and enabled decisions, result meaning and its consumer. Group cohesive invocation facts in an operation-owned request type when useful; inspect calls longer than two lines for missing hierarchy. | An API accepts unrelated aggregate fields, loses evidence needed for its decisions, flattens conceptual levels, hides a parameter bag, or returns a caller's envelope rather than its own result. |
| Who owns each dependency and input? | Dependency owner and lifetime; distinguish construction inputs from invocation data. Trace original evidence through projections to its interpreting operation. | A fixed collaborator/configuration is threaded through operations, a caller preclassifies a collaborator's uncertainty or business decisions, or reconstructs its result. |
| What associations does each collection express? | Preserve source identity with existing domain-typed keys and required nonempty cardinality. Trace empty/unavailable entries, duplicate handling and precedence through projections; exercise order-sensitive outcomes. | Nested/parallel lists encode associations only by position or parameter name, projections discard required identity, or a collection change silently alters uniqueness or first-value precedence. |
| Where can work fail or cause effects? | Each effectful operation's concrete reason; each pure operation's direct result. | Pure computation is effect-wrapped, or I/O is hidden behind a supposedly pure contract. |
| Which states are invalid, and who excludes them? | Illegal combinations, types excluding them, and the supplying boundary for cross-value guarantees. | A forbidden combination remains representable despite an available stronger type, an invariant has no enforcing owner, or a consumer repeats its port's validation. |
| Which values are policy rather than algorithms? | Authority, reader and update path for configurable business data. | Editable policy is embedded in implementation constants or silently replaced with a default. |
| What module does each suite exercise? | Existing production module, public operation, scenario, substituted collaborators and actual boundary crossed; classify it using the seam definition below. | A suite has no production module boundary, repeats a collaborator's behavior through its caller, or labels internal port collaboration an external seam. |

An exception needs a concrete reason and maintainer authorization when it changes an agreed convention. A size justification cannot excuse mixed ownership, artificial effects or an unrelated result type.

Challenge each extraction in both directions: which concrete change requires callers to evolve together, and which change must remain local? Shared code owns knowledge whose divergence would be a bug. Similar endpoints, pagination, result shapes or algorithms across independently evolving resources are incidental similarity; keep their implementations local. Thin wrappers do not establish ownership while a common implementation still owns their resource rules.

Challenge each added layer against its consumer: what required decision or contract disappears if it is removed? Keep endpoint fetching, decoding and snapshot assembly in the adapter implementing the consumer's port; several remote calls alone do not justify another client or orchestrator. A required application operation coordinating owned ports is a use case, tested with substituted collaborators. Keep single-owner pure mapping private. A broad label such as “metadata preparation” or a shorter method is not a decomposition argument.

### What counts as a seam

A seam test crosses the boundary between an adapter and the real external system or representation it translates: HTTP, a database engine, filesystem, process or configuration file. Name the adapter, concrete boundary, real resource and observable translation/failure proved. For a driving HTTP adapter, send real requests with its application service substituted; for a driven HTTP adapter, exercise the real remote service in an isolated test environment.

Calls between our own ports/services are internal collaboration, not an adapter seam. A class invoking injected ports has a component test, even when those ports have HTTP implementations elsewhere. The adapter under test must itself cross the named boundary; starting a real service beside an unrelated orchestrator does not establish its seam.

## Whole-diff self-check

Record `pass`, `fail` or `not applicable` for every item, with `file:symbol` evidence or the reason it does not apply. Inspect every changed production declaration and suite, not just lines mentioned by the maintainer. A correction invalidates affected answers and reviews; see [`reviewer.md`](../reviewer.md).

- [ ] For each module this PR changed, its suite was re-derived as a whole from the module's current observable behavior — the suite _is_ that projection. Existing tests were extended, merged, retargeted, or deleted to match; a net-new test exists only where a genuinely new observable behavior has no existing test that should own it.
- [ ] No two tests differ only in which parameters they fill — that is one table test.
- [ ] Each unit this PR grew was measured against `DESIGN-PRINCIPLES.md` §7's size signal. Split independently required responsibilities, not merely longer methods or repeated shapes. Added modules pass the ownership, sharing and layer-removal challenges above; required orchestrators sequence owned modules rather than absorbing their algorithms or policy tables. A larger cohesive unit has a responsibility-based justification, not a coverage-based one.
- [ ] Re-derive fractal design for each changed method, block, and chain: sibling statements have the same abstraction level, and called APIs return what their callers need rather than anemic data requiring lower-level reconstruction. Extract a meaningful responsibility or improve the owning API when levels differ.
- [ ] Tests are Arrange / Act / Assert, each block separated by a blank line.
- [ ] Assertions are one physical line in the normal case; extract named `expectedX`/`actualX` values (decomposing nested expressions into values as needed) to make that possible.
- [ ] Test data is declared in semantic scenario order as local values; setup follows the actual reference suite's builder pattern rather than introducing a fixture framework.
- [ ] Values are named for the meaning they establish, not the helper call that produced them (`orderedChapters`, not `processed`).
- [ ] Test names interpolate the actual domain value under test rather than spelling it out, so a rename can't silently make the name wrong.
- [ ] Public/private APIs, warning messages and tests use existing domain vocabulary, checked against current contracts and any glossary. Test names identify the behavior guaranteed by that boundary; new synonyms require an actual domain distinction.
