# Pipeline operations

> Standard: Agentic Engineering Standards v1.2.0

## Workflows

- `build.yml` runs on every pull request. It calls `bash scripts/verify "origin/${GITHUB_BASE_REF}"` for changed Markdown, pipeline-module and affected-component checks.
- `verify-main.yml` is manually dispatched from `main` and runs all component checks.

## Toolchain and credentials

Dagger CLI 0.21.9 is installed by the workflows. The module uses pinned builder image tags and weekly Dependabot updates for sbt, npm, and GitHub Actions dependencies. Component checks require no application credentials.

## Local push verification

Enable the hook per clone with `git config core.hooksPath .githooks` from the repository root. It uses `mise exec` when available and calls the same `scripts/verify` entrypoint as pull-request CI; Docker and the pinned toolchain are required.

For each non-deletion update, the outgoing revision must be the checked-out HEAD, with no tracked edits or non-ignored untracked files. Ignored build outputs and `.agent-work/` evidence remain allowed. Deletion updates require no build.

Pushes updating an existing remote `main` compare against that remote's old commit. Other updates compare against the target remote's local tracking `main`; fetch that remote's `main` first. Missing bases fail explicitly. The verifier receives no hook input so it cannot consume subsequent ref-update records.
