import { Show, createMemo, createSignal, onCleanup, onMount, untrack } from "solid-js";
import type { Chapter, ChapterDetails, Country } from "../model/Workspace";
import type { ChapterSave } from "../model/Photo";
import { describeFailure } from "../model/Presentation";
import { CountryPicker } from "./CountryPicker";
import { displayCalendarDay, readCalendarDay, suggestFolder, validateChapter } from "./ChapterDetails";

export function ChapterDialog(props: {
  save: (details: ChapterDetails) => Promise<ChapterSave>;
  year: number;
  photoRoot: string;
  countries: readonly Country[];
  homeAvailable: boolean;
  chapter: Pick<Chapter, "kind" | "name" | "countries" | "start" | "end" | "sourceFolder"> | undefined;
  onSaved: (result: ChapterSave) => void;
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
  const [start, setStart] = createSignal(displayCalendarDay(existing?.start ?? `${String(untrack(() => props.year))}-01-01`));
  const [end, setEnd] = createSignal(displayCalendarDay(existing?.end ?? `${String(untrack(() => props.year))}-01-01`));
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
      selectedKind === "home"
        ? { kind: "home", ...common }
        : { kind: "vacation", name: name().trim(), start: readCalendarDay(start()), end: readCalendarDay(end()), ...common };
    const validation = validateChapter(details);
    if (validation !== undefined) {
      setError(validation);
      return;
    }
    setPending(true);
    try {
      const saved = await props.save(details);
      props.onSaved(saved);
    } catch (failure) {
      setError(describeFailure(failure));
      setPending(false);
    }
  }

  function openCalendar(event: Event & { currentTarget: HTMLInputElement }) {
    if (typeof event.currentTarget.showPicker !== "function") return;
    event.preventDefault();
    event.currentTarget.showPicker();
  }

  function handleCalendarKey(event: KeyboardEvent & { currentTarget: HTMLInputElement }) {
    if (event.key === "Enter" || event.key === " ") openCalendar(event);
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
                  type="text"
                  aria-label="First day"
                  placeholder="dd/mm/yyyy"
                  aria-description="Date in day/month/year format"
                  value={start()}
                  disabled={pending()}
                  aria-describedby="chapter-feedback"
                  onInput={(event) => {
                    setStart(event.currentTarget.value);
                  }}
                />
                <input
                  type="date"
                  aria-label="Choose first day from calendar"
                  title="Choose first day from calendar"
                  value={readCalendarDay(start())}
                  disabled={pending()}
                  aria-describedby="chapter-feedback"
                  onClick={openCalendar}
                  onKeyDown={handleCalendarKey}
                  onInput={(event) => {
                    setStart(event.currentTarget.value === "" ? "" : displayCalendarDay(event.currentTarget.value));
                  }}
                />
                <svg class="date-calendar-icon" viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M5 5h14v16H5z M8 3v4 M16 3v4 M5 10h14 M8 14h2 M14 14h2 M8 17h2" />
                </svg>
              </label>
              <label>
                Last day
                <input
                  type="text"
                  aria-label="Last day"
                  placeholder="dd/mm/yyyy"
                  aria-description="Date in day/month/year format"
                  value={end()}
                  disabled={pending()}
                  aria-describedby="chapter-feedback"
                  onInput={(event) => {
                    setEnd(event.currentTarget.value);
                  }}
                />
                <input
                  type="date"
                  aria-label="Choose last day from calendar"
                  title="Choose last day from calendar"
                  value={readCalendarDay(end())}
                  disabled={pending()}
                  aria-describedby="chapter-feedback"
                  onClick={openCalendar}
                  onKeyDown={handleCalendarKey}
                  onInput={(event) => {
                    setEnd(event.currentTarget.value === "" ? "" : displayCalendarDay(event.currentTarget.value));
                  }}
                />
                <svg class="date-calendar-icon" viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M5 5h14v16H5z M8 3v4 M16 3v4 M5 10h14 M8 14h2 M14 14h2 M8 17h2" />
                </svg>
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
          <Show when={error()}>
            {(message) => (
              <p role="alert" aria-hidden={pending()}>
                {message()}
              </p>
            )}
          </Show>
          <div class="dialog-status">
            <Show when={pending()}>
              <p role="status">Saving chapter. Please wait.</p>
            </Show>
          </div>
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
