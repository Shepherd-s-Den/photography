# Spec quality checklist

Gate for the Spec PR. First verify against `CONTRIBUTING.md` §Documentation style — it owns prose/terseness rules; do not restate them here. Then fill from the spec and [`templates/change-spec.md`](../templates/change-spec.md) — never from memory of a past spec. Read the whole spec once, straight through, before checking anything below.

- [ ] Classification (`feature`/`investigation`) is recorded and the branch name matches it.
- [ ] Sections have distinct purposes. Avoid restating facts, but keep useful context where readers need it.
- [ ] `What & Why`, `Acceptance Criteria`, and `Doc Sync` are present and non-empty; every other section is present only because it carries real content, not filler.
- [ ] Stays at behavior/contract altitude: avoid code structure and diff walkthroughs. Name a tool, path, or storage mechanism when it is part of the agreed behavior or points to an authoritative artifact.
- [ ] `Domain / Design Notes` covers changed domain, persisted-data, or boundary contracts.
- [ ] The active model and living docs are authoritative. Treat archived proposals as history unless a living source designates them as current.
- [ ] `Grounded in` names concrete spike findings or a specific reason exploration was unnecessary.
- [ ] Acceptance criteria are short, observable, and testable. Keep each item to one behavior and include relevant failure and boundary cases.
- [ ] Persisted-data specs separate database-enforced constraints from use-case rules and say how each database constraint is enforced.
- [ ] `Doc Sync` lists only necessary living-doc changes, names the section and fact, and points to authoritative artifacts instead of copying their contents.
- [ ] `Invariants` holds only pre-existing guarantees, never this change's own rules. `Tradeoffs Accepted` holds only a real downside with who bears it. `Out of Scope` holds only what a reader would reasonably wonder about, not a restatement of the criteria's own exclusions. Any of the three is omitted rather than left empty.
- [ ] Proportional to the closest approved precedent spec: if this change is smaller than that precedent but the proposal is the same size or larger, cut it.
