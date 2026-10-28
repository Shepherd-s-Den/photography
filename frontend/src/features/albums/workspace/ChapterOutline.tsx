import { For, Show, createEffect, createMemo, on, onCleanup, onMount } from "solid-js";
import type { Chapter, Slot, Spread } from "../model/Workspace";
import { findSelection, findSpreadNavigation } from "./WorkspaceSelection";
import { spreadAspectRatio } from "./Canvas";

export function ChapterOutline(props: {
  albumId: string;
  chapters: readonly Pick<Chapter, "id" | "name">[];
  spreads: readonly Spread[];
  part: string | null;
  creationReason: string | undefined;
  onSelect: (part: string | null) => void;
  onCreate: (opener: HTMLButtonElement) => void;
}) {
  const selection = createMemo(() => findSelection(props.spreads, props.part));
  const selectedSpread = createMemo(() => {
    const selected = selection();
    return selected.kind === "spread" ? selected.spread : undefined;
  });
  const navigation = createMemo(() => findSpreadNavigation(props.spreads, selection()));
  const position = createMemo(() =>
    navigation().position === undefined ? "Choose a spread" : `Spread ${String(navigation().position)} / ${String(navigation().total)}`,
  );
  let strip: HTMLElement;
  let active = true;
  function revealSelectedSpread() {
    if (!active) return;
    const selected = strip.querySelector('[aria-current="page"]');
    if (selected === null) return;
    const bounds = selected.getBoundingClientRect();
    strip.scrollLeft += bounds.left - strip.getBoundingClientRect().left - (strip.clientWidth - bounds.width) / 2;
  }
  createEffect(
    on(
      () => props.part,
      () => {
        queueMicrotask(revealSelectedSpread);
      },
    ),
  );
  onMount(() => {
    const observer = new ResizeObserver(revealSelectedSpread);
    observer.observe(strip);
    onCleanup(() => {
      observer.disconnect();
    });
  });
  onCleanup(() => {
    active = false;
  });

  return (
    <aside class="spread-navigator" aria-label="Spread navigator">
      <nav
        ref={(element) => {
          strip = element;
        }}
        aria-label="Album organization"
        tabindex="0"
      >
        <a
          href={`/albums/${props.albumId}`}
          aria-current={selectedSpread()?.kind === "album_start" ? "page" : undefined}
          onClick={(event) => {
            event.preventDefault();
            props.onSelect(null);
          }}
        >
          <Show when={props.spreads.find((spread) => spread.kind === "album_start")}>{(spread) => <SpreadThumbnail slots={spread().slots} />}</Show>
          <span>Synopsis</span>
        </a>
        <For each={props.chapters.map((chapter) => chapter.id)}>
          {(chapterId) => (
            <Show when={props.chapters.find((chapter) => chapter.id === chapterId)}>
              {(chapter) => (
                <div class="chapter-spread-group">
                  <For each={props.spreads.filter((spread) => spread.chapter === chapterId).map((spread) => spread.id)}>
                    {(spreadId, index) => (
                      <Show when={props.spreads.find((spread) => spread.id === spreadId)}>
                        {(spread) => {
                          const destination = () => (spread().kind === "chapter_start" ? chapterId : spreadId);
                          const caption = () =>
                            spread().kind === "chapter_start" ? chapter().name : `${chapter().name} · Photo spread ${String(index())}`;
                          return (
                            <a
                              href={`?part=${destination()}`}
                              aria-current={selectedSpread()?.id === spreadId ? "page" : undefined}
                              onClick={(event) => {
                                event.preventDefault();
                                props.onSelect(destination());
                              }}
                            >
                              <SpreadThumbnail slots={spread().slots} />
                              <span>{caption()}</span>
                            </a>
                          );
                        }}
                      </Show>
                    )}
                  </For>
                </div>
              )}
            </Show>
          )}
        </For>
      </nav>
      <div class="spread-navigation-toolbar">
        <div role="group" aria-label="Spread navigation">
          <SpreadNavigationButton name="First spread" symbol="⇤" destination={navigation().first} onSelect={props.onSelect} />
          <SpreadNavigationButton name="Previous workspace spread" symbol="‹" destination={navigation().previous} onSelect={props.onSelect} />
          <span id="workspace-spread-position" aria-live="polite">
            {position()}
          </span>
          <SpreadNavigationButton name="Next workspace spread" symbol="›" destination={navigation().next} onSelect={props.onSelect} />
          <SpreadNavigationButton name="Last spread" symbol="⇥" destination={navigation().last} onSelect={props.onSelect} />
        </div>
        <div role="group" aria-label="Spread and chapter actions">
          <button type="button" disabled aria-describedby="spread-creation-unavailable">
            Add spread
          </button>
          <button
            type="button"
            disabled={props.creationReason !== undefined}
            aria-describedby={props.creationReason === undefined ? undefined : "chapter-capacity"}
            onClick={(event) => {
              props.onCreate(event.currentTarget);
            }}
          >
            Add chapter
          </button>
        </div>
      </div>
      <p id="spread-creation-unavailable" class="spread-action-feedback">
        Adding spreads is not available yet.
      </p>
      <Show when={props.creationReason}>{(reason) => <p id="chapter-capacity">{reason()}</p>}</Show>
    </aside>
  );
}

export function getChapterCapacityReason(count: number): string | undefined {
  return count >= 65 ? "This album has 65 interior spreads. No more chapters can be added." : undefined;
}

function SpreadNavigationButton(props: {
  name: string;
  symbol: string;
  destination: string | null | undefined;
  onSelect: (part: string | null) => void;
}) {
  return (
    <button
      type="button"
      aria-label={props.name}
      disabled={props.destination === undefined}
      aria-describedby={props.destination === undefined ? "workspace-spread-position" : undefined}
      onClick={() => {
        /* v8 ignore next -- Native disabled endpoint controls cannot activate. */
        if (props.destination !== undefined) props.onSelect(props.destination);
      }}
    >
      {props.symbol}
    </button>
  );
}

function SpreadThumbnail(props: { slots: readonly Slot[] }) {
  return (
    <svg
      aria-hidden="true"
      class="spread-thumbnail"
      viewBox={`0 0 100 ${String(100 / spreadAspectRatio)}`}
      style={{ "aspect-ratio": spreadAspectRatio }}
    >
      <rect width="100%" height="100%" fill="white" />
      <For each={props.slots}>
        {(slot) => (
          <svg x={`${String(slot.x)}%`} y={`${String(slot.y)}%`} width={`${String(slot.width)}%`} height={`${String(slot.height)}%`}>
            <rect width="100%" height="100%" fill={slot.kind === "text" ? "#bed9d9" : "#dfebec"} />
          </svg>
        )}
      </For>
      <path d={`M50 0V${String(100 / spreadAspectRatio)}`} stroke="#9eb5bb" stroke-width=".35" />
    </svg>
  );
}
