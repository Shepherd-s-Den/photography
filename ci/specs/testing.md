# Pipeline testing

> Standard: Agentic Engineering Standards v1.2.0

## Strategy

Vitest tests prove changed-path parsing and affected-component selection. When pipeline files change, the pull-request workflow also runs the Dagger module's host-side type, lint, format, and test checks. Containerized backend and frontend gates verify the selected build hooks work in their pinned environments. The frontend hook installs its lockfile, runs type/lint/format/boundary checks and tests with full statement, branch, function, and line coverage, then builds the production app.

`verify --all` exercises each component and is run by the manually dispatched main-branch workflow. A green selection test alone does not establish that the selected component builds.
