import type { Chapter, Country, Slot, Spread, SpreadTemplate } from "../model/Workspace";
import { readArray, readNumber, readRecord, readText } from "./Wire";
import { readScheduling } from "./PhotoWire";
import type { ChapterSave } from "../model/Photo";

export function readChapterIdentity(value: unknown): ChapterSave {
  return { id: readText(readRecord(value)["id"]), scheduling: readScheduling(value) };
}

export function readChapter(value: unknown): Chapter {
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

export function readSpread(value: unknown): Spread {
  const fields = readRecord(value);
  const kind = readKind(fields["kind"], ["album_start", "chapter_start", "photos_only"]);
  const geometry = { id: readText(fields["id"]), templateId: readText(fields["templateId"]), slots: readArray(fields["slots"]).map(readSlot) };
  if (kind !== "album_start") return { ...geometry, kind, chapter: readText(fields["chapter"]) };
  if (fields["chapter"] !== null) throw new Error("An album opening cannot belong to a chapter");
  return { ...geometry, kind, chapter: null };
}

export function readTemplates(value: unknown): readonly SpreadTemplate[] {
  return readArray(readRecord(value)["templates"]).map((entry) => {
    const fields = readRecord(entry);
    const slots = readArray(fields["slots"]).map(readGeometry);
    if (!slots.some((slot) => slot.kind === "photo")) throw new Error("A spread template must contain a photo slot");
    return { id: readText(fields["id"]), name: readText(fields["name"]), slots };
  });
}

function readSlot(value: unknown): Slot {
  const fields = readRecord(value);
  return { id: readText(fields["id"]), ...readGeometry(value) };
}

function readGeometry(value: unknown): Omit<Slot, "id"> {
  const fields = readRecord(value);
  return {
    kind: readKind(fields["kind"], ["photo", "text"]),
    x: readNumber(fields["x"]),
    y: readNumber(fields["y"]),
    width: readNumber(fields["width"]),
    height: readNumber(fields["height"]),
  };
}

export function readCountry(value: unknown): Country {
  const fields = readRecord(value);
  return { id: readText(fields["id"]), name: readText(fields["name"]), code: readText(fields["code"]) };
}

function readKind<T extends string>(value: unknown, allowed: readonly T[]): T {
  const kind = allowed.find((candidate) => candidate === value);
  if (kind === undefined) throw new Error("Unknown workspace kind");
  return kind;
}
