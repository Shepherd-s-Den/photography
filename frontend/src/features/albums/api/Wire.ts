import { maximumAlbumYear, minimumAlbumYear, type Album, type AlbumPage, type AlbumSummary } from "../model/Album";

export function readSummary(value: unknown): AlbumSummary {
  const fields = readRecord(value);
  const year = readInteger(fields["year"], minimumAlbumYear);
  if (year > maximumAlbumYear) throw new Error(`Expected an album year from ${minimumAlbumYear.toString()} to ${maximumAlbumYear.toString()}`);
  return { id: readText(fields["id"]), year, title: readText(fields["title"]), createdAt: readText(fields["createdAt"]) };
}

export function readAlbum(value: unknown): Album {
  return { ...readSummary(value), synopsis: readText(readRecord(value)["synopsis"]) };
}

export function readPage(value: unknown): AlbumPage {
  const fields = readRecord(value);
  const pageSize = readInteger(fields["pageSize"], 1);
  if (pageSize !== 6) throw new Error("Unexpected album page size");
  return {
    albums: readArray(fields["albums"]).map(readSummary),
    page: readInteger(fields["page"], 1),
    pageSize,
    totalAlbums: readInteger(fields["totalAlbums"], 0),
    totalPages: readInteger(fields["totalPages"], 0),
  };
}

export function readFailureMessage(value: unknown): string {
  if (typeof value === "object" && value !== null && "message" in value && typeof value.message === "string") return value.message;
  return "The request could not be completed. Please try again.";
}

function readRecord(value: unknown): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) throw new Error("Expected a JSON object");
  return Object.fromEntries(Object.entries(value));
}

function readText(value: unknown): string {
  if (typeof value !== "string") throw new Error("Expected a JSON string");
  return value;
}

function readNumber(value: unknown): number {
  if (typeof value !== "number" || !Number.isFinite(value)) throw new Error("Expected a finite JSON number");
  return value;
}

function readInteger(value: unknown, minimum: number): number {
  const parsed = readNumber(value);
  if (!Number.isInteger(parsed) || parsed < minimum) throw new Error("Expected an integer in range");
  return parsed;
}

function readArray(value: unknown): readonly unknown[] {
  if (!Array.isArray(value)) throw new Error("Expected a JSON array");
  return value;
}
