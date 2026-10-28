export interface Country {
  readonly id: string;
  readonly name: string;
  readonly code: string;
}

export interface Chapter {
  readonly id: string;
  readonly kind: "vacation" | "home";
  readonly name: string;
  readonly countries: readonly string[];
  readonly start: string;
  readonly end: string;
  readonly sourceFolder: string;
  readonly description: string;
}

export type ChapterDetails =
  | {
      readonly kind: "vacation";
      readonly name: string;
      readonly countries: readonly string[];
      readonly start: string;
      readonly end: string;
      readonly sourceFolder: string;
    }
  | { readonly kind: "home"; readonly countries: readonly string[]; readonly sourceFolder: string };

export interface Slot {
  readonly id: string;
  readonly kind: "photo" | "text";
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

export type Spread = {
  readonly id: string;
  readonly slots: readonly Slot[];
} & ({ readonly kind: "album_start"; readonly chapter: null } | { readonly kind: "chapter_start" | "photos_only"; readonly chapter: string });

export interface Workspace {
  readonly photoInventories: Readonly<Record<string, PhotoInventory>>;
  readonly year: number;
  readonly photoRoot: string;
  readonly chapters: readonly Chapter[];
  readonly spreads: readonly Spread[];
  readonly countries: readonly Country[];
}
import type { PhotoInventory } from "./Photo";
