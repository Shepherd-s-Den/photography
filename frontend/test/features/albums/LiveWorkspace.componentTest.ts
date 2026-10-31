import { createRoot, createSignal } from "solid-js";
import { waitFor } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import { createLiveWorkspace as createResourceWorkspace } from "../../../src/features/albums/open/LiveWorkspace";
import { resourceFixture, type SnapshotClient } from "./WorkspaceResources";
import type { AlbumPhotoEvents, PhotoEvent, PhotoInventory } from "../../../src/features/albums/model/Photo";
import type { Spread, Workspace } from "../../../src/features/albums/model/Workspace";
import type { AlbumClient } from "../../../src/features/albums/model/Album";

describe("live workspace application coordination", () => {
  function createLiveWorkspace(
    id: Parameters<typeof createResourceWorkspace>[0],
    client: Pick<AlbumClient, "open" | "chapterPhotos" | "refreshPhotos"> & { workspace: (id: string, signal?: AbortSignal) => Promise<Workspace> },
    events: AlbumPhotoEvents,
  ) {
    return createResourceWorkspace(id, { ...client, ...resourceFixture(client) }, events);
  }
  const inventory: PhotoInventory = {
    photos: [],
    placedPhotoIds: [],
    counts: { unplaced: 0, total: 0 },
    processing: { state: "idle", unsupportedCount: 0, skippedCount: 0, issues: [] },
  };
  const workspace: Workspace = {
    year: 2026,
    photoRoot: "/photos",
    countries: [],
    spreads: [],
    chapters: ["a", "b"].map((id) => ({
      id,
      kind: "home",
      name: id,
      countries: [],
      start: "2026-01-01",
      end: "2026-12-31",
      sourceFolder: "/photos",
      description: "",
    })),
    photoInventories: { a: inventory, b: inventory },
  };
  const album = { id: "album", year: 2026, title: "Album", synopsis: "", createdAt: "2026-01-01T00:00:00Z" };

  it.each([false, true])("should compose independently timed resources and honor acquisition disposal: %s", async (closed) => {
    const metadata = pendingRead<typeof album>();
    const chapterMetadata = pendingRead<Workspace["chapters"]>();
    const membership = pendingRead<readonly { id: string; chapter: string | null }[]>();
    const geometry = pendingRead<Spread>();
    const photos = pendingRead<PhotoInventory>();
    const opening: Spread = { id: "opening", chapter: null, kind: "album_start", templateId: "layout", slots: [] };
    let receive: (event: PhotoEvent) => void = () => {
      throw new Error("Not subscribed");
    };
    const close = vi.fn();
    const events: AlbumPhotoEvents = {
      subscribe: (_id, listener) => {
        receive = listener;
        return close;
      },
    };
    const client = {
      open: vi.fn().mockReturnValue(metadata.promise),
      chapters: vi.fn().mockReturnValue(chapterMetadata.promise),
      composition: vi.fn().mockReturnValue(membership.promise),
      spread: vi.fn().mockReturnValue(geometry.promise),
      countries: vi.fn().mockResolvedValue(workspace.countries),
      photoSource: vi.fn().mockResolvedValue("/photos"),
      chapterPhotos: vi.fn().mockReturnValueOnce(photos.promise).mockResolvedValue(inventory),
      refreshPhotos: vi.fn(),
    };
    const { live, dispose } = createRoot((release) => ({ live: createResourceWorkspace(() => "album", client, events), dispose: release }));
    receive({ kind: "subscribed" });
    expect(client.chapters).toHaveBeenCalledOnce();
    expect(client.chapterPhotos).not.toHaveBeenCalled();
    metadata.resolve(album);
    chapterMetadata.resolve([...workspace.chapters].reverse());
    membership.resolve([{ id: opening.id, chapter: null }]);
    await waitFor(() => {
      expect(client.spread).toHaveBeenCalledWith(album.id, opening.id, null, expect.any(AbortSignal));
      expect(client.chapterPhotos).toHaveBeenCalledTimes(2);
    });
    receive({ kind: "inventory-changed", chapterId: "b" });
    expect(live.workspace()).toBeUndefined();
    if (closed) dispose();
    geometry.resolve(opening);
    photos.resolve(inventory);
    await new Promise((resolve) => setTimeout(resolve, 0));
    if (closed) {
      expect(live.workspace()).toBeUndefined();
      expect(close).toHaveBeenCalledOnce();
    } else {
      expect(live.workspace()?.chapters.map((chapter) => chapter.id)).toEqual(["b", "a"]);
      expect(live.workspace()?.spreads).toEqual([opening]);
      expect(live.liveStatus()).toBe("live");
      expect(client.chapterPhotos).toHaveBeenCalledTimes(3);
      dispose();
    }
  });

  it("should expose duplicated chapter identities as an acquisition failure instead of silently replacing an inventory", async () => {
    const duplicated = { ...workspace, chapters: [...workspace.chapters, ...workspace.chapters] };
    const client = {
      open: vi.fn().mockResolvedValue(album),
      workspace: vi.fn().mockResolvedValue(duplicated),
      chapterPhotos: vi.fn().mockResolvedValue(inventory),
      refreshPhotos: vi.fn(),
    };
    const events: AlbumPhotoEvents = {
      subscribe: (_id, receive) => {
        receive({ kind: "subscribed" });
        return vi.fn();
      },
    };
    const { live, dispose } = createRoot((release) => ({ live: createLiveWorkspace(() => "album", client, events), dispose: release }));
    await waitFor(() => {
      expect(live.error()).toEqual(new Error("Duplicate chapter identities"));
    });
    expect(live.workspace()).toBeUndefined();
    dispose();
  });

  it("should wait for subscribed, buffer changes during catch-up and serialize trailing reads for unselected chapters", async () => {
    const snapshot = pendingRead<Workspace>();
    const first = pendingRead<PhotoInventory>();
    const second = pendingRead<PhotoInventory>();
    const receive = vi.fn<(event: PhotoEvent) => void>();
    const close = vi.fn();
    const events: AlbumPhotoEvents = {
      subscribe: vi.fn<AlbumPhotoEvents["subscribe"]>((_id, listener) => {
        receive.mockImplementation(listener);
        return close;
      }),
    };
    const client = {
      open: vi.fn().mockResolvedValue(album),
      workspace: vi.fn().mockReturnValue(snapshot.promise),
      chapterPhotos: vi.fn<AlbumClient["chapterPhotos"]>().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise),
      refreshPhotos: vi.fn(),
    };
    const { live, dispose } = createRoot((release) => ({ live: createLiveWorkspace(() => "album", client, events), dispose: release }));

    expect(events.subscribe).toHaveBeenCalledOnce();
    expect(client.workspace).not.toHaveBeenCalled();
    receive({ kind: "subscribed" });
    receive({ kind: "inventory-changed", chapterId: "b" });
    receive({ kind: "inventory-changed", chapterId: "b" });
    snapshot.resolve(workspace);
    await waitFor(() => {
      expect(client.chapterPhotos).toHaveBeenCalledTimes(1);
    });
    receive({ kind: "inventory-changed", chapterId: "b" });
    receive({ kind: "inventory-changed", chapterId: "b" });

    expect(client.workspace).toHaveBeenCalledOnce();
    expect(client.chapterPhotos.mock.calls[0]?.[0]).toBe("b");
    first.resolve({ ...inventory, processing: { ...inventory.processing, state: "running" } });
    await waitFor(() => {
      expect(client.chapterPhotos).toHaveBeenCalledTimes(2);
    });
    second.resolve({ ...inventory, processing: { ...inventory.processing, state: "completed" } });
    await waitFor(() => {
      expect(live.workspace()?.photoInventories["b"]?.processing.state).toBe("completed");
    });

    expect(live.workspace()?.photoInventories["a"]).toEqual(inventory);
    expect(client.open).toHaveBeenCalledOnce();
    expect(client.refreshPhotos).not.toHaveBeenCalled();
    dispose();
    expect(close).toHaveBeenCalledOnce();
    expect(client.chapterPhotos.mock.calls[0]?.[1].aborted).toBe(true);
  });

  it("should catch up after interruption, reject superseded reads and keep failed reads explicit and retryable", async () => {
    const old = pendingRead<PhotoInventory>();
    const reconnect = pendingRead<Workspace>();
    let receive: (event: PhotoEvent) => void = () => {
      throw new Error("Not subscribed");
    };
    const events: AlbumPhotoEvents = {
      subscribe: (_id, listener) => {
        receive = listener;
        return vi.fn();
      },
    };
    const client = {
      open: vi.fn().mockResolvedValue(album),
      workspace: vi.fn().mockResolvedValueOnce(workspace).mockReturnValueOnce(reconnect.promise),
      chapterPhotos: vi.fn().mockReturnValueOnce(old.promise).mockRejectedValueOnce(new Error("offline")).mockResolvedValue(inventory),
      refreshPhotos: vi.fn(),
    };
    const { live, dispose } = createRoot((release) => ({ live: createLiveWorkspace(() => "album", client, events), dispose: release }));
    receive({ kind: "subscribed" });
    await waitFor(() => {
      expect(live.workspace()).toEqual(workspace);
    });
    receive({ kind: "inventory-changed", chapterId: "a" });
    receive({ kind: "interrupted" });

    expect(live.liveStatus()).toBe("interrupted");
    receive({ kind: "subscribed" });
    expect(live.liveStatus()).toBe("catching-up");
    receive({ kind: "inventory-changed", chapterId: "b" });
    reconnect.resolve(workspace);
    await waitFor(() => {
      expect(live.inventoryErrors()["b"]).toBeDefined();
    });
    old.resolve({ ...inventory, processing: { ...inventory.processing, state: "failed" } });
    await Promise.resolve();

    expect(live.workspace()?.photoInventories["a"]?.processing.state).toBe("idle");
    expect(live.liveStatus()).toBe("live");
    expect(live.workspace()?.photoInventories["b"]).toEqual(inventory);
    live.retryChapter("b");
    await waitFor(() => {
      expect(live.inventoryErrors()["b"]).toBeUndefined();
    });
    receive({ kind: "invalid" });
    expect(live.liveStatus()).toBe("invalid");
    expect(client.refreshPhotos).not.toHaveBeenCalled();
    dispose();
  });

  it("should isolate album switching, close old subscriptions, abort work and ignore late completions", async () => {
    const firstAlbum = pendingRead<typeof album>();
    const firstWorkspace = pendingRead<Workspace>();
    const listeners: ((event: PhotoEvent) => void)[] = [];
    const closers = [vi.fn(), vi.fn()];
    const events: AlbumPhotoEvents = {
      subscribe: vi.fn<AlbumPhotoEvents["subscribe"]>((_id, receive) => {
        listeners.push(receive);
        return closers[listeners.length - 1] ?? vi.fn();
      }),
    };
    const client = {
      open: vi
        .fn()
        .mockReturnValueOnce(firstAlbum.promise)
        .mockResolvedValue({ ...album, id: "other", year: 2027 }),
      workspace: vi
        .fn<SnapshotClient["workspace"]>()
        .mockReturnValueOnce(firstWorkspace.promise)
        .mockResolvedValue({ ...workspace, year: 2027 }),
      chapterPhotos: vi.fn(),
      refreshPhotos: vi.fn(),
    };
    const { live, change, dispose } = createRoot((release) => {
      const [id, changeAlbum] = createSignal("album");
      return { live: createLiveWorkspace(id, client, events), change: changeAlbum, dispose: release };
    });
    listeners[0]?.({ kind: "subscribed" });
    change("other");
    await waitFor(() => {
      expect(events.subscribe).toHaveBeenCalledTimes(2);
    });
    listeners[1]?.({ kind: "subscribed" });
    await waitFor(() => {
      expect(live.workspace()?.year).toBe(2027);
    });
    firstAlbum.resolve(album);
    firstWorkspace.resolve(workspace);
    listeners[0]?.({ kind: "inventory-changed", chapterId: "a" });
    await Promise.resolve();

    expect(live.album()?.id).toBe("other");
    expect(live.workspace()?.year).toBe(2027);
    expect(closers[0]).toHaveBeenCalledOnce();
    expect(client.workspace.mock.calls[0]?.[1]?.aborted).toBe(true);
    expect(client.chapterPhotos).not.toHaveBeenCalled();
    dispose();
    expect(closers[1]).toHaveBeenCalledOnce();
  });

  it("should preserve saved identity after scheduling failure and distinguish scoped Refresh scheduling from processing", async () => {
    const pending = pendingRead<"queued" | "coalesced">();
    let receive: (event: PhotoEvent) => void = () => {
      throw new Error("Not subscribed");
    };
    const events: AlbumPhotoEvents = {
      subscribe: (_id, listener) => {
        receive = listener;
        return vi.fn();
      },
    };
    const client = {
      open: vi.fn().mockResolvedValue(album),
      workspace: vi.fn().mockResolvedValue(workspace),
      chapterPhotos: vi.fn().mockResolvedValue(inventory),
      refreshPhotos: vi
        .fn()
        .mockReturnValueOnce(pending.promise)
        .mockRejectedValueOnce(new Error("Scheduling unavailable"))
        .mockResolvedValue("coalesced"),
    };
    const { live, dispose } = createRoot((release) => ({ live: createLiveWorkspace(() => "album", client, events), dispose: release }));
    receive({ kind: "subscribed" });
    await waitFor(() => {
      expect(live.workspace()).toBeDefined();
    });
    await live.saved({ id: "a", scheduling: "failed" });

    expect(live.scheduling()["a"]).toBe("failed");
    const refresh = live.refresh(["a", "b"]);
    const duplicate = live.refresh(["a"]);
    await waitFor(() => {
      expect(live.scheduling()["b"]).toBe("failed");
    });
    expect(live.scheduling()["a"]).toBe("pending");
    pending.resolve("queued");
    await Promise.all([refresh, duplicate]);

    expect(client.refreshPhotos.mock.calls).toEqual([["a"], ["b"]]);
    expect(live.scheduling()).toEqual({ a: "queued", b: "failed" });
    expect(live.workspace()?.photoInventories["a"]?.processing.state).toBe("idle");
    await live.refresh(["b"]);
    expect(live.scheduling()["b"]).toBe("coalesced");
    dispose();
  });

  it("should retain explicit workspace failures, retry existing state and coalesce reloads instead of starting imports", async () => {
    const pending = pendingRead<Workspace>();
    let receive: (event: PhotoEvent) => void = () => {
      throw new Error("Not subscribed");
    };
    const events: AlbumPhotoEvents = {
      subscribe: (_id, listener) => {
        receive = listener;
        return vi.fn();
      },
    };
    const failure = new Error("Failed inventory");
    const client = {
      open: vi.fn().mockResolvedValue(album),
      workspace: vi.fn().mockRejectedValueOnce(failure).mockReturnValueOnce(pending.promise).mockResolvedValue(workspace),
      chapterPhotos: vi.fn(),
      refreshPhotos: vi.fn(),
    };
    const { live, dispose } = createRoot((release) => ({ live: createLiveWorkspace(() => "album", client, events), dispose: release }));
    receive({ kind: "subscribed" });
    await waitFor(() => {
      expect(live.error()).toBe(failure);
    });
    expect(live.workspace()).toBeUndefined();
    const retry = live.reload();
    const trailing = live.reload();
    pending.resolve(workspace);
    await Promise.all([retry, trailing]);

    expect(live.error()).toBeUndefined();
    expect(live.workspace()).toEqual(workspace);
    expect(client.workspace).toHaveBeenCalledTimes(3);
    expect(client.refreshPhotos).not.toHaveBeenCalled();
    dispose();
  });

  it("should catch up unknown chapter signals through the existing workspace and recover from a failed snapshot", async () => {
    let receive: (event: PhotoEvent) => void = () => {
      throw new Error("Not subscribed");
    };
    const events: AlbumPhotoEvents = {
      subscribe: (_id, listener) => {
        receive = listener;
        return vi.fn();
      },
    };
    const client = {
      open: vi.fn().mockResolvedValue(album),
      workspace: vi.fn().mockResolvedValueOnce(workspace).mockRejectedValueOnce("Unreadable snapshot").mockResolvedValue(workspace),
      chapterPhotos: vi.fn(),
      refreshPhotos: vi.fn(),
    };
    const { live, dispose } = createRoot((release) => ({ live: createLiveWorkspace(() => "album", client, events), dispose: release }));
    receive({ kind: "subscribed" });
    await waitFor(() => {
      expect(live.workspace()).toBeDefined();
    });
    receive({ kind: "inventory-changed", chapterId: "new-chapter" });
    await waitFor(() => {
      expect(live.error()).toEqual(new Error("Saved workspace could not be loaded"));
    });
    receive({ kind: "inventory-changed", chapterId: "new-chapter" });
    await waitFor(() => {
      expect(live.error()).toBeUndefined();
    });

    expect(client.workspace).toHaveBeenCalledTimes(3);
    expect(client.chapterPhotos).not.toHaveBeenCalled();
    expect(client.refreshPhotos).not.toHaveBeenCalled();
    dispose();
    live.retryChapter("a");
    await live.reload();
    await live.refresh(["a"]);
    await live.saved({ id: "a", scheduling: "failed" });
    expect(client.chapterPhotos).not.toHaveBeenCalled();
    expect(client.workspace).toHaveBeenCalledTimes(3);
    expect(client.refreshPhotos).not.toHaveBeenCalled();
  });

  it.each(["snapshot", "inventory", "inventory-failure", "refresh", "refresh-failure", "metadata"] as const)(
    "should release the workspace without publishing a late %s outcome",
    async (operation) => {
      const metadata = pendingRead<typeof album>();
      const snapshot = pendingRead<Workspace>();
      const photos = pendingRead<PhotoInventory>();
      const command = pendingRead<"queued" | "coalesced">();
      let receive: (event: PhotoEvent) => void = () => {
        throw new Error("Not subscribed");
      };
      const close = vi.fn();
      const events: AlbumPhotoEvents = {
        subscribe: (_id, listener) => {
          receive = listener;
          return close;
        },
      };
      const client = {
        open: vi.fn().mockReturnValue(operation === "metadata" ? metadata.promise : Promise.resolve(album)),
        workspace: vi.fn().mockResolvedValueOnce(workspace).mockReturnValue(snapshot.promise),
        chapterPhotos: vi.fn().mockReturnValue(photos.promise),
        refreshPhotos: vi.fn().mockReturnValue(command.promise),
      };
      const { live, dispose } = createRoot((release) => ({ live: createLiveWorkspace(() => "album", client, events), dispose: release }));
      receive({ kind: "subscribed" });
      if (operation !== "metadata")
        await waitFor(() => {
          expect(live.workspace()).toBeDefined();
        });
      const pending = operation === "snapshot" ? live.reload() : operation.startsWith("refresh") ? live.refresh(["a"]) : Promise.resolve();
      if (operation.startsWith("inventory")) live.retryChapter("a");
      dispose();
      const failure = new Error("Late failure after exit");
      if (operation === "snapshot") snapshot.reject(failure);
      if (operation === "inventory") photos.resolve(inventory);
      if (operation === "inventory-failure") photos.reject(failure);
      if (operation === "refresh") command.resolve("queued");
      if (operation === "refresh-failure") command.reject(failure);
      if (operation === "metadata") metadata.reject(failure);
      await pending;
      await new Promise((resolve) => setTimeout(resolve, 0));

      expect(close).toHaveBeenCalledOnce();
      expect(live.error()).toBeUndefined();
      if (operation.startsWith("refresh")) expect(live.scheduling()["a"]).toBe("pending");
    },
  );

  it("should reject chapter results while switching into an album that has not subscribed yet", async () => {
    const photos = pendingRead<PhotoInventory>();
    let receive: (event: PhotoEvent) => void = () => {
      throw new Error("Not subscribed");
    };
    const events: AlbumPhotoEvents = {
      subscribe: (_id, listener) => {
        receive = listener;
        return vi.fn();
      },
    };
    const client = {
      open: vi.fn().mockResolvedValue(album),
      workspace: vi.fn().mockResolvedValue(workspace),
      chapterPhotos: vi.fn().mockReturnValue(photos.promise),
      refreshPhotos: vi.fn(),
    };
    const { live, change, dispose } = createRoot((release) => {
      const [id, changeAlbum] = createSignal("album");
      return { live: createLiveWorkspace(id, client, events), change: changeAlbum, dispose: release };
    });
    receive({ kind: "subscribed" });
    await waitFor(() => {
      expect(live.workspace()).toBeDefined();
    });
    live.retryChapter("a");
    change("other");
    await waitFor(() => {
      expect(live.workspace()).toBeUndefined();
    });
    photos.resolve(inventory);
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(live.workspace()).toBeUndefined();
    dispose();
  });

  it("should expose browser subscription construction failure without claiming a loaded workspace", async () => {
    const failure = new Error("EventSource unavailable");
    const events: AlbumPhotoEvents = {
      subscribe: () => {
        throw failure;
      },
    };
    const client = {
      open: vi.fn().mockResolvedValue(album),
      workspace: vi.fn(),
      chapterPhotos: vi.fn(),
      refreshPhotos: vi.fn(),
    };
    const { live, dispose } = createRoot((release) => ({ live: createLiveWorkspace(() => "album", client, events), dispose: release }));

    expect(live.error()).toBe(failure);
    expect(live.loading()).toBe(false);
    expect(live.liveStatus()).toBe("interrupted");
    expect(client.workspace).not.toHaveBeenCalled();
    dispose();
    await Promise.resolve();
  });

  function pendingRead<T>() {
    let resolve: (value: T) => void = () => {
      throw new Error("Read not started");
    };
    let reject: (error: Error) => void = () => {
      throw new Error("Read not started");
    };
    const promise = new Promise<T>((finish, fail) => {
      resolve = finish;
      reject = fail;
    });
    return { promise, resolve, reject };
  }
});
