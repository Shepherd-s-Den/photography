import { describe, expect, it } from "vitest";
import { AlbumRequestError, type AlbumPage } from "../../../src/features/albums/model/Album";
import { failureMessage, isMissingAlbum, pagination, validateTitle } from "../../../src/features/albums/model/Presentation";

describe("album presentation rules", () => {
  it.each([
    ["", "Enter an album title."],
    ["   ", "Enter an album title."],
    ["\t\n", "Enter an album title."],
    ["  Coast  ", undefined],
  ])("should validate title %j", (value, expected) => {
    expect(validateTitle(value)).toBe(expected);
  });

  it.each([
    [new Error("Internal details"), false, "The request could not be completed. Please try again."],
    [new AlbumRequestError(404, "Missing album"), true, "Missing album"],
    [new AlbumRequestError(400, "Use a title"), false, "Use a title"],
  ])("should classify visible request failures", (error, missing, message) => {
    expect(isMissingAlbum(error)).toBe(missing);
    expect(failureMessage(error)).toBe(message);
  });

  it.each([
    [1, 3, true, "Albums are loading.", "Albums are loading."],
    [1, 3, false, "Already on the first page.", undefined],
    [2, 3, false, undefined, undefined],
    [3, 3, false, undefined, "Already on the last page."],
    [1, 0, false, "Already on the first page.", "Already on the last page."],
  ])("should explain pagination availability for page %i of %i", (current, total, loading, previous, next) => {
    const page: AlbumPage = { albums: [], page: current, pageSize: 6, totalAlbums: 0, totalPages: total };

    expect(pagination(page, loading)).toEqual({ previous, next });
  });
});
