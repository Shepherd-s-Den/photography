import { Show, createMemo, createResource } from "solid-js";
import type { AlbumClient } from "../model/Album";
import { isMissingAlbum } from "../model/Presentation";

export function AlbumOpen(props: { client: AlbumClient; id: string }) {
  const [album, { refetch }] = createResource(
    () => props.id,
    (id) => props.client.open(id),
  );
  const missing = createMemo(() => isMissingAlbum(album.error));
  return (
    <>
      <a class="back-link" href="/">
        Back to albums
      </a>
      <Show when={album.loading}>
        <div role="status" aria-label="Loading album" class="loading-placeholder" />
      </Show>
      <Show when={album.error !== undefined}>
        <div role="alert">
          <Show
            when={missing()}
            fallback={
              <>
                The album could not be loaded.{" "}
                <button
                  type="button"
                  onClick={() => {
                    void refetch();
                  }}
                >
                  Retry album
                </button>
              </>
            }
          >
            This album could not be found.
          </Show>
        </div>
      </Show>
      <Show when={!album.loading && album.error === undefined}>
        <Show when={album()}>
          {(loaded) => (
            <article class="album-open">
              <h1>{loaded().title}</h1>
              <p>{loaded().synopsis}</p>
            </article>
          )}
        </Show>
      </Show>
    </>
  );
}
