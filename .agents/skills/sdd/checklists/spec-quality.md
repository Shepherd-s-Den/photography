# Spec quality checklist

Gate for the Spec PR. First verify against `CONTRIBUTING.md` §Documentation style — it owns prose/terseness rules; do not restate them here. Then fill from the spec and [`templates/change-spec.md`](../templates/change-spec.md) — never from memory of a past spec. Read the whole spec once, straight through, before checking anything below.

- [ ] Classification (`feature`/`investigation`) is recorded and the branch name matches it.
- [ ] Every sentence lives in exactly one section; removing any sentence loses information — no fact is restated across sections, and no two sections share a clause of near-identical wording.
- [ ] `What & Why`, `Acceptance Criteria`, and `Doc Sync` are present and non-empty; every other section is present only because it carries real content, not filler.
- [ ] Stays at behavior/contract altitude throughout: no file paths, class names, adapter or library choices, module layout, or diff walkthroughs — including a choice justified by what a specific runtime or dependency currently supports (ground it in the underlying domain property instead, e.g. lossy vs. lossless, not "the JDK lacks a quality dial").
- [ ] `Domain / Design Notes` is present whenever domain contracts, ports, or service signatures change, and absent otherwise.
- [ ] `Grounded in` names a concrete spike finding (never "explored and it seemed fine") or a specific, checkable reason no exploration was needed (never a bare "already understood"). When it names a spike, `Domain / Design Notes` and `Alternatives Considered` reflect that finding, not a domain type, port, or tractability claim reasoned from scratch.
- [ ] Each `Acceptance Criteria` item is externally observable/testable and covers failure, boundary, and accessibility behavior where relevant; none merely restates `What & Why`.
- [ ] Each `Doc Sync` entry names a doc, a section, and the exact new/revised fact — not a topic name or this checklist's own wording.
- [ ] `Invariants` holds only pre-existing guarantees, never this change's own rules. `Tradeoffs Accepted` holds only a real downside with who bears it. `Out of Scope` holds only what a reader would reasonably wonder about, not a restatement of the criteria's own exclusions. Any of the three is omitted rather than left empty.
- [ ] Proportional to the closest approved precedent spec: if this change is smaller than that precedent but the proposal is the same size or larger, cut it.
