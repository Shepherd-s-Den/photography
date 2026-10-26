# Code quality checklist — backend

Load only for a PR that changes `backend/**`. `CONTRIBUTING.md` §Scala composition and `DESIGN-PRINCIPLES.md` §1/§4/§5 own the general rules; the shared items live in [`code-quality.md`](code-quality.md).

## Model and boundary audit

- [ ] Use agreed domain terms. Model required values directly and derive facts from their owner.
- [ ] Make APIs clear for callers: use precise input and result types, and check constructors and copies for invalid combinations.
- [ ] Parse, normalize and validate external values in adapters; pass strong types into the core.
- [ ] Put each operation in the service that owns it. Name ports for the work callers need.
- [ ] Keep a comment only when clearer code cannot express its reason.

## Implementation

- [ ] Effectful application boundaries are expressed as Tagless Final algebras parameterized by `F[_]`; pure domain logic stays independent of runtime effects.
- [ ] Per-call parameters contain only that call's inputs; fixed business collaborators and settings are injected once at construction.
- [ ] Capabilities use `F` context bounds and typeclass accessors, not explicit method, `make` or constructor parameters.
- [ ] Concrete interpreters and effect runtimes are composed at the application boundary, not selected from inside domain code.
- [ ] In runtime composition, keep constructor/factory calls on one physical line; extract nested dependency construction into meaningfully named vals to fit the line limit.
- [ ] Business and adapter behavior lives in the owning private `Live*` implementation or behind a caller-defined, injected trait. Inventory every changed public or package-visible companion/helper-object callable and its actual callers: each must be a factory or a pure, stateless utility. Package visibility does not make an effectful helper an internal method or a substitutable collaborator; record the inventory before checking this item off.
- [ ] A shared codecs object contains pure bidirectional mappings only. Name at least two real consumers and the representation invariant whose divergence would be a bug; keep other codecs local to their owning implementation.
- [ ] Effectful components use a trait, companion-object factory, and `Live<TraitName>` implementation.
- [ ] Orchestration remains visible in service methods; effect sequencing is linear and errors remain explicit.
- [ ] Tests substitute algebras at service boundaries and exercise concrete adapters against their real external seam.
- [ ] Fixtures and stubs are local to the suite that owns their behavior; shared fixtures have a named, stable responsibility.
- [ ] Test IDs and values are deterministic. Use direct constructors for simple fixtures and builders for non-trivial fixture construction, applying the shallow-helper rule.
- [ ] Resource acquisition, use, and release follow the selected Typelevel effect library's resource-safe primitive.
- [ ] Low-information values such as `None` and `Option.empty` use named arguments; domain-meaningful values remain positional.
- [ ] Production dependencies are introduced only for a documented capability and kept out of pure domain modules.
- [ ] Destructure a tuple or product only when every field is required, including in pattern matches. Otherwise access the required fields directly; use named fields where available.
- [ ] Use named fields for partial product access and `case (fieldA, fieldB)` patterns when all tuple fields are required, including collection callbacks. Positional tuple accessors such as `._1` are not allowed.
- [ ] Each statement in a for-comprehension occupies one physical line. Extract multiline SQL and expressions into named vals or private defs before sequencing them.
- [ ] Use `.map` for a single generator with a pure result; reserve for-comprehensions for multiple dependent steps. Inventory every changed comprehension against this rule.
- [ ] Inventory comprehensions mixing `<-` and `=` against the fractal-design check. Keep effectful orchestration separate from lower-level transformations through meaningful owning operations, rather than wrapping pure values in effects. Retain a mix only with a concrete same-abstraction-level justification recorded in review evidence.
