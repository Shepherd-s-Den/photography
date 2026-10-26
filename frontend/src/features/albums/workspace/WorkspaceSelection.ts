import type { Spread } from "../model/Workspace";

export type Selection = { kind: "cover" } | { kind: "spread"; spread: Spread } | { kind: "invalid" };

export function findSelection(spreads: readonly Spread[], part: string | null): Selection {
  if (part === "cover") return { kind: "cover" };
  const spread =
    part === null
      ? spreads.find((candidate) => candidate.kind === "album_start")
      : spreads.find((candidate) => candidate.id === part || (candidate.chapter === part && candidate.kind === "chapter_start"));
  return spread === undefined ? { kind: "invalid" } : { kind: "spread", spread };
}

export function findReaderNavigation(
  spreads: readonly Spread[],
  selection: Selection,
): { previous: string | null | undefined; next: string | null | undefined } {
  if (selection.kind === "invalid") return { previous: undefined, next: undefined };
  const parts = ["cover", ...spreads.map((spread) => (spread.kind === "album_start" ? null : spread.id))];
  const index = selection.kind === "cover" ? 0 : spreads.indexOf(selection.spread) + 1;
  return { previous: parts[index - 1], next: parts[index + 1] };
}
