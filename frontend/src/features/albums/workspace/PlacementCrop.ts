import type { PhotoTransform } from "../model/Placement";

interface Dimensions {
  readonly width: number;
  readonly height: number;
}

export function boundTransform(photo: Dimensions, slot: Dimensions, transform: PhotoTransform): PhotoTransform {
  if (![transform.x, transform.y, transform.zoom].every(Number.isFinite) || transform.zoom < 1 || transform.zoom > 5)
    throw new Error("Invalid photo transform");
  const scale = Math.max(slot.width / photo.width, slot.height / photo.height) * transform.zoom;
  const maxX = Math.max(0, (photo.width * scale - slot.width) / 2);
  const maxY = Math.max(0, (photo.height * scale - slot.height) / 2);
  return { ...transform, x: Math.max(-maxX, Math.min(maxX, transform.x)), y: Math.max(-maxY, Math.min(maxY, transform.y)) };
}

export function cropImage(photo: Dimensions, slot: Dimensions, transform: PhotoTransform) {
  const bounded = boundTransform(photo, slot, transform);
  const scale = Math.max(slot.width / photo.width, slot.height / photo.height) * bounded.zoom;
  const width = photo.width * scale;
  const height = photo.height * scale;
  return { width, height, left: (slot.width - width) / 2 + (bounded.mirrored ? -bounded.x : bounded.x), top: (slot.height - height) / 2 + bounded.y };
}

export function panTransform(transform: PhotoTransform, delta: { readonly x: number; readonly y: number }): PhotoTransform {
  return { ...transform, x: transform.x + (transform.mirrored ? -delta.x : delta.x), y: transform.y + delta.y };
}
