export const processingStates = ["idle", "queued", "running", "completed", "failed"] as const;
export const issueStages = ["discovery", "read", "generation", "storage", "persistence", "cleanup", "scheduling"] as const;
export const issueCodes = [
  "unreadable_source",
  "undecodable",
  "unstable",
  "encoding_failed",
  "storage_failed",
  "persistence_failed",
  "cleanup_failed",
  "scheduling_failed",
] as const;
export const schedulingStates = ["queued", "coalesced", "not-requested", "failed"] as const;
export type Scheduling = (typeof schedulingStates)[number];
export interface ChapterSave {
  readonly id: string;
  readonly scheduling: Scheduling;
}
export interface Photo {
  readonly id: string;
  readonly filename: string;
  readonly capturedLocal: string | null;
  readonly width: number;
  readonly height: number;
  readonly caption: string | null;
  readonly thumbnail: { readonly url: string; readonly width: number; readonly height: number };
}
export interface PhotoInventory {
  readonly photos: readonly Photo[];
  readonly placedPhotoIds: readonly string[];
  readonly counts: { readonly unplaced: number; readonly total: number };
  readonly processing: {
    readonly state: (typeof processingStates)[number];
    readonly unsupportedCount: number;
    readonly skippedCount: number;
    readonly issues: readonly {
      readonly stage: (typeof issueStages)[number];
      readonly code: (typeof issueCodes)[number];
      readonly filename: string | null;
    }[];
  };
}
export type PhotoEvent =
  { readonly kind: "subscribed" | "interrupted" | "invalid" } | { readonly kind: "inventory-changed"; readonly chapterId: string };
export interface AlbumPhotoEvents {
  readonly subscribe: (albumId: string, receive: (event: PhotoEvent) => void) => () => void;
}
