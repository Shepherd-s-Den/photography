import { describe, expect, it } from "vitest";
import { describePeriod, isCalendarDay, suggestFolder, validateChapter } from "../../../src/features/albums/workspace/ChapterDetails";
import { findSelection, findReaderNavigation } from "../../../src/features/albums/workspace/WorkspaceSelection";
import { getChapterCapacityReason } from "../../../src/features/albums/workspace/ChapterOutline";
import { getCoverCredits } from "../../../src/features/albums/workspace/Canvas";
import { searchCountries } from "../../../src/features/albums/workspace/CountryPicker";
import { readWorkspace } from "../../../src/features/albums/api/WorkspaceWire";

describe("chapter detail feedback", () => {
  it.each([
    ["2000-02-29", true],
    ["1900-02-29", false],
    ["2026-00-01", false],
    ["2026-13-01", false],
    ["2026-01-00", false],
    ["2026-01-32", false],
  ])("should recognize calendar day %s without timezone conversion", (value, valid) => {
    expect(isCalendarDay(value)).toBe(valid);
  });
  it.each([
    ["2026-04-02", "2026-04-02", undefined],
    ["2025-12-31", "2026-01-01", undefined],
    ["2024-02-29", "2024-03-01", undefined],
    ["2026-02-29", "2026-03-01", "Enter valid calendar dates."],
    ["2026-04-31", "2026-05-01", "Enter valid calendar dates."],
    ["2026-04-03", "2026-04-02", "Last day must not precede first day."],
    ["", "2026-01-01", "Enter valid calendar dates."],
  ])("should validate the inclusive calendar period %s to %s", (start, end, expected) => {
    const draft = { kind: "vacation" as const, name: "Kyoto", countries: ["jp"], start, end, sourceFolder: "/photos/2026/selection/Kyoto" };

    expect(validateChapter(draft)).toBe(expected);
  });

  it.each([
    [{ kind: "home" as const, countries: ["ro"], sourceFolder: "/photos/Home" }, undefined],
    [{ kind: "home" as const, countries: [], sourceFolder: "/photos/Home" }, "Select at least one country."],
    [{ kind: "home" as const, countries: ["ro"], sourceFolder: "relative" }, "Enter an absolute photo folder without parent traversal."],
    [{ kind: "home" as const, countries: ["ro"], sourceFolder: "/photos/../Home" }, "Enter an absolute photo folder without parent traversal."],
    [{ kind: "home" as const, countries: ["ro"], sourceFolder: "" }, "Enter an absolute photo folder without parent traversal."],
    ...["/", "/photos//Kyoto", "/photos/./Kyoto", "/photos/Kyoto/", "/photos\\Kyoto", "/photos/\0Kyoto"].map(
      (sourceFolder) =>
        [{ kind: "home" as const, countries: ["ro"], sourceFolder }, "Enter an absolute photo folder without parent traversal."] as const,
    ),
    [
      { kind: "vacation" as const, name: " ", countries: ["ro"], start: "2026-01-01", end: "2026-01-01", sourceFolder: "/photos/a" },
      "Enter a location / name.",
    ],
    [
      { kind: "vacation" as const, name: " Home ", countries: ["ro"], start: "2026-01-01", end: "2026-01-01", sourceFolder: "/photos/a" },
      "Choose a vacation name other than Home.",
    ],
  ] as const)("should give chapter input feedback for %j", (draft, expected) => {
    expect(validateChapter(draft)).toBe(expected);
  });

  it("should derive full paths and calendar display without changing nominal years", () => {
    expect(suggestFolder("/mnt/photos/", 2026, " Kyoto ")).toBe("/mnt/photos/2026/selection/Kyoto");
    expect(suggestFolder("/", 2026, "Home")).toBe("/2026/selection/Home");
    expect(describePeriod({ kind: "home", start: "2026-01-01", end: "2026-12-31" })).toBe("Throughout the year");
    expect(describePeriod({ kind: "vacation", start: "2025-12-31", end: "2026-01-01" })).toBe("31/12/2025 - 01/01/2026");
  });
});

describe("country picker matching", () => {
  it("should search all supplied countries by name or code, including no matches", () => {
    const countries = [
      { id: "ro", name: "Romania", code: "RO" },
      { id: "jp", name: "Japan", code: "JP" },
    ];

    expect(searchCountries(countries, " jp ")).toEqual([countries[1]]);
    expect(searchCountries(countries, "ROM")).toEqual([countries[0]]);
    expect(searchCountries(countries, "")).toEqual(countries);
    expect(searchCountries(countries, "Atlantis")).toEqual([]);
  });
});

describe("chapter outline capacity", () => {
  it.each([
    [64, undefined],
    [65, "This album has 65 interior spreads. No more chapters can be added."],
  ] as const)("should explain chapter creation availability at %i spreads", (count, expected) => {
    expect(getChapterCapacityReason(count)).toBe(expected);
  });
});

describe("cover credits", () => {
  it.each([
    [2025, "by Cosmin & Mara"],
    [2026, "by Cosmin, Mara & Iacob"],
  ] as const)("should credit the family for %i", (year, expected) => {
    expect(getCoverCredits(year)).toBe(expected);
  });
});

describe("workspace URL selection", () => {
  it("should resolve URL parts through persisted openings and spread ownership without inventing missing values", () => {
    const spreads = [
      { id: "album-opening", chapter: null, kind: "album_start" as const, slots: [] },
      { id: "chapter-opening", chapter: "chapter", kind: "chapter_start" as const, slots: [] },
      { id: "photo", chapter: "chapter", kind: "photos_only" as const, slots: [] },
    ];

    expect(findSelection(spreads, null)).toEqual({ kind: "spread", spread: spreads[0] });
    expect(findSelection(spreads, "cover")).toEqual({ kind: "cover" });
    expect(findSelection(spreads, "chapter")).toEqual({ kind: "spread", spread: spreads[1] });
    expect(findSelection(spreads, "photo")).toEqual({ kind: "spread", spread: spreads[2] });
    expect(findSelection(spreads, "foreign")).toEqual({ kind: "invalid" });
    expect(findSelection([], null)).toEqual({ kind: "invalid" });
  });

  it.each([
    ["cover", undefined, null],
    [null, "cover", "chapter-opening"],
    ["chapter", null, "photo"],
    ["photo", "chapter-opening", "home-opening"],
    ["home", "photo", undefined],
    ["foreign", undefined, undefined],
  ] as const)("should traverse persisted Reader order from %s without wrapping", (part, previous, next) => {
    const spreads = [
      { id: "album-opening", chapter: null, kind: "album_start" as const, slots: [] },
      { id: "chapter-opening", chapter: "chapter", kind: "chapter_start" as const, slots: [] },
      { id: "photo", chapter: "chapter", kind: "photos_only" as const, slots: [] },
      { id: "home-opening", chapter: "home", kind: "chapter_start" as const, slots: [] },
    ];

    expect(findReaderNavigation(spreads, findSelection(spreads, part))).toEqual({ previous, next });
  });

  it("should navigate an album without chapters from its opening to Cover", () => {
    const spreads = [{ id: "opening", chapter: null, kind: "album_start" as const, slots: [] }];

    expect(findReaderNavigation(spreads, findSelection(spreads, null))).toEqual({ previous: "cover", next: undefined });
  });
});

describe("workspace wire representations", () => {
  const chapter = {
    id: "chapter",
    kind: "vacation",
    name: "Kyoto",
    countries: ["jp"],
    start: "2026-04-02",
    end: "2026-04-14",
    sourceFolder: "/photos/Kyoto",
    description: "",
  };
  const slot = { id: "slot", kind: "photo", x: 2, y: 3, width: 24, height: 40 };
  const spread = { id: "opening", chapter: null, kind: "album_start", slots: [slot] };
  const workspace = {
    year: 2026,
    photoRoot: "/photos",
    chapters: [chapter],
    spreads: [spread],
    countries: [{ id: "jp", name: "Japan", code: "JP" }],
  };

  const homeWorkspace = {
    ...workspace,
    chapters: [{ ...chapter, kind: "home", name: "Home" }],
    spreads: [{ ...spread, chapter: "chapter", kind: "chapter_start", slots: [{ ...slot, kind: "text" }] }],
  };
  const photoWorkspace = { ...workspace, spreads: [{ ...spread, chapter: chapter.id, kind: "photos_only" }] };

  it.each([
    ["album_start", workspace],
    ["chapter_start", homeWorkspace],
    ["photos_only", photoWorkspace],
  ] as const)("should decode complete saved metadata, country catalog and exact %s geometry", (_kind, expectedWorkspace) => {
    const actualWorkspace = readWorkspace(expectedWorkspace);

    expect(actualWorkspace).toEqual(expectedWorkspace);
  });

  it.each([
    { ...workspace, year: undefined },
    { ...workspace, year: 2026.5 },
    { ...workspace, year: 1899 },
    { ...workspace, year: 2101 },
    { ...workspace, countries: null },
    { ...workspace, photoRoot: undefined },
    { ...workspace, chapters: [{ ...chapter, kind: "unknown" }] },
    { ...workspace, spreads: [{ ...spread, kind: "unknown" }] },
    { ...workspace, spreads: [{ ...spread, chapter: chapter.id }] },
    { ...workspace, spreads: [{ ...spread, kind: "chapter_start", chapter: null }] },
    { ...workspace, spreads: [{ ...spread, kind: "photos_only", chapter: null }] },
    { ...workspace, spreads: [{ ...spread, slots: [{ ...slot, kind: "unknown" }] }] },
    { ...workspace, spreads: [{ ...spread, slots: [{ ...slot, x: Number.NaN }] }] },
  ])("should reject incomplete or unknown workspace representations %j", (value) => {
    expect(() => readWorkspace(value)).toThrow();
  });
});
