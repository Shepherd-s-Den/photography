# Design principles

## Ownership

- Domain values own strong types, refinements and intrinsic pure facts.
- Domain values carry no capabilities.
- Use types that exclude invalid states.
- Application operations own business decisions, aggregate construction, normalization and lifecycle.
- Application services own orchestration.
- Companions own factories and operation results.
- Ports name the work their caller needs, in domain terms.
- Adapters implement real external boundaries.
- Only composition binds concrete interpreters.
- The supplying adapter parses and validates external data and assembles source inventories.
- Consumers trust their port's domain-valid guarantees instead of validating them again.
- Persistence owns representation validity, relational constraints and atomic writes of supplied decisions.
- Application decisions need coordination across the full read/decision/write sequence.
- A transaction proves atomicity, not the safety of an earlier business decision.
- An event recorded after it happened takes its timestamp from the caller, not the server clock.
- The boundary converts local entry to an absolute instant, validates it and preserves it.
- Code, contracts and UI share domain vocabulary.
- New terms require a real distinction and maintainer agreement.

## Policy placement

- Inventory changed ordering instances, sorting, precedence, deduplication winners, retained spelling, grouping and display projections as behavior.
- Ask whether the rule remains true when its screen, endpoint or consuming use case changes.
- Purity alone does not establish domain ownership.
- Domain ordering requires an intrinsic invariant, such as chronology or book structure.
- Use-case selection and normalization belong to the operation supplying them.
- Source contract order belongs to its adapter.
- Visual order, labels and formatting belong to presentation.
- Test each rule at its owner.
- Global ordering instances that choose winners must name their intrinsic invariant.

## Continuity and seams

- Account for existing reads, transformations, retained results and consumers before adding another acquisition path.
- Check the validity, failure behavior and lifetime of retained results.
- Reuse sufficient contracts; evolve insufficient contracts at their existing owner.
- Separate a responsibility only when its decisions change independently.
- A newly invented caller proves consumption, not necessity.
- Share knowledge whose divergence would be a bug.
- Keep independently changing resource rules local, even when their code looks similar.
- An extraction must own a required decision, invariant or external boundary.
- For each new method or argument, identify the required decision or guarantee lost if it is removed.
- Thin wrappers and several remote calls alone do not justify another layer.
- Acquisition, decoding and snapshot assembly stay with the adapter owning that port.
- A module coordinating our ports is a use case, not an external adapter.
- A seam test crosses the adapter's real external system or representation.
- Driving HTTP seam tests send real requests and substitute the application service.
- Driven HTTP seam tests use the real remote service with isolated test state.
- Keep mutable authoritative records separate from cached projections.
- Projections preserve identity and required evidence.
- Failed reads and identity mismatches remain explicit failures.

## Contracts and effects

- Signatures accept only facts consumed by their operation.
- Justify batch size and scope separately from entity identity.
- Accept needed facts, not another operation's result envelope.
- Grouped inputs represent cohesive facts; sum types represent distinct valid states.
- Groups must not hide unrelated parameters or dependencies.
- APIs exceeding five fields or parameters require a cohesion/state-model check before hoisting calls.
- Inject fixed collaborators and settings once at construction.
- Invocation APIs accept only data that can change for that call.
- Composition parses static configuration into the types its consumers need.
- A class has at most five injected business dependencies, excluding generic capabilities.
- Split responsibilities instead of hiding excess dependencies in bundles.
- Pure computation returns plain values.
- Effects require I/O, resource, concurrency or capability work.
- Lifting one workflow branch does not justify an otherwise pure API.
- Business collaborators use traits; mechanical pure utilities may be concrete.
- Adapters use application contracts, not peer adapters or bypassed infrastructure.
- Collections preserve typed identity, required cardinality and associations.
- Preserve duplicate handling and meaningful precedence through projections.
- Use keyed associations instead of parallel positional lists.
- Configurable business data has an authority and an explicit read/update path.
- Constants or relocated resource files do not establish configurability.
- Repository-backed business data uses its caller-owned repository contract; static settings do not replace that lifecycle.
- State-dependent writes check and commit against the same current state.
- Prove concurrency, cancellation and resource guarantees at their owning boundaries.
- Canonical encodings preserve semantics used outside the core, including stored ordering across retained data.

## Complexity and expression

- Each unit owns one responsibility.
- Sibling statements use one abstraction level.
- Keep orchestration visible and linear.
- Independently changing algorithms retain their own owners.
- Keep a single owner's pure mapping private.
- Order declarations and parameters most important first.
- Inspect methods beyond roughly 7-10 lines, units beyond a screenful, and suites beyond roughly three times production size.
- Size prompts inspection; split only distinct responsibilities.
- Extract a newly exposed responsibility in the change that exposes it.
- Never trim tests to hide the size ratio.
- Prefer the simplest design that satisfies the agreed behavior.
- Reduce design complexity before adding scope; narrowing approved scope requires maintainer agreement.
- Compatibility requires deployed versions or retained data to preserve.
- Errors and uncertainty remain explicit.
- Preserve the primary failure when compensation also fails.
- Model success, expected short-circuit and failure as distinct states when all three occur.
- Avoid unsafe casts, partial operations and success-shaped fallbacks.
- Names and structure carry code intent; rationale belongs in owning docs.
- Add no explanatory code comments, including documentation comments.
- Required tooling directives are allowed; record their justification in review evidence.
- Production dependencies require a demonstrated capability and pinned versions.
- Encode important dependency directions in architecture checks.
- Add and document a gate alongside the code or dependency it governs.
- Compiler gates reject unsafe constructs, unused code and accidental widening.
- Include new dependencies in the repository's existing update automation.
- Coverage proves behavior; duplicated tests do not improve that proof.
- UI controls own their accessible state and focus continuity.
- Disabled controls explain why they are unavailable.
- Layout handles content constraints and narrow widths, not just one viewport.
