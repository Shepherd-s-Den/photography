# Pipeline contracts

> Standard: Agentic Engineering Standards v1.2.0

## Contract inventory

| Surface | Authoritative source |
| --- | --- |
| Public Dagger functions and component gates | [Dagger module](../../.dagger/src/index.ts) |
| Path-to-component ownership | [Selection policy](../../.dagger/src/selection.ts) |
| Shared verification and outgoing-revision validation | [Verification entrypoint](../../scripts/verify) and [pre-push hook](../../.githooks/pre-push) |
| Hosted invocation and toolchain pins | [GitHub workflows](../../.github/workflows/) |

## Completion semantics

Successful verification means every required stage completed successfully. Selecting no application component still requires the shared Markdown and pipeline-module checks; an explicit all-component run bypasses selection, not the component gates.
