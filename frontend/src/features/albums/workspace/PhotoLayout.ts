import type { Photo, PhotoInventory } from "../model/Photo";

export interface ChapterPhoto {
  readonly chapterId: string;
  readonly photo: Photo;
}
export interface PhotoRow {
  readonly height: number;
  readonly top: number;
  readonly items: readonly {
    readonly photo: ChapterPhoto;
    readonly width: number;
    readonly height: number;
    readonly left: number;
    readonly top: number;
  }[];
}
export interface PhotoRows {
  readonly height: number;
  readonly rows: readonly PhotoRow[];
}

export function derivePhotos(chapterIds: readonly string[], inventories: Readonly<Record<string, PhotoInventory>>) {
  const photos = chapterIds.flatMap((chapterId) => {
    const inventory = inventories[chapterId];
    return inventory?.photos.filter((photo) => !inventory.placedPhotoIds.includes(photo.id)).map((photo) => ({ chapterId, photo })) ?? [];
  });
  if (chapterIds.length > 1)
    photos.sort(
      (left, right) =>
        compareText(captureOrder(left.photo.capturedLocal), captureOrder(right.photo.capturedLocal)) ||
        compareText(left.photo.filename.toLowerCase(), right.photo.filename.toLowerCase()) ||
        compareText(left.photo.filename, right.photo.filename) ||
        compareText(left.chapterId, right.chapterId),
    );
  const counts = chapterIds.reduce(
    (sum, id) => {
      const chapterCounts = inventories[id]?.counts;
      return { unplaced: sum.unplaced + (chapterCounts?.unplaced ?? 0), total: sum.total + (chapterCounts?.total ?? 0) };
    },
    { unplaced: 0, total: 0 },
  );
  return { photos, counts };
}

export function derivePhotoRows(photos: readonly ChapterPhoto[], width: number): PhotoRows {
  const plans: RowPlan[] = [{ cost: 0, end: 0, row: [], previous: undefined }];
  for (const [index] of photos.entries()) {
    const end = index + 1;
    const best = plans.slice(-8).reduce<RowPlan>(
      (current, previous) => {
        const row = photos.slice(previous.end, end);
        const height = rowHeight(row, width);
        const cost =
          previous.cost + ((height - 110) / 110) ** 2 + 100 * (Math.max(75 - height, 0) / 75) ** 2 + 100 * (Math.max(height - 150, 0) / 150) ** 2;
        return cost < current.cost ? { cost, end, row, previous } : current;
      },
      { cost: Infinity, end, row: [], previous: undefined },
    );
    plans.push(best);
  }
  const orderedRows: (readonly ChapterPhoto[])[] = [];
  for (let plan = plans.at(-1); plan?.previous !== undefined; plan = plan.previous) {
    orderedRows.unshift(plan.row);
  }
  let top = 0;
  const rows = orderedRows.map((row) => {
    const positioned = positionRow(row, width, top);
    top += positioned.height + 3;
    return positioned;
  });
  return { rows, height: Math.max(0, top - 3) };
}

export function limitPhotoWidth(width: number, available: number): number {
  return Math.max(200, Math.min(Math.max(200, available - 372), width));
}

interface RowPlan {
  readonly cost: number;
  readonly end: number;
  readonly row: readonly ChapterPhoto[];
  readonly previous: RowPlan | undefined;
}

function rowHeight(row: readonly ChapterPhoto[], width: number): number {
  return (
    (width - (row.length - 1) * 3) /
    row.reduce((sum, photo) => sum + (photo.photo.thumbnail.width / photo.photo.thumbnail.height) * heightScale(photo), 0)
  );
}

function positionRow(row: readonly ChapterPhoto[], width: number, top: number): PhotoRow {
  const base = rowHeight(row, width);
  const height = base * Math.max(...row.map(heightScale));
  let left = 0;
  const items = row.map((photo) => {
    const imageHeight = base * heightScale(photo);
    const imageWidth = (imageHeight * photo.photo.thumbnail.width) / photo.photo.thumbnail.height;
    const positioned = { photo, width: imageWidth, height: imageHeight, left, top: top + (height - imageHeight) / 2 };
    left += imageWidth + 3;
    return positioned;
  });
  return { top, height, items };
}

function heightScale(photo: ChapterPhoto): number {
  const { width, height } = photo.photo.thumbnail;
  return width < height ? 1.12 : width > height ? 0.96 : 1;
}

function captureOrder(value: string | null): string {
  if (value === null) return "\uffff";
  const seconds = value.length === 16 ? `${value}:00` : value;
  return seconds.includes(".") ? seconds.padEnd(29, "0") : `${seconds}.000000000`;
}

function compareText(left: string, right: string): number {
  return left < right ? -1 : left > right ? 1 : 0;
}
