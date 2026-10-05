# Photography domain model

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0. Lifetime: open from creation through implementation, archived per [the SDD skill's Archive PR phase](../../../.agents/skills/sdd/SKILL.md).

**Date:** 2026-10-05

**Classification:** feature

**Grounded in:** The user supplied an existing Rust domain and requested a direct language port. Its source defines the target behavior, so no open domain or tractability question needs a separate spike.

## What & Why

The backend has no photography domain model yet. Establishing one gives later services a shared vocabulary for photo-book entities and composition, and a stable place for their business rules.

## Domain / Design Notes

The Scala model preserves the source's validated values and closed alternatives as typed data with the same boundaries. Entities and compositions consume those values, and domain rules remain pure.

## Acceptance Criteria

- All ten record identities accept positive signed 64-bit values and reject zero or negative values. Names trim Unicode whitespace at both ends and reject an empty result, embedded line-feed or carriage return, or any NUL.
- A relative location is accepted only when it is nonblank, does not start with `/`, contains no NUL, backslash, or colon, and every slash-delimited segment is nonempty and neither `.` nor `..`. Joining two accepted locations with `/` yields another accepted location.
- A date string is accepted only when it denotes a calendar date and formatting that date as `YYYY-MM-DD` reproduces the input exactly. Visit-date start and end are independently optional; when both are present, end is not earlier than start.
- A timestamp is accepted only from `-262144-01-01T00:00:00.000Z` through `+262142-12-31T23:59:59.999Z`, inclusive; the minimum and maximum signed 64-bit millisecond values are rejected. Lease duration is positive. Adding a duration reports integer overflow as a session error and a result outside the timestamp calendar range as a date error.
- Session creation requires nonnegative caller-supplied time; restoration requires a nonnegative heartbeat and an expiry strictly after it. A session is live exactly when its heartbeat is not later than the supplied time and that time is earlier than its expiry. Renewal succeeds only while live, otherwise returning a session validation error; it preserves identity and owner and sets the new expiry from the supplied time and lease.
- Position accepts zero through 4,294,967,295; advancing the maximum position fails rather than wrapping. Width and height each accept one through 4,294,967,295. A rectangle requires finite coordinates and dimensions, nonnegative `x` and `y`, positive width and height, and `x + width <= 100` and `y + height <= 100`. Crop pan offsets are finite; zoom is finite and greater than zero; the default crop has pan `(0, 0)` and zoom `1`.
- A country code contains exactly two uppercase ASCII letters. A session credential is nonempty, contains no Unicode whitespace or NUL, and renders as `SessionId([REDACTED])` in debug output.
- Camera and lens gear retain their identity and names; only lenses expose an associated camera. Gear selections accept the empty selection, reject duplicates, and expose unique IDs in ascending numeric order.
- Synopsis-owned spreads always use the synopsis template. A chapter spread at position zero uses the chapter-start template; later positions use the regular template. Surface order places spreads before covers and then orders equal surface kinds by identity. Front and back cover sides map to positions zero and one. Bleed removes duplicate edges and orders the result Top, Bottom, Left, Right.
- A regular template has no text slots; every template has at most one text slot; positions are unique. A ready template is nonempty and, for chapter-start and synopsis kinds, has exactly one text slot; its requested kind must match its own kind.
- Invalidity distinguishes identity, name, location, date, date range, dimensions, rectangle, crop, country code, template, ordering, and session errors. Entity references distinguish album, chapter, photo, spread, slot, placement, cover, synopsis, country, destination, gear, template, and session; a placement reference carries its slot identity. Conflicts distinguish duplicate source, country code, or gear; wrong owner; text or occupied slot; invalid gear selection; referenced gear; stale discovery; opening; spine artwork; missing lock; duplicate synopsis; and duplicate cover. An operation result distinguishes invalid input, a missing entity, a conflict, an expired session, a lock with its owner, and an infrastructure failure; a change result distinguishes changed from unchanged values.
- Automated backend tests exercise these acceptance criteria and run in the backend CI gate.

## Doc Sync

- `specs/design.md` — Architecture constraints: record that the backend domain provides pure, validated photography values, entities, compositions, and result variants.
- `GLOSSARY.md` (new) — Language: define Album–Chapter, Chapter–Destination, Photo–Chapter, Spread–Chapter or synopsis, Slot–Spread or Cover, Placement–slot and Photo, and lens–camera relationships; distinguish photo-owned captions from slot-owned bleed.
- `specs/testing.md` — Strategy: list coverage of accepted/rejected value boundaries, default crop, editor-session lifecycle, gear selections, spread/cover/bleed behavior, template readiness, and result variants; state that `sbt test` runs those cases.
- `CONTRIBUTING.md` — Local development: document `cd backend && sbt test` as the backend test command.
- `AGENTS.md` — Gates: change the backend gate from `sbt compile` to `sbt test` so domain tests are required for backend changes.
- `ci/specs/design.md` — Component ownership: record that the pinned backend gate invokes `sbt test`, compiling the backend and running its tests.
- `ci/specs/testing.md` — Strategy: state that backend checks validate domain behavior while pipeline tests validate changed-component selection.
