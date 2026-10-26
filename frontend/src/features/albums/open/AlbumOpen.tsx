import { Show, createMemo, createResource } from "solid-js";
import type { AlbumClient } from "../model/Album";
import { isMissingAlbum } from "../model/Presentation";
import { AlbumWorkspace } from "../workspace/AlbumWorkspace";

export function AlbumOpen(props: { client: Pick<AlbumClient, "open" | "workspace" | "createChapter" | "updateChapter">; id: string }) {
  const load = async (id: string) => {
    const [metadata, workspace] = await Promise.all([props.client.open(id), props.client.workspace(id)]);
    return { metadata, workspace };
  };
  const [album, { refetch, mutate }] = createResource(() => props.id, load);
  const missing = createMemo(() => isMissingAlbum(album.error));
  async function refreshWorkspace() {
    const refreshed = await load(props.id);
    mutate(refreshed);
  }
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
          {(loaded) => <AlbumWorkspace album={loaded().metadata} workspace={loaded().workspace} client={props.client} onRefresh={refreshWorkspace} />}
        </Show>
      </Show>
    </>
  );
}
