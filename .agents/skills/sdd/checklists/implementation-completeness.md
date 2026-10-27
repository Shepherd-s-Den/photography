# Implementation completeness checklist

Gate for every Implementation PR (against that PR's own slice) and, at full scope, for the Archive PR (against the complete merged change). This checklist asks "did we actually ship what the spec said," not "is the code well-written" — that's `code-quality.md`.

- [ ] Every acceptance criterion applicable to this slice has a passing test tracing to it by name or scenario. Criteria owned by later slices have explicit owners and remain open; the Archive PR checks them all against the complete merged change.
- [ ] Every domain/boundary contract in this slice has an owning implementation and passing scenario. Preserve externally contractual names; internal structure follows caller needs and the quality gates. A changed approved contract requires a maintainer-authorized spec amendment, not an implementation shaped around a mistaken design noun.
- [ ] The governing spec, including maintainer-authorized amendments under `SKILL.md`'s change discipline, matches this PR's shipped slice. Any changed scope is reflected in the spec and reviewed with the implementation.
- [ ] During implementation, deferred `Doc Sync` entries still describe the intended final facts; living-document updates and archival remain in their owning phase.
- [ ] For the Archive PR, every `Doc Sync` entry was applied to its exact document/section and the archived proposal matches the complete shipped behavior. Compare actual content rather than the author's summary.
