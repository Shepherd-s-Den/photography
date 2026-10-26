import { Show, createMemo, createSignal, onCleanup, onMount, untrack } from "solid-js";
import type { Chapter, ChapterDetails, Country } from "../model/Workspace";
import { describeFailure } from "../model/Presentation";
import { CountryPicker } from "./CountryPicker";
import { suggestFolder, validateChapter } from "./ChapterDetails";

export function ChapterDialog(props: {
  save: (details: ChapterDetails) => Promise<{ id: string }>;
  year: number;
  photoRoot: string;
  countries: readonly Country[];
  homeAvailable: boolean;
  chapter: Pick<Chapter, "kind" | "name" | "countries" | "start" | "end" | "sourceFolder"> | undefined;
  onSaved: (chapterId: string) => void;
  onClose: () => void;
  returnFocus: () => void;
}) {
  const existing = untrack(() => props.chapter);
  const homeAvailable = untrack(() => props.homeAvailable);
  const [kind, setKind] = createSignal<"vacation" | "home" | "">(existing?.kind ?? (homeAvailable ? "" : "vacation"));
  const [name, setName] = createSignal(existing?.name ?? "");
  const [countries, setCountries] = createSignal<readonly string[]>(
    untrack(() => existing?.countries ?? props.countries.filter((country) => country.code === "RO").map((country) => country.id)),
  );
  const [start, setStart] = createSignal(existing?.start ?? `${String(untrack(() => props.year))}-01-01`);
  const [end, setEnd] = createSignal(existing?.end ?? `${String(untrack(() => props.year))}-01-01`);
  const [manualFolder, setManualFolder] = createSignal(existing?.sourceFolder);
  const folder = createMemo(() => manualFolder() ?? suggestFolder(props.photoRoot, props.year, kind() === "home" ? "Home" : name()));
  const [error, setError] = createSignal<string>();
  const [pending, setPending] = createSignal(false);
  const heading = existing === undefined ? "Add a chapter" : "Chapter details";
  let dialog: HTMLDialogElement;

  onMount(() => {
    dialog.showModal();
  });
  onCleanup(() => {
    dialog.close();
    props.returnFocus();
  });

  async function submit(event: SubmitEvent) {
    event.preventDefault();
    // Native disabled controls cannot submit while a save is pending.
    /* v8 ignore next */
    if (pending()) return;
    const selectedKind = kind();
    if (selectedKind === "") {
      setError("Choose a chapter type.");
      return;
    }
    const common = { countries: countries(), sourceFolder: folder() };
    const details: ChapterDetails =
      selectedKind === "home" ? { kind: "home", ...common } : { kind: "vacation", name: name().trim(), start: start(), end: end(), ...common };
    const validation = validateChapter(details);
    if (validation !== undefined) {
      setError(validation);
      return;
    }
    setPending(true);
    setError(undefined);
    try {
      const saved = await props.save(details);
      props.onSaved(saved.id);
    } catch (failure) {
      setError(describeFailure(failure));
      setPending(false);
    }
  }

  return (
    <dialog
      ref={(element) => {
        dialog = element;
      }}
      class="album-dialog chapter-dialog"
      aria-label={heading}
      onCancel={(event) => {
        event.preventDefault();
        if (!pending()) props.onClose();
      }}
    >
      <header class="dialog-heading">
        <h2>{heading}</h2>
        <button
          type="button"
          class="panel-close"
          aria-label="Close"
          disabled={pending()}
          aria-describedby="chapter-feedback"
          onClick={() => {
            props.onClose();
          }}
        >
          ×
        </button>
      </header>
      <form
        aria-label="Chapter details"
        novalidate
        onSubmit={(event) => {
          void submit(event);
        }}
      >
        <Show when={existing === undefined && homeAvailable}>
          <label>
            Chapter type
            <select
              value={kind()}
              disabled={pending()}
              aria-describedby="chapter-feedback"
              onChange={(event) => {
                const value = event.currentTarget.value;
                setKind(value === "home" ? "home" : value === "vacation" ? "vacation" : "");
              }}
            >
              <option value="">Choose a type</option>
              <option value="vacation">Vacation</option>
              <option value="home">Home</option>
            </select>
          </label>
        </Show>
        <Show when={kind() !== ""}>
          <Show when={kind() === "vacation"}>
            <label>
              Location / name
              <input
                value={name()}
                disabled={pending()}
                aria-describedby="chapter-feedback"
                onInput={(event) => {
                  setName(event.currentTarget.value);
                }}
              />
            </label>
          </Show>
          <CountryPicker countries={props.countries} selected={countries()} disabled={pending()} onChange={setCountries} />
          <Show when={kind() === "vacation"}>
            <div class="date-fields">
              <label>
                First day
                <input
                  type="date"
                  value={start()}
                  disabled={pending()}
                  aria-describedby="chapter-feedback"
                  onInput={(event) => {
                    setStart(event.currentTarget.value);
                  }}
                />
              </label>
              <label>
                Last day
                <input
                  type="date"
                  value={end()}
                  disabled={pending()}
                  aria-describedby="chapter-feedback"
                  onInput={(event) => {
                    setEnd(event.currentTarget.value);
                  }}
                />
              </label>
            </div>
          </Show>
          <label>
            Photo folder
            <input
              value={folder()}
              disabled={pending()}
              aria-describedby="chapter-feedback"
              onInput={(event) => {
                setManualFolder(event.currentTarget.value);
              }}
            />
          </label>
        </Show>
        <div id="chapter-feedback">
          <Show when={error()}>{(message) => <p role="alert">{message()}</p>}</Show>
          <Show when={pending()}>
            <p role="status">Saving chapter. Please wait.</p>
          </Show>
        </div>
        <div class="dialog-actions">
          <button
            type="button"
            class="secondary"
            disabled={pending()}
            aria-describedby="chapter-feedback"
            onClick={() => {
              props.onClose();
            }}
          >
            Cancel
          </button>
          <button type="submit" disabled={pending()} aria-describedby="chapter-feedback">
            {existing === undefined ? "Add chapter" : "Save chapter"}
          </button>
        </div>
      </form>
    </dialog>
  );
}
