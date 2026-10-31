# Code quality checklist — frontend

Load for the frontend surface selected by [`SKILL.md`](../SKILL.md#setup). `CONTRIBUTING.md` §Naming (TS imports, focus ownership) and §Testing conventions (Vitest tiering, 100% coverage as a means) own the general rules; cross-stack items live in [`code-quality.md`](code-quality.md).

- [ ] Pure logic — validation, derivation, formatting — is an exported plain function unit-tested directly against its branch table; the component test drives rendering and wiring only, not that branch table again.
- [ ] A control that disables itself renders _why_, associated to it (`aria-describedby`), from the same predicate that drives the disable — never a silent disable, never a second copy of the rule.
- [ ] A UI invariant mirrors the backend contract it stands for rather than a re-guessed rule, and fails closed the way the backend does (reject, not silently repair).
- [ ] Tests reach the DOM through the accessibility tree (role + accessible name) and fire real event seams; none assert on test-ids, class names, or element structure.
- [ ] A branch the UI genuinely cannot reach (a guard behind a disabled control) uses the required coverage directive with its source-backed reason in review evidence under `DESIGN-PRINCIPLES.md` §8; coverage is otherwise earned by real user paths, never by staging a state the UI forbids.
- [ ] Derived values use `createMemo`/accessors and control flow uses `<Show>`/`<For>`/`<Index>` — `<Index>` for positional or primitive rows, `<For>` for keyed identity.
