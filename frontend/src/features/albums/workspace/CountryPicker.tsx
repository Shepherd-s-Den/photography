import { For, Show, createMemo, createSignal, createUniqueId } from "solid-js";
import type { Country } from "../model/Workspace";

export function CountryPicker(props: {
  countries: readonly Country[];
  selected: readonly string[];
  disabled: boolean;
  onChange: (ids: readonly string[]) => void;
}) {
  const id = createUniqueId();
  const [query, setQuery] = createSignal("");
  const [open, setOpen] = createSignal(false);
  const [active, setActive] = createSignal(0);
  const matches = createMemo(() => searchCountries(props.countries, query()));
  const selectedCountries = createMemo(() => props.countries.filter((country) => props.selected.includes(country.id)));
  const activeOption = createMemo(() => (open() ? matches()[active()] : undefined));
  let container: HTMLDivElement;
  let search: HTMLInputElement;

  function toggle(country: Country) {
    props.onChange(
      props.selected.includes(country.id) ? props.selected.filter((selected) => selected !== country.id) : [...props.selected, country.id],
    );
  }

  function handleKey(event: KeyboardEvent) {
    if (event.key === "Escape" && open()) {
      event.preventDefault();
      event.stopPropagation();
      setOpen(false);
    } else if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      const step = event.key === "ArrowDown" ? 1 : -1;
      const nextActive = open() && matches().length > 0 ? (active() + step + matches().length) % matches().length : 0;
      setActive(nextActive);
      setOpen(true);
      queueMicrotask(() => {
        document.getElementById(`${id}-${String(nextActive)}`)?.scrollIntoView({ block: "nearest" });
      });
    } else if (event.key === "Enter" && open()) {
      event.preventDefault();
      const country = activeOption();
      if (country !== undefined) toggle(country);
    }
  }

  return (
    <div
      ref={(element) => {
        container = element;
      }}
      class="country-picker"
    >
      <label for={`${id}-search`}>Countries</label>
      <div class="country-selections" aria-label="Selected countries">
        <For each={selectedCountries()}>
          {(country) => (
            <button
              type="button"
              class="secondary"
              disabled={props.disabled}
              aria-describedby="chapter-feedback"
              aria-label={`Remove ${country.name}`}
              onClick={() => {
                toggle(country);
                search.focus();
              }}
            >
              {country.name} <span aria-hidden="true">×</span>
            </button>
          )}
        </For>
      </div>
      <input
        ref={(element) => {
          search = element;
        }}
        id={`${id}-search`}
        role="combobox"
        aria-label="Search countries"
        autocomplete="off"
        aria-autocomplete="list"
        aria-expanded={open()}
        aria-controls={`${id}-options`}
        aria-activedescendant={activeOption() === undefined ? undefined : `${id}-${String(active())}`}
        disabled={props.disabled}
        aria-describedby="chapter-feedback"
        value={query()}
        onFocus={() => {
          setOpen(true);
        }}
        onBlur={(event) => {
          const target = event.relatedTarget;
          if (!(target instanceof Node) || !container.contains(target)) setOpen(false);
        }}
        onInput={(event) => {
          setQuery(event.currentTarget.value);
          setActive(0);
          setOpen(true);
        }}
        onKeyDown={handleKey}
      />
      <Show when={open()}>
        <div class="country-popup">
          <div id={`${id}-options`} role="listbox" aria-label="Countries" aria-multiselectable="true" class="country-options">
            <For each={matches()}>
              {(country, index) => (
                <button
                  id={`${id}-${String(index())}`}
                  type="button"
                  disabled={props.disabled}
                  aria-describedby="chapter-feedback"
                  role="option"
                  aria-selected={props.selected.includes(country.id)}
                  tabindex="-1"
                  classList={{ "active-option": active() === index() }}
                  onMouseDown={(event) => {
                    event.preventDefault();
                  }}
                  onClick={() => {
                    toggle(country);
                  }}
                >
                  {country.name} ({country.code}) {props.selected.includes(country.id) ? "✓" : ""}
                </button>
              )}
            </For>
          </div>
          <Show when={matches().length === 0}>
            <p role="status">No countries match your search.</p>
          </Show>
        </div>
      </Show>
    </div>
  );
}

export function searchCountries(countries: readonly Country[], query: string): readonly Country[] {
  const needle = query.trim().toLocaleLowerCase();
  return countries.filter((country) => country.name.toLocaleLowerCase().includes(needle) || country.code.toLocaleLowerCase().includes(needle));
}
