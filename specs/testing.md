# Photography testing

> Standard: Agentic Engineering Standards v1.2.0

## Strategy

The backend gate compiles the Scala 3 source, the frontend gate validates its minimal HTML entry point, and pipeline tests prove affected-component selection. Database-seam integration tests initialize the schema before exercising database access. Add behavior-focused tests at domain, application, UI, and adapter boundaries as product behavior is introduced.

## Test names

Name tests for the behavior they verify.
