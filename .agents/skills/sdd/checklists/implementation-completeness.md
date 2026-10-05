# Implementation completeness checklist

Gate for every Implementation PR (against that PR's own slice) and, at full scope, for the
Archive PR (against the complete merged change). This checklist asks "did we actually ship what
the spec said," not "is the code well-written" — that's `code-quality.md`.

- [ ] Every acceptance criterion in the merged spec has at least one passing test that traces to
  it by name or scenario — not just a plausible-looking test nearby.
- [ ] Every domain/design element named in the spec's `Domain / Design Notes` (types, ports,
  traits, enums) exists in shipped code under that name.
- [ ] Nothing in the merged spec describes behavior that isn't actually shipped. If scope was
  cut, the spec itself was amended and re-reviewed before this PR — never archived unchanged
  next to a smaller implementation.
- [ ] Every `Doc Sync` entry was applied verbatim to the exact doc and section it named; diff
  the entry's text against the actual doc change, not against the author's memory of intent.
- [ ] The archived spec file's own content (kept or trimmed) matches shipped reality — it
  describes only what exists, never what was once planned.
