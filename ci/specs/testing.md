# Pipeline testing

> Standard: Agentic Engineering Standards v1.2.0

## Strategy

Vitest tests prove changed-path parsing and affected-component selection, including hooks, SDD scripts and toolchain configuration. The shared local/CI entrypoint always runs the Dagger module's host-side type, lint, format and test checks. Containerized backend and frontend gates verify the selected build hooks work in their pinned environments. The frontend hook installs its lockfile, runs type/lint/format/boundary checks and tests with full statement, branch, function, and line coverage, then builds the production app.

`scripts/test_verification.py` exercises the real pre-push hook and verification shell script in temporary Git repositories, substituting only npm and Dagger commands. It proves gate order and fail-fast behavior, Markdown rejection, remote/main base selection, outgoing-HEAD and clean-tree requirements, missing-base failures and deletion handling. The pipeline's local-setup check supplies Git, Bash, the hook, workflow and Markdown checker alongside the script suite.

`verify --all` exercises each component and is run by the manually dispatched main-branch workflow. A green selection test alone does not establish that the selected component builds.
