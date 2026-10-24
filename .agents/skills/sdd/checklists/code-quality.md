# Code quality checklist — shared

Gate for every Implementation PR, both stacks. Then load the stack checklist for each surface this PR changes — [`code-quality-frontend.md`](code-quality-frontend.md) for `frontend/**`, [`code-quality-backend.md`](code-quality-backend.md) for `backend/**` — and no others: a frontend-only PR does not read the backend checklist, nor the reverse. `CONTRIBUTING.md` §Testing conventions and `DESIGN-PRINCIPLES.md` §2/§3/§6/§7 own the general rules; the items below live only here.

- [ ] For each module this PR changed, its suite was re-derived as a whole from the module's current observable behavior — the suite _is_ that projection. Existing tests were extended, merged, retargeted, or deleted to match; a net-new test exists only where a genuinely new observable behavior has no existing test that should own it.
- [ ] No two tests differ only in which parameters they fill — that is one table test.
- [ ] Each unit this PR grew was measured against `DESIGN-PRINCIPLES.md` §7's size signal (a unit past a screenful, a suite past ~3× the code it exercises); anything past it was split or justified, not silently grown.
- [ ] Re-derive fractal design for each changed method, block, and chain: sibling statements have the same abstraction level, and called APIs return what their callers need rather than anemic data requiring lower-level reconstruction. Extract a meaningful responsibility or improve the owning API when levels differ.
- [ ] Tests are Arrange / Act / Assert, each block separated by a blank line.
- [ ] Assertions are one physical line in the normal case; extract named `expectedX`/`actualX` values (decomposing nested expressions into values as needed) to make that possible.
- [ ] Fixtures are declared in semantic scenario order, grouped by the state or use case they describe, not by type.
- [ ] Values are named for the meaning they establish, not the helper call that produced them (`wateredPlantOperations`, not `watered`).
- [ ] Test names use the vocabulary exposed by the tested boundary and identify the behavior they guarantee.
