# Photography ownership and safety

- Run application commands from repository root; ignored evidence belongs in `.agent-work/<slug>`.
- README and current specs own product authority; executable OpenAPI, migrations and application ports own precise contracts.
- Run `mise install`, then `python3 scripts/local.py`; it installs locked frontend dependencies and reports ready URLs.
- Select isolated SQLite/photo/thumbnail paths using application configuration; original photographs are read-only, thumbnails and application state are writable.
- Never overwrite originals or retained spike/NAS state without explicit owner authorization; disposable databases are identified before recreation.
- Before the first NAS deployment, evolve V1 schema/V2 data with ordinary migrated repository seam tests; retained spike data requires authorization.
- After deployment, preserve applied migrations and introduce versioned changes with appropriate upgrade evidence.
- The supported topology is one backend.
- Preserve SQLite and referenced thumbnails consistently.
- Read `specs/operational.md` for current startup/recovery support.
- Check changed backend/frontend/pipeline with their Dagger component gates; every change runs `dagger call verify`, all when shared impact is uncertain.
- Pipeline module changes also run `npm ci --prefix .dagger && dagger develop && npm --prefix .dagger run verify`.
- Pre-push/CI uses `bash scripts/verify <base>`; enable `.githooks` in the clone and read `ci/specs/operational.md` for revision requirements.
- Use existing `photography.syntax.EitherSyntax.*` postfix `.eitherT` where applicable.
- Keep secrets in local environment/configuration and stop only processes owned by the current work.
- UI precedents: `frontend/src/app/app.css`, workspace styles, ChapterOutline toolbar, AlbumDialog and SpreadConfirmation; source owns exact layout/copy/shortcuts.
