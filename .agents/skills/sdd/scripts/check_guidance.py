#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path

from check_markdown_wrapping import check

SKILL = Path(".agents/skills/sdd")
LIMIT = 480


def inventory(root: Path) -> list[Path]:
    owner = root / "domains/media/audiobookshelf"
    if not owner.is_dir():
        owner = root
    files = set((root / SKILL).rglob("*.md"))
    files.update((root / ".agents").glob("*.md"))
    files.update((owner / "specs/templates").rglob("*.md"))
    files.update((owner / "frontend").glob("*PATTERNS*.md"))
    for directory in {root, owner}:
        for name in ("AGENTS.md", "CLAUDE.md", "DESIGN-PRINCIPLES.md", "CONTRIBUTING.md"):
            path = directory / name
            if path.exists():
                files.add(path)
    files.update((root / ".github/instructions").rglob("*.md"))
    wrapper = root / ".github/copilot-instructions.md"
    if wrapper.exists():
        files.add(wrapper)
    return sorted(files)


def inspect(root: Path) -> tuple[int, dict[str, str]]:
    files = inventory(root)
    owner = root / "domains/media/audiobookshelf"
    if not owner.is_dir():
        owner = root
    entrypoints = {root / "AGENTS.md", root / ".agents/README.md", owner / "AGENTS.md",
                   owner / "DESIGN-PRINCIPLES.md", owner / "CONTRIBUTING.md"}
    for path in entrypoints:
        if not path.is_file():
            raise ValueError(f"missing entrypoint: {path}")
    required = ("SKILL.md", "executor.md", "reviewer.md", "principles.md",
                "conventions.md", "context.md", "local.md",
                "templates/change-spec.md", "templates/design-map.md", "templates/review.md")
    for name in required:
        if not (root / SKILL / name).is_file():
            raise ValueError(f"missing guidance: {name}")
    total = sum(len(path.read_text().splitlines()) for path in files)
    if total > LIMIT:
        raise ValueError(f"{root}: {total} guidance lines exceed {LIMIT}")
    for path in files:
        errors = check(path)
        if errors:
            raise ValueError(f"{path}: invalid Markdown wrapping: {errors}")
    shared = {
        str(path.relative_to(root / SKILL)): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in (root / SKILL).rglob("*")
        if path.is_file() and path.suffix in (".md", ".py") and path.name != "local.md"
    }
    print(f"{root}: {total}/{LIMIT} lines across {len(files)} guidance files")
    return total, shared


def main() -> int:
    parser = argparse.ArgumentParser(description="Check guidance budget and optional cross-repo parity.")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[4])
    parser.add_argument("--peer", type=Path)
    args = parser.parse_args()
    try:
        total, shared = inspect(args.root.resolve())
        if args.peer:
            peer_total, peer_shared = inspect(args.peer.resolve())
            if total != peer_total or shared != peer_shared:
                raise ValueError("guidance totals or shared content differ between repositories")
    except (OSError, ValueError) as error:
        parser.exit(1, f"guidance: {error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
