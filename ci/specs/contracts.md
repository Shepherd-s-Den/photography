# Pipeline contracts

> Standard: Agentic Engineering Standards v1.2.0

## Contract inventory

The GitHub Actions workflows call these public Dagger functions from `.dagger/src/index.ts`:

| Invocation | Caller | Arguments |
| --- | --- | --- |
| `changed` | `build.yml`, every pull request | `--base`, defaulting to the merge-base with `origin/main`. |
| `verify` | `build.yml` (affected checks); `verify-main.yml` (all checks) | `--base`, or `--all`. |

The component gates are `backend-check`, `frontend-check`, and `pipeline-check`. The pipeline module's host-side verification additionally runs its type, lint, formatting, and test checks when pipeline files change. Each check exits zero on success and non-zero on failure.
