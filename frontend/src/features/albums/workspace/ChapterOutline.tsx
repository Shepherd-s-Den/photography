import { For, Show } from "solid-js";
import type { Chapter, Spread } from "../model/Workspace";
import { describePeriod } from "./ChapterDetails";

export function ChapterOutline(props: {
  albumId: string;
  chapters: readonly Chapter[];
  spreads: readonly Spread[];
  part: string | null;
  chapterId: string | undefined;
  collapsed: boolean;
  creationReason: string | undefined;
  onSelect: (part: string | null) => void;
  onCollapse: () => void;
  onCreate: (opener: HTMLButtonElement) => void;
}) {
  return (
    <aside class="chapter-sidebar">
      <div class="sidebar-heading">
        <Show when={!props.collapsed}>
          <h2>Chapters</h2>
        </Show>
        <button
          type="button"
          class="sidebar-toggle secondary"
          aria-label={props.collapsed ? "Expand chapter sidebar" : "Collapse chapter sidebar"}
          aria-expanded={!props.collapsed}
          aria-controls="chapter-controls"
          onClick={(event) => {
            props.onCollapse();
            event.currentTarget.focus();
          }}
        >
          {props.collapsed ? "›" : "‹"}
        </button>
      </div>
      <div class="sidebar-content">
        <div id="chapter-controls" inert={props.collapsed} aria-hidden={props.collapsed} class="chapter-controls">
          <nav aria-label="Album organization">
            <a
              href={`/albums/${props.albumId}`}
              aria-current={props.part === null ? "page" : undefined}
              onClick={(event) => {
                event.preventDefault();
                props.onSelect(null);
              }}
            >
              Album opening
            </a>
            <For each={props.chapters}>
              {(chapter, index) => (
                <div class="chapter-block">
                  <a
                    href={`?part=${chapter.id}`}
                    aria-current={props.chapterId === chapter.id ? "page" : undefined}
                    onClick={(event) => {
                      event.preventDefault();
                      props.onSelect(chapter.id);
                    }}
                  >
                    <span class="chapter-number">{String(index() + 1).padStart(2, "0")}</span>{" "}
                    <span>
                      {chapter.name} <small>{describePeriod(chapter)}</small>
                    </span>
                  </a>
                  <For each={props.spreads.filter((spread) => spread.chapter === chapter.id && spread.kind === "photos_only")}>
                    {(spread, spreadIndex) => (
                      <a
                        class="photo-spread-link"
                        href={`?part=${spread.id}`}
                        aria-current={props.part === spread.id ? "page" : undefined}
                        onClick={(event) => {
                          event.preventDefault();
                          props.onSelect(spread.id);
                        }}
                      >
                        {chapter.name} · Photo spread {spreadIndex() + 1}
                      </a>
                    )}
                  </For>
                </div>
              )}
            </For>
          </nav>
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
          <Show when={props.creationReason}>{(reason) => <p id="chapter-capacity">{reason()}</p>}</Show>
        </div>
      </div>
    </aside>
  );
}

export function getChapterCapacityReason(count: number): string | undefined {
  return count >= 65 ? "This album has 65 interior spreads. No more chapters can be added." : undefined;
}
