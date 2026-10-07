# Photography testing

> Standard: Agentic Engineering Standards v1.2.0

## Strategy

The backend gate compiles the Scala 3 source, the frontend gate validates its minimal HTML entry point, and pipeline tests prove affected-component selection. Database-seam integration tests initialize the schema before exercising database access. Add behavior-focused tests at domain, application, UI, and adapter boundaries as product behavior is introduced.

## Test names

Name domain and application component suites `<Boundary>ComponentTest`. Use them to verify behavior through the component's observable boundary.

Name adapter suites `<Adapter>SeamIntegrationTest`. Exercise each adapter through its concrete seam: send HTTP requests through routes and inspect responses, or initialize a database schema before exercising SQLite access. Loading the real `application.conf` file is also a seam integration test.

Every test case's descriptive name starts with `should` and describes the behavior it guarantees. Keep runner-required prefixes, such as Python's `test_`, before that phrase. Use the vocabulary exposed by the tested boundary.
