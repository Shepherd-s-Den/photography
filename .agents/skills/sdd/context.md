# Living context

| Owner | Durable knowledge |
| --- | --- |
| README | Orientation and entrypoints |
| Principles/conventions | Engineering rules and contribution practice |
| Design | Central concepts, ownership, significant lifecycle/sequence diagrams and cross-cutting guarantees |
| Contracts | Authoritative executable boundary pointers and essential interpretation |
| Testing | Risk-to-boundary confidence strategy and enduring suite nomenclature |
| Operations | Current startup, topology, durable state, recovery and available observability |
| Pipeline context | Pipeline design/testing/operations in `ci/specs/` |
| Proposal / ignored evidence | Feature criteria/rationale / test mappings, command results and review dispositions |
| Schemas/configuration/reference data | Exact representations, inventories and values |

- Doc Sync is a knowledge delta, not copy-ready prose. Promote only changes to enduring system understanding; integrate them into the owning section and prune obsolete explanation.
- Read the whole affected document and authoritative current source before writing. One concept has one current explanation; useful pointers disclose details rather than copying them.
- Accuracy and brevity do not justify misplaced content. Feature walkthroughs, individual test scenarios and copied configuration inventories stay with their owners.
- Specialist references earn a document only for durable cross-feature knowledge absent from existing sources, with a conditional pointer; engineering instructions remain within the guidance budget.
- Publication checks the whole resulting document: a new reader understands major behavior without feature history; current capabilities are distinguished from unavailable/recommended facilities.

## Authoring templates

- Design: purpose/boundary, compact central model, significant lifecycles/interactions, unique constraints/tradeoffs. Omit code inventories and per-control walkthroughs.
- Contracts: boundary-to-authority table, essential semantics not owned by design/schema. Exact declarations remain executable.
- Testing: risk-to-boundary strategy, suite categories, integrated/manual confidence and why. Gate configuration owns thresholds; evidence owns cases/results.
- Operations: supported local/runtime entrypoints, isolation, storage/topology, available logs/health/monitoring, safe recovery. Link configuration; describe important gaps honestly.
- Operations includes startup and shutdown, network exposure and state ownership.
- Describe load drivers and supported capacity limits when they affect operation.
- Use relevant sections only. Diagrams resolve meaningful lifecycle/ordering decisions; prose supplies remaining guarantees rather than narrating every branch.
