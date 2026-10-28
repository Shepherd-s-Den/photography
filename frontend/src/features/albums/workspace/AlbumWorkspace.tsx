import { Show, createMemo, createSignal, onCleanup, untrack } from "solid-js";
import type { Album, AlbumClient } from "../model/Album";
import type { Chapter, Workspace } from "../model/Workspace";
import type { ChapterSave, Scheduling } from "../model/Photo";
import { PhotoPane } from "./PhotoPane";
import { ChapterDialog } from "./ChapterDialog";
import { ChapterOutline, getChapterCapacityReason } from "./ChapterOutline";
import { ChapterMetadata } from "./ChapterMetadata";
import { CoverCanvas, SpreadCanvas, coverAspectRatio, spreadAspectRatio } from "./Canvas";
import { FullscreenReader } from "./FullscreenReader";
import { findReaderNavigation, findSelection } from "./WorkspaceSelection";
import "./workspace.css";

export function AlbumWorkspace(props: {
  album: Pick<Album, "id" | "title" | "synopsis">;
  workspace: Workspace;
  client: Pick<AlbumClient, "createChapter" | "updateChapter">;
  onRefresh: () => Promise<void>;
  onSaved: (result: ChapterSave) => Promise<void>;
  workspaceError: unknown;
  photoErrors: Readonly<Record<string, string>>;
  scheduling: Readonly<Record<string, Scheduling | "pending">>;
  liveStatus: "connecting" | "catching-up" | "live" | "interrupted" | "invalid";
  onPhotoRefresh: (ids: readonly string[]) => Promise<void>;
  onInventoryRetry: (id: string) => void;
}) {
  const readPart = () => new URL(window.location.href).searchParams.get("part");
  const [part, setPart] = createSignal(readPart());
  const [interior, setInterior] = createSignal<string | null>(untrack(() => (part() === "cover" ? null : part())));
  const [dialog, setDialog] = createSignal<{ chapter: Chapter | undefined; opener: HTMLElement }>();
  const [refreshing, setRefreshing] = createSignal(false);
  const [refreshAfterSave, setRefreshAfterSave] = createSignal(false);
  const [readerEntry, setReaderEntry] = createSignal<HTMLDivElement>();
  const selection = createMemo(() => findSelection(props.workspace.spreads, part()));
  const selectedSpread = createMemo(() => {
    const selected = selection();
    return selected.kind === "spread" ? selected.spread : undefined;
  });
  const chapter = createMemo(() => props.workspace.chapters.find((candidate) => candidate.id === selectedSpread()?.chapter));
  const photoChapters = createMemo(() => {
    const owner = chapter();
    return owner === undefined ? props.workspace.chapters : [owner];
  });
  const label = createMemo(() => chapter()?.name ?? "Synopsis");
  const readerLabel = createMemo(() =>
    selection().kind === "invalid"
      ? ""
      : part() === "cover"
        ? "Cover"
        : selectedSpread()?.kind === "photos_only"
          ? `${label()} · Photo spread`
          : label(),
  );
  const navigation = createMemo(() => findReaderNavigation(props.workspace.spreads, selection()));
  const refreshReason = createMemo(() =>
    refreshing()
      ? "Refreshing saved workspace. Please wait."
      : props.workspaceError !== undefined
        ? "Reload the saved workspace before editing chapters."
        : undefined,
  );
  const creationReason = createMemo(() => refreshReason() ?? getChapterCapacityReason(props.workspace.spreads.length));
  let workspaceElement: HTMLElement;
  let refreshGeneration = 0;
  let active = true;

  function syncHistory() {
    const next = readPart();
    setPart(next);
    if (next !== "cover") setInterior(next);
  }
  window.addEventListener("popstate", syncHistory);
  onCleanup(() => {
    active = false;
    refreshGeneration++;
    window.removeEventListener("popstate", syncHistory);
  });

  function select(next: string | null) {
    const url = new URL(window.location.href);
    if (next === null) url.searchParams.delete("part");
    else url.searchParams.set("part", next);
    if (part() !== next) window.history.pushState(null, "", url);
    setPart(next);
    if (next !== "cover") setInterior(next);
  }

  async function refresh(result?: ChapterSave) {
    const generation = ++refreshGeneration;
    setRefreshAfterSave(result !== undefined);
    setRefreshing(true);
    try {
      if (result === undefined) await props.onRefresh();
      else await props.onSaved(result);
    } finally {
      if (generation === refreshGeneration) setRefreshing(false);
    }
  }

  function saved(result: ChapterSave, action: { chapter: Chapter | undefined; opener: HTMLElement }) {
    if (!active) return;
    const opener = action.opener;
    setDialog(undefined);
    if (action.chapter === undefined) select(result.id);
    void refresh(result).then(() => {
      if (opener instanceof HTMLButtonElement && opener.isConnected && !opener.disabled) opener.focus();
      else workspaceElement.focus();
    });
  }

  return (
    <section
      ref={(element) => {
        workspaceElement = element;
      }}
      tabindex="-1"
      class="workspace"
      aria-label="Album workspace"
    >
      <header class="workspace-heading">
        <h1>A year in photographs / {props.workspace.year}</h1>
        <div class="workspace-controls" role="group" aria-label="Workspace controls">
          <div ref={setReaderEntry} />
          <div class="workspace-mode" role="group" aria-label="Canvas view">
            <button
              type="button"
              aria-pressed={part() !== "cover"}
              onClick={() => {
                select(interior());
              }}
            >
              Spreads
            </button>
            <button
              type="button"
              aria-pressed={part() === "cover"}
              onClick={() => {
                select("cover");
              }}
            >
              Cover
            </button>
          </div>
        </div>
      </header>
      <Show when={refreshing()}>
        <p role="status">Refreshing saved workspace…</p>
      </Show>
      <Show when={props.workspaceError !== undefined}>
        <div role="alert">
          {refreshAfterSave()
            ? "Chapter saved, but the workspace could not be refreshed."
            : "Saved workspace could not be refreshed. Displayed photos may be out of date."}{" "}
          <button
            type="button"
            onClick={() => {
              void refresh();
            }}
          >
            Retry workspace
          </button>
        </div>
      </Show>
      <PhotoPane
        label={chapter() === undefined ? "Album photos" : "Chapter photos"}
        chapters={photoChapters()}
        inventories={props.workspace.photoInventories}
        inventoryErrors={props.photoErrors}
        scheduling={props.scheduling}
        liveStatus={props.liveStatus}
        onRefresh={() => {
          void props.onPhotoRefresh(photoChapters().map((owner) => owner.id));
        }}
        onRetryInventory={props.onInventoryRetry}
        onRetryRefresh={(id) => {
          void props.onPhotoRefresh([id]);
        }}
      >
        <div class="workspace-editor">
          <Show when={part() !== "cover"}>
            <ChapterOutline
              albumId={props.album.id}
              chapters={props.workspace.chapters}
              spreads={props.workspace.spreads}
              part={part()}
              creationReason={creationReason()}
              onSelect={select}
              onCreate={(opener) => {
                setDialog({ chapter: undefined, opener });
              }}
            />
          </Show>
          <article class="workspace-preview" aria-label="Selected part">
            <Show when={props.workspaceError === undefined}>
              <Show when={!refreshing() && selection().kind === "invalid"}>
                <div role="alert">
                  This album part could not be found.{" "}
                  <button
                    type="button"
                    onClick={() => {
                      select(null);
                    }}
                  >
                    Go to Synopsis
                  </button>
                </div>
              </Show>
              <FullscreenReader
                aspectRatio={part() === "cover" ? coverAspectRatio : spreadAspectRatio}
                label={readerLabel()}
                entryMount={readerEntry()}
                unavailable={selection().kind === "invalid" ? "Choose an available album part to enter Reader." : refreshReason()}
                canPrevious={navigation().previous !== undefined}
                canNext={navigation().next !== undefined}
                onNavigate={(direction) => {
                  const next = direction === -1 ? navigation().previous : navigation().next;
                  /* v8 ignore next -- Endpoint controls and keyboard guards prevent unavailable navigation. */
                  if (next !== undefined) select(next);
                }}
              >
                <Show when={part() === "cover"}>
                  <CoverCanvas title={props.album.title} year={props.workspace.year} />
                </Show>
                <Show when={selectedSpread()}>
                  {(spread) => (
                    <SpreadCanvas
                      slots={spread().slots}
                      title={chapter()?.name ?? props.album.title}
                      text={chapter()?.description ?? props.album.synopsis}
                      label={label()}
                    />
                  )}
                </Show>
              </FullscreenReader>
              <Show when={chapter()}>
                {(owner) => (
                  <ChapterMetadata
                    chapter={owner()}
                    countries={props.workspace.countries}
                    unavailable={refreshReason()}
                    onEdit={(opener) => {
                      setDialog({ chapter: owner(), opener });
                    }}
                  />
                )}
              </Show>
            </Show>
          </article>
        </div>
      </PhotoPane>
      <Show when={dialog()} keyed>
        {(action) => (
          <ChapterDialog
            save={(details) =>
              action.chapter === undefined
                ? props.client.createChapter(props.album.id, details)
                : props.client.updateChapter(props.album.id, action.chapter.id, details)
            }
            year={props.workspace.year}
            photoRoot={props.workspace.photoRoot}
            countries={props.workspace.countries}
            homeAvailable={!props.workspace.chapters.some((item) => item.kind === "home")}
            chapter={action.chapter}
            onSaved={(result) => {
              saved(result, action);
            }}
            onClose={() => {
              setDialog(undefined);
            }}
            returnFocus={() => {
              action.opener.focus();
            }}
          />
        )}
      </Show>
    </section>
  );
}
