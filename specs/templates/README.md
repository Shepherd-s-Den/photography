# Living-doc templates

> Standard: Agentic Engineering Standards v1.2.0

These templates govern the product living documents in `specs/` and the pipeline documents in `ci/specs/`.

Rules that apply to every living doc:

- **One fact, one place.** Link to the canonical home instead of restating it: contract schemas → `specs/contracts.md`; domain terms → `GLOSSARY.md` when one exists; commands and repository map → `README.md`; engineering conventions → `CONTRIBUTING.md`; pipeline behavior → `ci/specs/`.
- **Own a responsibility, not its membership.** Point to the package or directory that owns an architectural concern; do not enumerate implementation files.
- **Omit, don't pad.** Delete a section with nothing non-duplicated to say.
- **Architecture, not a second implementation.** Use diagrams and brief annotations for relationships and significant flows. Explain testing strategy rather than listing individual tests.
- **Single voice, current state.** Describe how the system works today; do not preserve decision archaeology in living docs.

The SDD skill's Sync & Archive phase updates living docs against these templates.
