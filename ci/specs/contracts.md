# Pipeline contracts

> Standard: Agentic Engineering Standards v1.2.0

## Contract inventory

The GitHub Actions workflows call these public Dagger functions from `.dagger/src/index.ts`:

| Invocation | Caller | Arguments |
| --- | --- | --- |
| `changed` | Local affected-component inspection | `--base`, defaulting to the merge-base with `origin/main`. |
| `verify` | `scripts/verify` (affected checks); `verify-main.yml` (all checks) | `--base`, or `--all`. |

The component gates are `backend-check`, `frontend-check`, and `pipeline-check`. The shared `bash scripts/verify <base-commit-or-ref>` entrypoint validates its base, checks changed Markdown from the merge-base to HEAD, installs and verifies the pipeline module, and runs affected Dagger checks against the supplied base. Pipeline-module type, lint, formatting and test checks run even for documentation-only changes. Each gate exits zero on success; a failure stops later gates.
