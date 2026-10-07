# Spec quality checklist

Gate for the Spec PR. First verify against `CONTRIBUTING.md` §Documentation style — it owns prose/terseness rules; do not restate them here. Then fill from the spec and [`templates/change-spec.md`](../templates/change-spec.md) — never from memory of a past spec. Read the whole spec once, straight through, before checking anything below.

- [ ] Classification (`feature`/`investigation`) is recorded and the branch name matches it.
- [ ] Sections have distinct purposes. Avoid restating facts, but keep useful context where readers need it.
- [ ] `What & Why` and `Acceptance Criteria` are non-empty. `Doc Sync` is present and lists necessary changes or says `None`.
- [ ] Stays at behavior/contract altitude: avoid code structure and diff walkthroughs. Name a tool, path, or storage mechanism when it is part of the agreed behavior or points to an authoritative artifact.
- [ ] `Domain / Design Notes` covers changed domain, persisted-data, or boundary contracts.
- [ ] The active model and living docs are authoritative. Treat archived proposals as history unless a living source designates them as current.
- [ ] `Grounded in` names concrete spike findings or a specific reason exploration was unnecessary.
- [ ] Draft structure first (diagrams/tables for relevant flows and contracts); add only prose that clarifies it.
- [ ] Prose paragraphs and list items are at most two rendered lines; violations block review.
- [ ] Acceptance criteria are short, observable, and testable. Keep each item to one behavior and include relevant failure and boundary cases.
- [ ] Persisted-data specs separate database-enforced constraints from use-case rules and say how each database constraint is enforced.
- [ ] Each Doc Sync entry names one owning document, section, and changed fact; omit docs whose owned knowledge is unchanged.
- [ ] `Out of Scope` entries answer a likely reader question, add no roadmap inventory, and repeat no acceptance criterion; omit the section when none qualify.
- [ ] `Invariants` holds only pre-existing guarantees. `Tradeoffs Accepted` names a real cost and who bears it. Omit either when empty.
- [ ] Proportional to the closest approved precedent spec: if this change is smaller than that precedent but the proposal is the same size or larger, cut it.
