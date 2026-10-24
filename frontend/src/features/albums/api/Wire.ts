import type { Album, AlbumPage, AlbumSummary } from "../model/Album";

export function readSummary(value: unknown): AlbumSummary {
  const fields = record(value);
  return { id: text(fields["id"]), title: text(fields["title"]), createdAt: text(fields["createdAt"]) };
}

export function readAlbum(value: unknown): Album {
  return { ...readSummary(value), synopsis: text(record(value)["synopsis"]) };
}

export function readPage(value: unknown): AlbumPage {
  const fields = record(value);
  const pageSize = integer(fields["pageSize"], 1);
  if (pageSize !== 6) throw new Error("Unexpected album page size");
  return {
    albums: array(fields["albums"]).map(readSummary),
    page: integer(fields["page"], 1),
    pageSize,
    totalAlbums: integer(fields["totalAlbums"], 0),
    totalPages: integer(fields["totalPages"], 0),
  };
}

export function readFailureMessage(value: unknown): string {
  if (typeof value === "object" && value !== null && "message" in value && typeof value.message === "string") return value.message;
  return "The request could not be completed. Please try again.";
}

function record(value: unknown): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) throw new Error("Expected a JSON object");
  return Object.fromEntries(Object.entries(value));
}

function text(value: unknown): string {
  if (typeof value !== "string") throw new Error("Expected a JSON string");
  return value;
}

function number(value: unknown): number {
  if (typeof value !== "number" || !Number.isFinite(value)) throw new Error("Expected a finite JSON number");
  return value;
}

function integer(value: unknown, minimum: number): number {
  const parsed = number(value);
  if (!Number.isInteger(parsed) || parsed < minimum) throw new Error("Expected an integer in range");
  return parsed;
}

function array(value: unknown): readonly unknown[] {
  if (!Array.isArray(value)) throw new Error("Expected a JSON array");
  return value;
}
