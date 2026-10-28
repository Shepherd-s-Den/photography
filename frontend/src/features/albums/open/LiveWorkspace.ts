import { createEffect, createSignal, on, onCleanup, type Accessor } from "solid-js";
import type { Album, AlbumClient } from "../model/Album";
import type { AlbumPhotoEvents, ChapterSave, PhotoEvent, Scheduling } from "../model/Photo";
import type { Workspace } from "../model/Workspace";

export function createLiveWorkspace(
  albumId: Accessor<string>,
  client: Pick<AlbumClient, "open" | "workspace" | "chapterPhotos" | "refreshPhotos">,
  events: AlbumPhotoEvents,
) {
  const [album, setAlbum] = createSignal<Album>();
  const [workspace, setWorkspace] = createSignal<Workspace>();
  const [loading, setLoading] = createSignal(true);
  const [error, setError] = createSignal<unknown>();
  const [liveStatus, setLiveStatus] = createSignal<"connecting" | "catching-up" | "live" | "interrupted" | "invalid">("connecting");
  const [inventoryErrors, setInventoryErrors] = createSignal<Readonly<Record<string, string>>>({});
  const [scheduling, setScheduling] = createSignal<Readonly<Record<string, Scheduling | "pending">>>({});
  let operations: {
    reload: () => Promise<void>;
    retryChapter: (id: string) => void;
    refresh: (ids: readonly string[]) => Promise<void>;
    saved: (result: ChapterSave) => Promise<void>;
  };

  createEffect(
    on(albumId, (id) => {
      let active = true;
      let epoch = 0;
      let reading: Promise<void> | undefined;
      let readAgain = false;
      const invalidated = new Set<string>();
      const chapters = new Map<string, { running: boolean; dirty: boolean }>();
      const commands = new Map<string, Promise<void>>();
      const controller = new AbortController();
      let metadataRead: Promise<Album> | undefined;
      setAlbum(undefined);
      setWorkspace(undefined);
      setError(undefined);
      setLoading(true);
      setLiveStatus("connecting");
      setInventoryErrors({});
      setScheduling({});

      function readAlbum(): Promise<Album> {
        if (metadataRead !== undefined) return metadataRead;
        metadataRead = client
          .open(id)
          .then((metadata) => {
            if (active) setAlbum(metadata);
            return metadata;
          })
          .finally(() => {
            metadataRead = undefined;
          });
        return metadataRead;
      }

      function reload(): Promise<void> {
        if (!active) return Promise.resolve();
        if (reading !== undefined) {
          readAgain = true;
          return reading;
        }
        const run = async () => {
          let failure: Error | undefined;
          do {
            readAgain = false;
            epoch++;
            for (const chapter of chapters.values()) chapter.dirty = false;
            try {
              const [metadata, snapshot] = await Promise.all([album() ?? readAlbum(), client.workspace(id, controller.signal)]);
              if (!active) return;
              setAlbum(metadata);
              setWorkspace(snapshot);
              setInventoryErrors({});
              failure = undefined;
            } catch (cause) {
              failure = cause instanceof Error ? cause : new Error("Saved workspace could not be loaded");
            }
          } while (needsAnotherRead());
          reading = undefined;
          if (!active) return;
          setError(() => failure);
          setLoading(false);
          if (liveStatus() === "catching-up" && failure === undefined) setLiveStatus("live");
          const pending = [...invalidated];
          invalidated.clear();
          for (const chapter of pending) retryChapter(chapter);
        };
        reading = run();
        return reading;
      }

      function needsAnotherRead(): boolean {
        return active && readAgain;
      }

      function retryChapter(chapterId: string): void {
        if (!active) return;
        if (reading !== undefined) {
          invalidated.add(chapterId);
          return;
        }
        if (workspace()?.chapters.some((chapter) => chapter.id === chapterId) !== true) {
          void reload();
          return;
        }
        const state = chapters.get(chapterId) ?? { running: false, dirty: false };
        chapters.set(chapterId, state);
        if (state.running) {
          state.dirty = true;
          return;
        }
        const started = epoch;
        state.running = true;
        void client
          .chapterPhotos(chapterId, controller.signal)
          .then((inventory) => {
            const snapshot = workspace();
            if (snapshot === undefined || !active || started !== epoch) return;
            setWorkspace({ ...snapshot, photoInventories: { ...snapshot.photoInventories, [chapterId]: inventory } });
            setInventoryErrors((current) => Object.fromEntries(Object.entries(current).filter(([key]) => key !== chapterId)));
          })
          .catch(() => {
            if (active && started === epoch)
              setInventoryErrors((current) => ({
                ...current,
                [chapterId]: "Photo inventory could not be loaded. Retained photos may be out of date.",
              }));
          })
          .finally(() => {
            state.running = false;
            if (active && state.dirty) {
              state.dirty = false;
              retryChapter(chapterId);
            }
          });
      }

      async function refresh(ids: readonly string[]): Promise<void> {
        if (!active) return;
        await Promise.all(
          ids.map((chapterId) => {
            const existing = commands.get(chapterId);
            if (existing !== undefined) return existing;
            setScheduling((current) => ({ ...current, [chapterId]: "pending" }));
            const command = client
              .refreshPhotos(chapterId)
              .then((result) => {
                if (active) setScheduling((current) => ({ ...current, [chapterId]: result }));
              })
              .catch(() => {
                if (active) setScheduling((current) => ({ ...current, [chapterId]: "failed" }));
              })
              .finally(() => {
                commands.delete(chapterId);
              });
            commands.set(chapterId, command);
            return command;
          }),
        );
      }

      function receive(event: PhotoEvent) {
        if (!active) return;
        if (event.kind === "inventory-changed") retryChapter(event.chapterId);
        else if (event.kind === "subscribed") {
          setLiveStatus("catching-up");
          void reload();
        } else setLiveStatus(event.kind);
      }

      operations = {
        reload,
        retryChapter,
        refresh,
        saved: async (result) => {
          if (!active) return;
          setScheduling((current) => ({ ...current, [result.id]: result.scheduling }));
          await reload();
        },
      };
      let close: (() => void) | undefined;
      void readAlbum().catch((failure: unknown) => {
        if (active) {
          setError(() => failure);
          setLoading(false);
        }
      });
      try {
        close = events.subscribe(id, receive);
      } catch (failure) {
        setError(() => failure);
        setLoading(false);
        setLiveStatus("interrupted");
      }
      onCleanup(() => {
        active = false;
        controller.abort();
        close?.();
        invalidated.clear();
      });
    }),
  );
  return {
    album,
    workspace,
    loading,
    error,
    liveStatus,
    inventoryErrors,
    scheduling,
    reload: () => operations.reload(),
    retryChapter: (id: string) => {
      operations.retryChapter(id);
    },
    refresh: (ids: readonly string[]) => operations.refresh(ids),
    saved: (result: ChapterSave) => operations.saved(result),
  };
}
