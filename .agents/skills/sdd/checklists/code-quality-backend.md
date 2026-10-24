# Code quality checklist — backend

Load only for a PR that changes `backend/**`. `CONTRIBUTING.md` §Scala composition and `DESIGN-PRINCIPLES.md` §1/§4/§5 own the general rules; the shared items live in [`code-quality.md`](code-quality.md).

- [ ] Effectful application boundaries are expressed as Tagless Final algebras parameterized by `F[_]`; pure domain logic stays independent of runtime effects.
- [ ] Concrete interpreters and effect runtimes are composed at the application boundary, not selected from inside domain code.
- [ ] Business and adapter behavior lives in the owning private `Live*` implementation or behind a caller-defined, injected trait. Inventory every changed public or package-visible companion/helper-object callable and its actual callers: each must be a factory or a pure, stateless utility. Package visibility does not make an effectful helper an internal method or a substitutable collaborator; record the inventory before checking this item off.
- [ ] A shared codecs object contains pure bidirectional mappings only. Name at least two real consumers and the representation invariant whose divergence would be a bug; keep other codecs local to their owning implementation.
- [ ] Effectful components use a trait, companion-object factory, and `Live<TraitName>` implementation.
- [ ] Orchestration remains visible in service methods; effect sequencing is linear and errors remain explicit.
- [ ] Tests substitute algebras at service boundaries and exercise concrete adapters against their real external seam.
- [ ] Fixtures and stubs are local to the suite that owns their behavior; shared fixtures have a named, stable responsibility.
- [ ] Resource acquisition, use, and release follow the selected Typelevel effect library's resource-safe primitive.
- [ ] Low-information values such as `None` and `Option.empty` use named arguments; domain-meaningful values remain positional.
- [ ] Production dependencies are introduced only for a documented capability and kept out of pure domain modules.
- [ ] Destructure a tuple or product only when every field is required, including in pattern matches. Otherwise access the required fields directly; use named fields where available.
- [ ] Use named fields for partial product access and `case (fieldA, fieldB)` patterns when all tuple fields are required, including collection callbacks. Positional tuple accessors such as `._1` are not allowed.
- [ ] Each statement in a for-comprehension occupies one physical line. Extract multiline SQL and expressions into named vals or private defs before sequencing them.
- [ ] Inventory comprehensions mixing `<-` and `=` against the fractal-design check. Keep effectful orchestration separate from lower-level transformations through meaningful owning operations, rather than wrapping pure values in effects. Retain a mix only with a concrete same-abstraction-level justification recorded in review evidence.
