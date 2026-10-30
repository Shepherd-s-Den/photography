# Living-doc templates

> Standard: Agentic Engineering Standards v1.2.0

These templates govern product context in `specs/` and pipeline context in `ci/specs/`. Living docs are the system's strategic map: the smallest durable context needed to understand its behavior, boundaries and operating model.

## Knowledge ownership

| Document | Reader's question | Appropriate content |
| --- | --- | --- |
| [Design](design.md) | How does the system fit together and behave? | Core concepts, significant state/sequence diagrams, cross-cutting guarantees and tradeoffs. |
| [Contracts](contracts.md) | Where is the authoritative boundary defined? | Links to executable contracts and essential cross-boundary interpretation. |
| [Testing](testing.md) | How do we gain confidence? | Risk-to-boundary strategy, suite nomenclature and the role of integrated validation. |
| [Operations](operational.md) | How do we run and support it? | Local/NAS startup, topology, durable data, recovery, logs and available monitoring. |

README provides orientation; CONTRIBUTING owns engineering conventions and development gates. Proposals own feature-specific acceptance criteria and design rationale. Executable schemas, reference data and configuration own exact inventories and values. Ignored change evidence owns test mappings, command results, screenshots and review dispositions.

## Strategic synthesis

Read the current owning section and the authoritative source before editing. Treat Doc Sync as a **knowledge delta**, not text to paste: integrate the change into the system model and remove superseded explanation.

Promote a fact when it changes how a future contributor understands a major flow, responsibility, system-wide guarantee or operating decision. Keep feature-local interactions, individual scenarios, UI copy and implementation mechanics with their proposal, contract or source. Point to those owners when further detail is useful.

Draw state diagrams for meaningful lifecycles and sequence diagrams for significant boundary interactions. Diagrams explain decisions and ordering; prose carries only the guarantees they leave unclear. A diagram is not a requirement to draw every branch.

Use the owning template's relevant sections, omitting empty ones. Preserve one current explanation per concept; refine it rather than appending a feature history or creating another document to hold displaced detail.

## Publication check

Review the whole affected document, not only added lines. It passes when:

- A new reader can explain the component's purpose and major flows without reading a feature history.
- Every retained paragraph or diagram supports a durable system-level decision at that document's altitude.
- Detailed facts have authoritative owners reached through useful pointers, rather than duplicated inventories.
- Changed knowledge is integrated, obsolete material is removed and unrelated sections remain coherent.
- Current capability is distinguished from a recommendation or an unavailable operational facility.

Reject a test-case catalog in testing docs, a control-by-control walkthrough in design, or copied schemas/configuration in prose. Correct the abstraction and owner, not merely the word count.
