# Independent review

- One review phase per PR; use [the fixed template](templates/review.md). Architecture and convention violations count even with green tests.
- Start a fresh, messageable reviewer, not a one-shot invocation. Persist its agent ID, baseline/candidate and ignored report path in work state.
- The launch supplies scope, authoritative sources, applicable template and output path; the reviewer checks source before reconciling implementer conclusions.
- Review prompts must not preload disputed conclusions or exclude applicable conventions as style noise.
- Review the complete candidate and existing affected behavior. Pattern-following changes verify precedent fit and adaptation; novel ownership/contracts receive deeper flow, removal and failure analysis.
- Independence means verifying conclusions, not duplicating all preparatory activities. Unexpected defects still belong in findings.
- The reviewer writes candidate identity, file coverage, evidence, findings and open gates before returning. The orchestrator verifies the report exists.
- Each finding names the rule, source location and consequence. The reviewer does not edit or grant merge approval.
- The orchestrator accepts and fixes, or rejects with source-backed evidence. A real rule exception requires maintainer authorization; rationale alone is insufficient.
- Send accepted repairs and affected late changes to the same reviewer for targeted final-candidate verification, then rerun affected automated gates.
- If its session is unavailable, automatically launch a messageable replacement with the saved report and outstanding verification scope; this resumes the authorized phase without another permission request.
- A replacement checks repairs and affected consequences independently; saved evidence does not masquerade as a live reviewer or final verification.
- An additional unrestricted whole-PR review requires a separate decision. Routine repair verification is not another whole review.
- Close only when coverage is accounted for, findings adjudicated, accepted repairs verified and final candidate checks pass.
- For a maintainer-reported miss, reopen the affected gate and inspect its consequences across the candidate. Replay the failing decision and a valid control when validating a changed review rule; do not mandate such exercises for ordinary adaptations.
