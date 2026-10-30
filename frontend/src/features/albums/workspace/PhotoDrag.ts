export type PhotoChoice = { readonly kind: "photo"; readonly photoId: string } | { readonly kind: "move"; readonly placementId: string };
export const photoDragType = "application/x-photography-photo";
export const photoDragFeedback = "Photo preview could not be prepared. Wait for the photo to load, or use keyboard selection.";

export function readPhotoChoice(text: string): PhotoChoice | undefined {
  try {
    const value: unknown = JSON.parse(text);
    if (typeof value !== "object" || value === null) return undefined;
    if ("kind" in value && value.kind === "photo" && "photoId" in value && typeof value.photoId === "string")
      return { kind: "photo", photoId: value.photoId };
    if ("kind" in value && value.kind === "move" && "placementId" in value && typeof value.placementId === "string")
      return { kind: "move", placementId: value.placementId };
    return undefined;
  } catch {
    return undefined;
  }
}

export function startPhotoDrag(
  event: Pick<DragEvent, "clientX" | "clientY" | "preventDefault"> & {
    readonly dataTransfer: Pick<DataTransfer, "setData" | "setDragImage" | "effectAllowed"> | null;
  },
  choice: PhotoChoice,
  source: { readonly image: HTMLImageElement | undefined; readonly frame: HTMLElement; readonly mirrored: boolean },
): boolean {
  const { image } = source;
  const frame = source.frame.getBoundingClientRect();
  if (event.dataTransfer === null || image === undefined || !image.complete || image.naturalWidth === 0 || frame.width <= 0 || frame.height <= 0) {
    event.preventDefault();
    return false;
  }
  const preview = document.createElement("canvas");
  preview.width = Math.max(1, Math.round(frame.width));
  preview.height = Math.max(1, Math.round(frame.height));
  preview.style.cssText = `position:fixed;left:-${String(preview.width + 8)}px;top:0;pointer-events:none;`;
  preview.setAttribute("aria-hidden", "true");
  try {
    const context = preview.getContext("2d");
    if (context === null) throw new Error("Canvas unavailable");
    const box = image.getBoundingClientRect();
    context.scale(preview.width / frame.width, preview.height / frame.height);
    context.translate(box.left - frame.left + (source.mirrored ? box.width : 0), box.top - frame.top);
    context.scale(source.mirrored ? -1 : 1, 1);
    context.drawImage(image, 0, 0, box.width, box.height);
    document.body.appendChild(preview);
    const x = Math.max(0, Math.min(preview.width, ((event.clientX - frame.left) * preview.width) / frame.width));
    const y = Math.max(0, Math.min(preview.height, ((event.clientY - frame.top) * preview.height) / frame.height));
    event.dataTransfer.setDragImage(preview, x, y);
    event.dataTransfer.setData(photoDragType, JSON.stringify(choice));
    event.dataTransfer.effectAllowed = "move";
    setTimeout(() => {
      preview.remove();
    }, 0);
    return true;
  } catch {
    preview.remove();
    event.preventDefault();
    return false;
  }
}
