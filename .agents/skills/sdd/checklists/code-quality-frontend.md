# Code quality checklist — frontend

Load only for a PR that changes `frontend/**`. `CONTRIBUTING.md` §Naming and §Testing conventions own the general rules, and the shared items live in [`code-quality.md`](code-quality.md); the items below live only here.

- [ ] Pure logic — validation, derivation, formatting — is an exported plain function unit-tested directly against its branch table; the component test drives rendering and wiring only, not that branch table again.
- [ ] A control that disables itself renders _why_, associated to it (`aria-describedby`), from the same predicate that drives the disable — never a silent disable, never a second copy of the rule.
- [ ] A UI invariant mirrors the backend contract it stands for rather than a re-guessed rule, and fails closed the way the backend does (reject, not silently repair).
- [ ] Tests reach the DOM through the accessibility tree (role + accessible name) and fire real event seams; none assert on test-ids, class names, or element structure.
- [ ] Tests exercise states reachable through the user interface; they do not bypass disabled controls merely to manufacture an impossible state.
- [ ] SolidJS components keep reactive reads in tracked expressions and render identity-keyed collections with stable item identity.
- [ ] `src/app` composes the application, each `src/features` module owns one user workflow, and `src/shared` stays independent of app and feature modules; dependency-cruiser passes without cycles.
- [ ] Frontend tests meet the configured statement, branch, function, and line coverage thresholds.
