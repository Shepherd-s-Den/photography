import { Show, createMemo, createSignal, onCleanup, untrack } from "solid-js";
import { SpreadOccupiedError, type Album, type AlbumClient } from "../model/Album";
import { describeFailure } from "../model/Presentation";
import type { Chapter, Workspace } from "../model/Workspace";
import type { ChapterSave, Scheduling } from "../model/Photo";
import { PhotoPane } from "./PhotoPane";
import { ChapterDialog } from "./ChapterDialog";
import { ChapterOutline, getChapterCapacityReason } from "./ChapterOutline";
import { ChapterMetadata } from "./ChapterMetadata";
import { CoverCanvas, SpreadCanvas, coverAspectRatio, spreadAspectRatio } from "./Canvas";
import { FullscreenReader } from "./FullscreenReader";
import { findDeletionSelection, findReaderNavigation, findSelection, findSpreadNavigation, findSpreadPosition } from "./WorkspaceSelection";
import "./workspace.css";
import { TemplateSelector } from "./TemplateSelector";
import { SpreadConfirmation } from "./SpreadConfirmation";

type SpreadAction = { opener: HTMLButtonElement; spreadId: string } & (
  | { kind: "add"; chapterId: string }
  | { kind: "move"; direction: "earlier" | "later" }
  | { kind: "template"; templateId: string }
  | { kind: "delete"; index: number }
);
type SpreadDestination = { kind: "part"; part: string | null } | { kind: "index"; index: number };

export function AlbumWorkspace(props: {
  album: Pick<Album, "id" | "title" | "synopsis">;
  workspace: Workspace;
  client: Pick<AlbumClient, "createChapter" | "updateChapter" | "templates" | "addSpread" | "moveSpread" | "changeSpreadTemplate" | "deleteSpread">;
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
  const [spreadPending, setSpreadPending] = createSignal(false);
  const [spreadError, setSpreadError] = createSignal<string>();
  const [failedAction, setFailedAction] = createSignal<SpreadAction>();
  const [confirmation, setConfirmation] = createSignal<{ action: SpreadAction; placements: number }>();
  const [reloadTarget, setReloadTarget] = createSignal<{ destination: SpreadDestination; opener: HTMLButtonElement }>();
  const selection = createMemo(() => findSelection(props.workspace.spreads, part()));
  const selectedSpread = createMemo(() => {
    const selected = selection();
    return selected.kind === "spread" ? selected.spread : undefined;
  });
  const chapter = createMemo(() => props.workspace.chapters.find((candidate) => candidate.id === selectedSpread()?.chapter));
  const spreadPosition = createMemo(() => {
    const spread = selectedSpread();
    return spread === undefined ? undefined : findSpreadPosition(props.workspace.spreads, spread);
  });
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
  const spreadNavigation = createMemo(() => findSpreadNavigation(props.workspace.spreads, selection()));
  const refreshReason = createMemo(() =>
    spreadPending()
      ? "Saving spread. Please wait."
      : refreshing()
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
  document.addEventListener("keydown", navigateSpread);
  onCleanup(() => {
    active = false;
    refreshGeneration++;
    window.removeEventListener("popstate", syncHistory);
    document.removeEventListener("keydown", navigateSpread);
  });

  function navigateSpread(event: KeyboardEvent) {
    if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey || document.fullscreenElement) return;
    if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
    if (event.target instanceof HTMLElement && event.target.closest("input, textarea, select, [contenteditable], dialog") !== null) return;
    if (refreshReason() !== undefined || part() === "cover") return;
    event.preventDefault();
    const destination = event.key === "ArrowLeft" ? spreadNavigation().previous : spreadNavigation().next;
    if (destination !== undefined) select(destination);
  }

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

  function restoreFocus(opener: HTMLButtonElement) {
    queueMicrotask(() => {
      if (opener.isConnected && !opener.disabled) opener.focus();
      else workspaceElement.focus();
    });
  }

  async function reloadSpread() {
    const target = reloadTarget();
    /* v8 ignore next -- Retry is rendered only with a saved mutation target. */
    if (target === undefined) return;
    setSpreadError(undefined);
    await refresh();
    if (!active) return;
    if (props.workspaceError !== undefined) {
      workspaceElement.focus();
      return;
    }
    const destination =
      target.destination.kind === "part" ? target.destination.part : findDeletionSelection(props.workspace.spreads, target.destination.index);
    if (destination === undefined) {
      setSpreadError("The saved workspace has no selectable spread. Reload the workspace.");
      workspaceElement.focus();
      return;
    }
    select(destination);
    setSpreadError(undefined);
    setReloadTarget(undefined);
    restoreFocus(target.opener);
  }

  async function mutateSpread(action: SpreadAction, confirmPlacements = false) {
    setSpreadPending(true);
    setSpreadError(undefined);
    setFailedAction(undefined);
    try {
      let destination: SpreadDestination = { kind: "part", part: part() };
      if (action.kind === "add")
        destination = { kind: "part", part: await props.client.addSpread(props.album.id, action.chapterId, action.spreadId) };
      else if (action.kind === "move") await props.client.moveSpread(props.album.id, action.spreadId, action.direction);
      else if (action.kind === "template")
        await props.client.changeSpreadTemplate(props.album.id, action.spreadId, action.templateId, confirmPlacements);
      else {
        await props.client.deleteSpread(props.album.id, action.spreadId, confirmPlacements);
        destination = { kind: "index", index: action.index };
      }
      if (!active) return;
      setConfirmation(undefined);
      setReloadTarget({ destination, opener: action.opener });
      await reloadSpread();
    } catch (failure) {
      if (!active) return;
      if (failure instanceof SpreadOccupiedError) setConfirmation({ action, placements: failure.placements });
      else {
        setSpreadError(describeFailure(failure));
        setFailedAction(action);
      }
    } finally {
      if (active) {
        setSpreadPending(false);
        if (confirmation() === undefined && reloadTarget() === undefined) restoreFocus(action.opener);
      }
    }
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
        <div class="workspace-album-heading">
          <h1>{props.album.title}</h1>
          <span aria-hidden="true">|</span>
          <a class="back-link" href="/">
            Back to album catalog
          </a>
        </div>
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
          {reloadTarget() !== undefined
            ? "Spread saved, but the workspace could not be refreshed."
            : refreshAfterSave()
              ? "Chapter saved, but the workspace could not be refreshed."
              : "Saved workspace could not be refreshed. Displayed photos may be out of date."}{" "}
          <button
            type="button"
            onClick={() => {
              if (reloadTarget() !== undefined) void reloadSpread();
              else void refresh();
            }}
          >
            Retry workspace
          </button>
        </div>
      </Show>
      <Show when={spreadError() !== undefined && confirmation() === undefined}>
        <p role="alert">
          {spreadError()}{" "}
          <button
            type="button"
            onClick={() => {
              if (reloadTarget() !== undefined) {
                void reloadSpread();
                return;
              }
              const action = failedAction();
              /* v8 ignore next -- Failure feedback retains its failed action. */
              if (action !== undefined) void mutateSpread(action);
            }}
          >
            {reloadTarget() === undefined ? "Retry spread action" : "Retry workspace"}
          </button>
        </p>
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
          <Show when={selectedSpread()}>
            {(spread) => (
              <TemplateSelector
                context={spread().kind}
                appliedTemplateId={spread().templateId}
                load={props.client.templates}
                unavailable={refreshReason()}
                onChange={(templateId, opener) => {
                  if (templateId !== spread().templateId) void mutateSpread({ kind: "template", spreadId: spread().id, templateId, opener });
                }}
              />
            )}
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
                unavailable={selection().kind === "invalid" ? "Choose an available album part to enter reader mode." : refreshReason()}
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
                  <Show when={spreadPosition()}>
                    {(position) => (
                      <ChapterMetadata
                        chapter={owner()}
                        position={position()}
                        countries={props.workspace.countries}
                        unavailable={refreshReason()}
                        onEdit={(opener) => {
                          setDialog({ chapter: owner(), opener });
                        }}
                      />
                    )}
                  </Show>
                )}
              </Show>
            </Show>
          </article>
          <Show when={part() !== "cover"}>
            <ChapterOutline
              albumId={props.album.id}
              chapters={props.workspace.chapters}
              spreads={props.workspace.spreads}
              part={part()}
              creationReason={creationReason()}
              unavailable={refreshReason()}
              onSelect={select}
              onCreate={(opener) => setDialog({ chapter: undefined, opener })}
              onAdd={(chapterId, spreadId, opener) => {
                void mutateSpread({ kind: "add", chapterId, spreadId, opener });
              }}
              onMove={(spreadId, direction, opener) => {
                void mutateSpread({ kind: "move", spreadId, direction, opener });
              }}
              onDelete={(spreadId, opener) => {
                const index = props.workspace.spreads.findIndex((spread) => spread.id === spreadId);
                void mutateSpread({ kind: "delete", spreadId, index, opener });
              }}
            />
          </Show>
        </div>
      </PhotoPane>
      <Show when={confirmation()}>
        {(occupied) => (
          <SpreadConfirmation
            placements={occupied().placements}
            deleting={occupied().action.kind === "delete"}
            pending={spreadPending()}
            error={spreadError()}
            onConfirm={() => {
              void mutateSpread(occupied().action, true);
            }}
            onCancel={() => {
              const opener = occupied().action.opener;
              setConfirmation(undefined);
              setSpreadError(undefined);
              setFailedAction(undefined);
              restoreFocus(opener);
            }}
          />
        )}
      </Show>
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
