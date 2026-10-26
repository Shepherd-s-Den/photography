import { Show, createMemo } from "solid-js";
import type { Chapter, Country } from "../model/Workspace";
import { describePeriod } from "./ChapterDetails";

export function ChapterMetadata(props: {
  chapter: Chapter;
  countries: readonly Country[];
  unavailable: string | undefined;
  onEdit: (opener: HTMLButtonElement) => void;
}) {
  const countryNames = createMemo(() =>
    props.countries
      .filter((country) => props.chapter.countries.includes(country.id))
      .map((country) => country.name)
      .join(", "),
  );
  return (
    <>
      <div class="chapter-metadata-row">
        <dl class="chapter-metadata">
          <div>
            <dt>Location</dt>
            <dd>{props.chapter.name}</dd>
          </div>
          <div>
            <dt>Countries</dt>
            <dd>{countryNames()}</dd>
          </div>
          <div>
            <dt>Period</dt>
            <dd>{describePeriod(props.chapter)}</dd>
          </div>
          <div class="folder-metadata">
            <dt>Photo folder</dt>
            <dd>{props.chapter.sourceFolder}</dd>
          </div>
        </dl>
        <button
          type="button"
          class="icon-button metadata-edit"
          aria-label="Edit chapter details"
          title="Edit chapter details"
          disabled={props.unavailable !== undefined}
          aria-describedby={props.unavailable !== undefined ? "metadata-feedback" : undefined}
          onClick={(event) => {
            props.onEdit(event.currentTarget);
          }}
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="m15 4 5 5M4 20l5-1L20 8a2 2 0 0 0-5-5L4 14z" />
          </svg>
        </button>
      </div>
      <Show when={props.unavailable}>{(reason) => <p id="metadata-feedback">{reason()}</p>}</Show>
    </>
  );
}
