import type { AlbumClient } from "../../../src/features/albums/model/Album";
import type { Workspace } from "../../../src/features/albums/model/Workspace";

export type SnapshotClient = Omit<AlbumClient, "chapters" | "composition" | "spread" | "countries" | "photoSource"> & {
  workspace: (id: string, signal?: AbortSignal) => Promise<Workspace>;
};

export function resourceFixture(client: Pick<SnapshotClient, "workspace" | "chapterPhotos">) {
  let snapshot: Promise<Workspace>;
  const initialInventories = new Set<string>();
  return {
    chapters: (id: string, signal: AbortSignal) => {
      initialInventories.clear();
      snapshot = client.workspace(id, signal);
      return snapshot.then((value) => {
        for (const chapter of value.chapters) initialInventories.add(chapter.id);
        return value.chapters;
      });
    },
    composition: () => snapshot.then((value) => value.spreads.map(({ id, chapter }) => ({ id, chapter }))),
    spread: (_id: string, spreadId: string) =>
      snapshot.then((value) => {
        const spread = value.spreads.find((entry) => entry.id === spreadId);
        if (spread === undefined) throw new Error("Missing fixture spread");
        return spread;
      }),
    countries: () => snapshot.then((value) => value.countries),
    photoSource: () => snapshot.then((value) => value.photoRoot),
    chapterPhotos: (id: string, signal: AbortSignal) => {
      if (initialInventories.delete(id))
        return snapshot.then((value) => {
          const inventory = value.photoInventories[id];
          if (inventory === undefined) return client.chapterPhotos(id, signal);
          return inventory;
        });
      return client.chapterPhotos(id, signal);
    },
  };
}
