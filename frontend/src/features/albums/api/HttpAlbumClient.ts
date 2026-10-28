import { AlbumRequestError, type AlbumClient } from "../model/Album";
import { readAlbum, readFailureMessage, readPage, readSummary } from "./Wire";
import { readChapterIdentity, readWorkspace } from "./WorkspaceWire";
import { readPhotoInventory, readScheduling } from "./PhotoWire";

export function makeHttpAlbumClient(fetcher: typeof fetch): AlbumClient {
  async function request<T>(
    path: string,
    method: string,
    body: unknown,
    expectedStatus: number,
    decode: (value: unknown) => T,
    signal?: AbortSignal,
  ): Promise<T> {
    const headers: Record<string, string> = { Accept: "application/json" };
    const init: RequestInit = { method, headers };
    if (signal !== undefined) init.signal = signal;
    if (body !== undefined) {
      headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(body);
    }
    const response = await fetcher(path, init);
    if (!response.ok) {
      const failure: unknown = await response.json();
      throw new AlbumRequestError(response.status, readFailureMessage(failure));
    }
    if (response.status !== expectedStatus) throw new Error("Unexpected album response status");
    const value: unknown = response.status === 204 ? undefined : await response.json();
    return decode(value);
  }

  return {
    list: (page) => request(`/api/albums?page=${String(page)}`, "GET", undefined, 200, readPage),
    create: (year) => request("/api/albums", "POST", { year }, 201, readSummary),
    rename: (id, title) => request(`/api/albums/${encodeURIComponent(id)}`, "PATCH", { title }, 200, readSummary),
    open: (id) => request(`/api/albums/${encodeURIComponent(id)}`, "GET", undefined, 200, readAlbum),
    remove: (id) => request(`/api/albums/${encodeURIComponent(id)}`, "DELETE", undefined, 204, () => undefined),
    workspace: (id, signal) => request(`/api/albums/${encodeURIComponent(id)}/workspace`, "GET", undefined, 200, readWorkspace, signal),
    chapterPhotos: (id, signal) => request(`/api/chapters/${encodeURIComponent(id)}/photos`, "GET", undefined, 200, readPhotoInventory, signal),
    refreshPhotos: (id) =>
      request(`/api/chapters/${encodeURIComponent(id)}/photos/refresh`, "POST", undefined, 202, (value) => {
        const scheduling = readScheduling(value);
        if (scheduling !== "queued" && scheduling !== "coalesced") throw new Error("Refresh was not scheduled");
        return scheduling;
      }),
    createChapter: (id, details) => request(`/api/albums/${encodeURIComponent(id)}/chapters`, "POST", details, 201, readChapterIdentity),
    updateChapter: (id, chapterId, details) => {
      const common = { countries: details.countries, sourceFolder: details.sourceFolder };
      const body = details.kind === "home" ? common : { name: details.name, start: details.start, end: details.end, ...common };
      return request(`/api/albums/${encodeURIComponent(id)}/chapters/${encodeURIComponent(chapterId)}`, "PUT", body, 200, readChapterIdentity);
    },
  };
}
