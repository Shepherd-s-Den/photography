import type { AlbumPhotoEvents, PhotoEvent } from "../model/Photo";
import { readRecord, readText } from "./Wire";

export function makeAlbumPhotoEvents(construct: (url: string) => EventTarget & Pick<EventSource, "close">): AlbumPhotoEvents {
  return {
    subscribe(albumId, receive) {
      const source = construct(`/api/albums/${encodeURIComponent(albumId)}/photo-events`);
      const subscribed: EventListener = (event) => {
        decode(event, false);
      };
      const changed: EventListener = (event) => {
        decode(event, true);
      };
      const interrupted = () => {
        receive({ kind: "interrupted" });
      };
      function decode(event: Event, isChange: boolean) {
        let notification: PhotoEvent;
        try {
          if (!(event instanceof MessageEvent) || typeof event.data !== "string") throw new Error("Invalid photo event");
          const value: unknown = JSON.parse(event.data);
          const fields = readRecord(value);
          if (!isChange) notification = { kind: "subscribed" };
          else {
            const chapterId = readText(fields["chapterId"]);
            if (chapterId.length === 0) throw new Error("Missing chapter identity");
            notification = { kind: "inventory-changed", chapterId };
          }
        } catch {
          notification = { kind: "invalid" };
        }
        receive(notification);
      }
      source.addEventListener("subscribed", subscribed);
      source.addEventListener("inventory-changed", changed);
      source.addEventListener("error", interrupted);
      return () => {
        source.removeEventListener("subscribed", subscribed);
        source.removeEventListener("inventory-changed", changed);
        source.removeEventListener("error", interrupted);
        source.close();
      };
    },
  };
}
