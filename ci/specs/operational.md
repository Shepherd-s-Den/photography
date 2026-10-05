# Pipeline operations

> Standard: Agentic Engineering Standards v1.2.0

## Workflows

- `build.yml` runs on every pull request. It installs the Dagger module, runs its own checks when pipeline files changed, and then verifies the affected components.
- `verify-main.yml` is manually dispatched from `main` and runs all component checks.

## Toolchain and credentials

Dagger CLI 0.21.9 is installed by the workflows. The module uses pinned builder image tags and weekly Dependabot updates for sbt, npm, and GitHub Actions dependencies. Component checks require no application credentials.
