import { describe, expect, it } from "vitest";
import { boundTransform, cropImage, panTransform } from "../../../src/features/albums/workspace/PlacementCrop";

describe("fill crop", () => {
  it("fills landscape and portrait slots and bounds both pan axes without changing mirror", () => {
    const transform = { x: 900, y: -900, zoom: 2, mirrored: true };

    const bounded = boundTransform({ width: 400, height: 200 }, { width: 200, height: 200 }, transform);
    const crop = cropImage({ width: 400, height: 200 }, { width: 200, height: 200 }, bounded);

    expect(bounded).toEqual({ x: 300, y: -100, zoom: 2, mirrored: true });
    expect(crop).toEqual({ width: 800, height: 400, left: -600, top: -200 });
  });

  it.each([false, true])("preserves the visible source crop when mirrored %s and pans in screen coordinates", (mirrored) => {
    const transform = { x: 30, y: 20, zoom: 2, mirrored };
    const photo = { width: 400, height: 200 };
    const slot = { width: 200, height: 200 };

    const crop = cropImage(photo, slot, transform);
    const panned = panTransform(transform, { x: 10, y: -10 });
    const displayed = cropImage(photo, slot, panned);

    expect(crop.left).toBe(mirrored ? -330 : -270);
    expect(displayed.left - crop.left).toBe(10);
    expect(displayed.top - crop.top).toBe(-10);
  });

  it.each([0, 6, NaN, Infinity])("rejects invalid zoom %s", (zoom) => {
    expect(() => boundTransform({ width: 100, height: 200 }, { width: 200, height: 100 }, { x: 0, y: 0, zoom, mirrored: false })).toThrow();
  });
});
