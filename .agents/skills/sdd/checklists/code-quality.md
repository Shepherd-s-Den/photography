# Code quality checklist

Gate for every Implementation PR, both stacks. Load the stack leaf for each changed surface: [`frontend`](code-quality-frontend.md) or [`backend`](code-quality-backend.md). `CONTRIBUTING.md` and `DESIGN-PRINCIPLES.md` remain authoritative.

## Architecture gate

The local design map is a **reuse, evolution or separation decision gate**, completed before tests or implementation. Start from the existing end-to-end system, not proposed modules. Apply these steps in order to every emerging use case and to the combined change; record source evidence and rejected alternatives. Signatures follow an accepted ownership decision, not the other way around.

| Step | Evidence required | Blocking condition |
| --- | --- | --- |
| 1. Reconstruct current flows | Trace relevant triggers through authoritative state, acquisition, transformations, retained results, publication and concrete consumers, including failure/recovery and concurrent paths. Read the owning source and test precedents; name what is already computed and retained. | The map starts with new files/APIs, omits a relevant existing path, or proposes another read, normalization pass, cache or service without accounting for available results and their validity/lifetime. |
| 2. Test existing abstractions | Walk the emerging use case through the existing operations and state. Choose reuse when they suffice, evolution when their contract is insufficient, or separation when a genuinely different responsibility is established. Record the exact insufficiency or independent reason to change, with source evidence. | A new presentation, endpoint or projection is treated as a new responsibility; an existing owner is bypassed without trying its contract; or a newly created caller is the sole justification for its proposed API. |
| 3. Assign shared and independent ownership | Compare paths: when divergence would be a bug, identify the one owner of that knowledge/behavior and how both consume it. Keep independently changing decisions and resource rules local; code-shape similarity alone does not establish shared knowledge. | Existing acquisition/derivation/publication is duplicated, thin wrappers retain the wrong owner, or independently changing flows are forced through a common implementation. |
| 4. Establish contracts and facts | After ownership is decided, trace each input to supplied and consumed facts, cardinality, result consumers and enforcing owners. Identify effects, dependency lifetimes, resource/transaction guarantees and policy authority. Domain groups/ADTs represent cohesive facts or meaningful states; preserve typed identity, ordering and required constraints. | Optional fields/modes, aggregate bags, ADTs or generic frameworks conceal incompatible flows or dependency/parameter counts; a forbidden state remains representable; pure work is effect-wrapped; fixed dependencies are per-call inputs; or consumers repeat supplier validation/reconstruct its decisions. |
| 5. Stress the whole architecture | Compare the existing and resulting flow/dependency graphs together. Remove each addition hypothetically: identify the necessary decision, invariant or external boundary lost. Account for reads, retained results, shared algorithms, ownership and failure order across all affected paths. | Individually plausible modules collectively duplicate ownership, add pass-through layers or redundant reads, or require consumers to recover shared facts from rendered text. Several external calls or an API-shaped wrapper alone do not earn another layer. |
| 6. Close the decision gate | For every changed responsibility, record the existing owner/flow, attempted reuse, chosen reuse/evolution/separation and why alternatives fail, resulting ownership, test seam and acceptance evidence. Only then record necessary operations/inputs/results and project tests. Recheck decisions against actual code at self-check and review. | Any decision lacks evidence, the map is only a catalog of proposed signatures/files, a suite has no production boundary, or required evolution cannot remain buildable without an unresolved sequencing exception. Stop and resolve the gap before tests or implementation. |

An exception needs a concrete reason and maintainer authorization when it changes an agreed convention. A size justification cannot excuse mixed ownership, artificial effects or an unrelated result type.

An accepted evolution keeps the responsibility with its existing owner. A separation identifies a new decision, invariant or effect lifecycle that must evolve independently, not merely a different caller. Keep acquisition, decoding and snapshot assembly in the adapter already responsible for that boundary; keep single-owner pure mapping local.

### What counts as a seam

A seam test crosses the boundary between an adapter and the real external system or representation it translates: HTTP, a database engine, filesystem, process or configuration file. Name the adapter, concrete boundary, real resource and observable translation/failure proved. For a driving HTTP adapter, send real requests with its application service substituted; for a driven HTTP adapter, exercise the real remote service in an isolated test environment.

Calls between our own ports/services are internal collaboration, not an adapter seam. A class invoking injected ports has a component test, even when those ports have HTTP implementations elsewhere. The adapter under test must itself cross the named boundary; starting a real service beside an unrelated orchestrator does not establish its seam.

## Whole-diff self-check

Record `pass`, `fail` or `not applicable` for every item, with `file:symbol` evidence or the reason it does not apply. Inspect every changed production declaration and suite, not just lines mentioned by the maintainer. A correction invalidates affected answers and reviews; see [`reviewer.md`](../reviewer.md).

- [ ] For each module this PR changed, its suite was re-derived as a whole from the module's current observable behavior — the suite _is_ that projection. Existing tests were extended, merged, retargeted, or deleted to match; a net-new test exists only where a genuinely new observable behavior has no existing test that should own it.
- [ ] No two tests differ only in which parameters they fill — that is one table test.
- [ ] The architecture decision gate above passes against the complete resulting change, including existing flows and retained results, rather than only each new module. Every reuse/evolution/separation is source-backed; each grown unit is measured against `DESIGN-PRINCIPLES.md` §7. Larger cohesive units have responsibility-based justification, not coverage-based excuses or artificial splits.
- [ ] Re-derive fractal design for each changed method, block, and chain: sibling statements have the same abstraction level, and called APIs return what their callers need rather than anemic data requiring lower-level reconstruction. Extract a meaningful responsibility or improve the owning API when levels differ.
- [ ] Tests are Arrange / Act / Assert, each block separated by a blank line.
- [ ] Assertions are one physical line in the normal case; extract named `expectedX`/`actualX` values (decomposing nested expressions into values as needed) to make that possible.
- [ ] Test data is declared in semantic scenario order as local values; setup follows the actual reference suite's builder pattern rather than introducing a fixture framework.
- [ ] Values are named for the meaning they establish, not the helper call that produced them (`orderedChapters`, not `processed`).
- [ ] Test names interpolate the actual domain value under test rather than spelling it out, so a rename can't silently make the name wrong.
- [ ] Public/private APIs, warning messages and tests use existing domain vocabulary, checked against current contracts and any glossary. Test names identify the behavior guaranteed by that boundary; new synonyms require an actual domain distinction.
- [ ] The complete diff obeys [code-comment ownership](../../../../DESIGN-PRINCIPLES.md#8-code-expresses-intent); required tooling directives are distinguished from authored explanation.
