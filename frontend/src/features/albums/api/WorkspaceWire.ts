import { maximumAlbumYear, minimumAlbumYear } from "../model/Album";
import type { Chapter, Country, Slot, Spread, Workspace } from "../model/Workspace";
import { readArray, readNumber, readRecord, readText } from "./Wire";
import { readPhotoInventory, readScheduling } from "./PhotoWire";
import type { ChapterSave } from "../model/Photo";

export function readWorkspace(value: unknown): Workspace {
  const fields = readRecord(value);
  const year = readNumber(fields["year"]);
  if (!Number.isInteger(year) || year < minimumAlbumYear || year > maximumAlbumYear) throw new Error("Expected an album year in range");
  const chapters = readArray(fields["chapters"]).map(readChapter);
  const photoInventories = Object.fromEntries(
    Object.entries(readRecord(fields["photoInventories"])).map(([id, inventory]) => [id, readPhotoInventory(inventory)]),
  );
  if (
    new Set(chapters.map((chapter) => chapter.id)).size !== chapters.length ||
    Object.keys(photoInventories).length !== chapters.length ||
    chapters.some((chapter) => !Object.hasOwn(photoInventories, chapter.id))
  )
    throw new Error("Missing chapter photo inventory");
  return {
    year,
    photoRoot: readText(fields["photoRoot"]),
    chapters,
    photoInventories,
    spreads: readArray(fields["spreads"]).map(readSpread),
    countries: readArray(fields["countries"]).map(readCountry),
  };
}

export function readChapterIdentity(value: unknown): ChapterSave {
  return { id: readText(readRecord(value)["id"]), scheduling: readScheduling(value) };
}

function readChapter(value: unknown): Chapter {
  const fields = readRecord(value);
  return {
    id: readText(fields["id"]),
    kind: readKind(fields["kind"], ["vacation", "home"]),
    name: readText(fields["name"]),
    countries: readArray(fields["countries"]).map(readText),
    start: readText(fields["start"]),
    end: readText(fields["end"]),
    sourceFolder: readText(fields["sourceFolder"]),
    description: readText(fields["description"]),
  };
}

function readSpread(value: unknown): Spread {
  const fields = readRecord(value);
  const kind = readKind(fields["kind"], ["album_start", "chapter_start", "photos_only"]);
  const geometry = { id: readText(fields["id"]), slots: readArray(fields["slots"]).map(readSlot) };
  if (kind !== "album_start") return { ...geometry, kind, chapter: readText(fields["chapter"]) };
  if (fields["chapter"] !== null) throw new Error("An album opening cannot belong to a chapter");
  return { ...geometry, kind, chapter: null };
}

function readSlot(value: unknown): Slot {
  const fields = readRecord(value);
  return {
    id: readText(fields["id"]),
    kind: readKind(fields["kind"], ["photo", "text"]),
    x: readNumber(fields["x"]),
    y: readNumber(fields["y"]),
    width: readNumber(fields["width"]),
    height: readNumber(fields["height"]),
  };
}

function readCountry(value: unknown): Country {
  const fields = readRecord(value);
  return { id: readText(fields["id"]), name: readText(fields["name"]), code: readText(fields["code"]) };
}

function readKind<T extends string>(value: unknown, allowed: readonly T[]): T {
  const kind = allowed.find((candidate) => candidate === value);
  if (kind === undefined) throw new Error("Unknown workspace kind");
  return kind;
}
