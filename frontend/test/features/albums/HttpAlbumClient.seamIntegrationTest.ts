import { describe, expect, it, vi } from "vitest";
import { makeHttpAlbumClient } from "../../../src/features/albums/api/HttpAlbumClient";
import { AlbumRequestError } from "../../../src/features/albums/model/Album";

describe("same-origin album HTTP client", () => {
  it("should load persisted workspace geometry and send only type-specific chapter inputs", async () => {
    const workspace = { year: 2026, photoRoot: "/nas/photos", chapters: [], countries: [], spreads: [], photoInventories: {} };
    const vacation = {
      kind: "vacation" as const,
      name: "Kyoto",
      countries: ["jp"],
      start: "2025-12-31",
      end: "2026-01-01",
      sourceFolder: "/photos/Kyoto",
    };
    const home = { kind: "home" as const, countries: ["ro"], sourceFolder: "/photos/Home" };
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify(workspace)))
      .mockResolvedValueOnce(new Response(JSON.stringify({ id: "vacation", scheduling: "failed" }), { status: 201 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ id: "vacation", scheduling: "not-requested" })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ id: "home", scheduling: "queued" }), { status: 201 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ id: "home", scheduling: "coalesced" })));
    const client = makeHttpAlbumClient(fetcher);
    const expectedVacation = {
      name: vacation.name,
      start: vacation.start,
      end: vacation.end,
      countries: vacation.countries,
      sourceFolder: vacation.sourceFolder,
    };
    const expectedHome = { countries: home.countries, sourceFolder: home.sourceFolder };
    const expectedVacationCreation = ["/api/albums/album%2Fid/chapters", { method: "POST", body: JSON.stringify(vacation) }];
    const expectedVacationUpdate = ["/api/albums/album%2Fid/chapters/chapter%2Fid", { method: "PUT", body: JSON.stringify(expectedVacation) }];

    const loadedWorkspace = await client.workspace("album/id");
    const createdVacation = await client.createChapter("album/id", vacation);
    const updatedVacation = await client.updateChapter("album/id", "chapter/id", vacation);
    const createdHome = await client.createChapter("album/id", home);
    const updatedHome = await client.updateChapter("album/id", "home", home);

    expect(loadedWorkspace).toEqual(workspace);
    expect(createdVacation).toEqual({ id: "vacation", scheduling: "failed" });
    expect(updatedVacation).toEqual({ id: "vacation", scheduling: "not-requested" });
    expect(createdHome).toEqual({ id: "home", scheduling: "queued" });
    expect(updatedHome).toEqual({ id: "home", scheduling: "coalesced" });
    expect(fetcher.mock.calls[0]?.[0]).toBe("/api/albums/album%2Fid/workspace");
    expect(fetcher.mock.calls[1]).toMatchObject(expectedVacationCreation);
    expect(fetcher.mock.calls[2]).toMatchObject(expectedVacationUpdate);
    expect(fetcher.mock.calls[3]?.[1]).toMatchObject({ method: "POST", body: JSON.stringify(home) });
    expect(fetcher.mock.calls[4]?.[1]).toMatchObject({ method: "PUT", body: JSON.stringify(expectedHome) });
  });
  it("should read chapter inventories with cancellation and schedule Refresh without a request body", async () => {
    const inventory = {
      photos: [],
      counts: { unplaced: 0, total: 0 },
      processing: { state: "failed", unsupportedCount: 2, skippedCount: 1, issues: [{ stage: "cleanup", code: "cleanup_failed", filename: null }] },
    };
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify(inventory)))
      .mockResolvedValueOnce(new Response('{"scheduling":"queued"}', { status: 202 }))
      .mockResolvedValueOnce(new Response('{"scheduling":"coalesced"}', { status: 202 }))
      .mockResolvedValueOnce(new Response('{"scheduling":"failed"}', { status: 202 }))
      .mockResolvedValueOnce(new Response('{"code":"service_failure"}', { status: 500 }));
    const client = makeHttpAlbumClient(fetcher);
    const controller = new AbortController();
    const expectedFailure = new AlbumRequestError(500, "The request could not be completed. Please try again.");
    const expectedRead = ["/api/chapters/chapter%2Fid/photos", { method: "GET", headers: { Accept: "application/json" }, signal: controller.signal }];
    const expectedRefresh = ["/api/chapters/chapter%2Fid/photos/refresh", { method: "POST", headers: { Accept: "application/json" } }];

    const loaded = await client.chapterPhotos("chapter/id", controller.signal);
    const queued = await client.refreshPhotos("chapter/id");
    const coalesced = await client.refreshPhotos("chapter/id");

    expect(loaded).toEqual(inventory);
    expect(queued).toBe("queued");
    expect(coalesced).toBe("coalesced");
    await expect(client.refreshPhotos("chapter/id")).rejects.toThrow();
    await expect(client.chapterPhotos("chapter/id", controller.signal)).rejects.toEqual(expectedFailure);
    expect(fetcher.mock.calls[0]).toEqual(expectedRead);
    expect(fetcher.mock.calls[1]).toEqual(expectedRefresh);
  });
  it("should send dashboard album operations with immutable year creation and free-form rename", async () => {
    const album = {
      id: "00000000-0000-0000-0000-000000000001",
      year: 2026,
      title: "Journey of the Shepherds 2026",
      createdAt: "2026-10-24T12:00:00Z",
    };
    const page = { albums: [album], page: 1, pageSize: 6, totalAlbums: 1, totalPages: 1 };
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify(page)))
      .mockResolvedValueOnce(new Response(JSON.stringify(album), { status: 201 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ ...album, synopsis: "" })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ ...album, title: "Asian capitals" })))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    const client = makeHttpAlbumClient(fetcher);
    const expectedUrls = ["/api/albums?page=1", "/api/albums", `/api/albums/${album.id}`, `/api/albums/${album.id}`, `/api/albums/${album.id}`];
    const expectedCreation = { method: "POST", body: JSON.stringify({ year: album.year }), headers: { "Content-Type": "application/json" } };

    const listed = await client.list(1);
    const created = await client.create(album.year);
    const opened = await client.open(album.id);
    const renamed = await client.rename(album.id, "Asian capitals");
    await client.remove(album.id);

    expect(listed).toEqual(page);
    expect(created).toEqual(album);
    expect(opened).toEqual({ ...album, synopsis: "" });
    expect(renamed).toEqual({ ...album, title: "Asian capitals" });
    expect(fetcher.mock.calls.map((call) => call[0])).toEqual(expectedUrls);
    expect(fetcher.mock.calls[1]?.[1]).toMatchObject(expectedCreation);
    expect(fetcher.mock.calls[2]?.[1]).toMatchObject({ method: "GET" });
    expect(fetcher.mock.calls[3]?.[1]).toMatchObject({ method: "PATCH", body: JSON.stringify({ title: "Asian capitals" }) });
    expect(fetcher.mock.calls[4]?.[1]).toMatchObject({ method: "DELETE" });
  });

  it.each([
    [400, { message: "Title is blank" }, "Title is blank"],
    [404, { code: "album_not_found" }, "The request could not be completed. Please try again."],
    [500, { code: "service_failure" }, "The request could not be completed. Please try again."],
  ])("should preserve HTTP %i failures without exposing success", async (status, body, message) => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify(body), { status }));
    const client = makeHttpAlbumClient(fetcher);

    await expect(client.open("missing")).rejects.toEqual(new AlbumRequestError(status, message));
  });

  it("should reject network failures, invalid JSON and invalid success shapes", async () => {
    const failure = new Error("Connection unavailable");
    const fetcher = vi
      .fn<typeof fetch>()
      .mockRejectedValueOnce(failure)
      .mockResolvedValueOnce(new Response("<html>not an API</html>"))
      .mockResolvedValueOnce(new Response(JSON.stringify({ albums: [] })));
    const client = makeHttpAlbumClient(fetcher);

    await expect(client.list(1)).rejects.toBe(failure);
    await expect(client.list(1)).rejects.toBeInstanceOf(SyntaxError);
    await expect(client.list(1)).rejects.toBeInstanceOf(Error);
  });

  it("should reject a deletion response that violates the no-content contract", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response("{}"));
    const client = makeHttpAlbumClient(fetcher);

    await expect(client.remove("album")).rejects.toThrow("Unexpected album response status");
  });
});
