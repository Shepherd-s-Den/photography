# Spec quality checklist

Gate for the Spec PR. First verify against `CONTRIBUTING.md` §Documentation style — it owns prose/terseness rules; do not restate them here. Then fill from the spec and [`templates/change-spec.md`](../templates/change-spec.md) — never from memory of a past spec. Read the whole spec once, straight through, before checking anything below.

- [ ] Classification (`feature`/`investigation`) is recorded and the branch name matches it.
- [ ] Sections have distinct purposes. Avoid restating facts, but keep useful context where readers need it.
- [ ] `What & Why` and `Acceptance Criteria` are non-empty. `Doc Sync` is present and lists necessary changes or says `None`.
- [ ] Stays at behavior/contract altitude: describe intent and observable guarantees. Keep signatures, code structure, file layouts and diff walkthroughs in implementation evidence; paths belong here only as pointers to authoritative artifacts.
- [ ] `Domain / Design Notes`, when needed, covers changed domain, persisted-data and boundary guarantees. Trace triggers through committed state, normal/failed outcomes and UI delivery; verify database guarantees against actual sources.
- [ ] The active model and living docs are authoritative. Treat archived proposals as history unless a living source designates them as current.
- [ ] `Grounded in` names concrete spike findings or a specific reason exploration was unnecessary.
- [ ] Draft structure first; each diagram/table resolves a flow or contract question, and prose clarifies remaining guarantees.
- [ ] Run `python3 .agents/skills/sdd/scripts/check_markdown_wrapping.py` on the proposal and every changed Markdown file; any source-wrapping failure blocks review.
- [ ] Prose paragraphs and list items are at most two rendered lines; inspect the rendered proposal and block longer items.
- [ ] Acceptance criteria are short, observable, and testable. Keep each item to one behavior and include relevant failure and boundary cases.
- [ ] Persisted-data specs separate database-enforced constraints from use-case rules and say how each database constraint is enforced.
- [ ] Each Doc Sync entry names one owning document, section and durable knowledge delta at that document's altitude, following the [living-doc quality policy](../../../../specs/templates/README.md). Precise details point to their authoritative artifact; omit docs whose owned knowledge is unchanged.
- [ ] `Out of Scope` entries answer a likely reader question, add no roadmap inventory, and repeat no acceptance criterion; omit the section when none qualify.
- [ ] `Invariants` holds only pre-existing guarantees. `Tradeoffs Accepted` names a real cost and who bears it. Omit either when empty.
- [ ] Proportional to the closest approved precedent spec: if this change is smaller than that precedent but the proposal is the same size or larger, cut it.
