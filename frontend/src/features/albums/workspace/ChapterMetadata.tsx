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
  const edit = (event: MouseEvent & { currentTarget: HTMLButtonElement }) => {
    props.onEdit(event.currentTarget);
  };
  return (
    <>
      <dl class="chapter-metadata">
        <div>
          <dt>Location</dt>
          <dd>
            <button
              type="button"
              aria-label="Change chapter location"
              disabled={props.unavailable !== undefined}
              aria-describedby="metadata-feedback"
              onClick={edit}
            >
              {props.chapter.name}
            </button>
          </dd>
        </div>
        <div>
          <dt>Countries</dt>
          <dd>
            <button
              type="button"
              aria-label="Change chapter countries"
              disabled={props.unavailable !== undefined}
              aria-describedby="metadata-feedback"
              onClick={edit}
            >
              {countryNames()}
            </button>
          </dd>
        </div>
        <div>
          <dt>Period</dt>
          <dd>
            <button
              type="button"
              aria-label="Change chapter period"
              disabled={props.unavailable !== undefined}
              aria-describedby="metadata-feedback"
              onClick={edit}
            >
              {describePeriod(props.chapter)}
            </button>
          </dd>
        </div>
        <div class="folder-metadata">
          <dt>Photo folder</dt>
          <dd>
            <button
              type="button"
              aria-label="Change chapter photo folder"
              disabled={props.unavailable !== undefined}
              aria-describedby="metadata-feedback"
              onClick={edit}
            >
              {props.chapter.sourceFolder}
            </button>
          </dd>
        </div>
        <Show when={props.chapter.description !== ""}>
          <div>
            <dt>Description</dt>
            <dd>{props.chapter.description}</dd>
          </div>
        </Show>
      </dl>
      <Show when={props.unavailable}>{(reason) => <p id="metadata-feedback">{reason()}</p>}</Show>
    </>
  );
}
