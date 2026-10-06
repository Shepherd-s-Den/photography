# Code quality checklist — backend

Load only for a PR that changes `backend/**`. `CONTRIBUTING.md` §Scala composition and `DESIGN-PRINCIPLES.md` §1/§4/§5 own the general rules; the shared items live in [`code-quality.md`](code-quality.md).

- [ ] Effectful application boundaries are expressed as Tagless Final algebras parameterized by `F[_]`; pure domain logic stays independent of runtime effects.
- [ ] Concrete interpreters and effect runtimes are composed at the application boundary, not selected from inside domain code.
- [ ] Effectful components use a trait, companion-object factory, and `Live<TraitName>` implementation.
- [ ] Orchestration remains visible in service methods; effect sequencing is linear and errors remain explicit.
- [ ] Tests substitute algebras at service boundaries and exercise concrete adapters against their real external seam.
- [ ] Fixtures and stubs are local to the suite that owns their behavior; shared fixtures have a named, stable responsibility.
- [ ] Resource acquisition, use, and release follow the selected Typelevel effect library's resource-safe primitive.
- [ ] Low-information values such as `None` and `Option.empty` use named arguments; domain-meaningful values remain positional.
- [ ] Production dependencies are introduced only for a documented capability and kept out of pure domain modules.
