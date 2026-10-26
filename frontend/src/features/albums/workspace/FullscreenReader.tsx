import { Show, createMemo, createSignal, createUniqueId, onCleanup, type JSX } from "solid-js";
import { Portal } from "solid-js/web";
import "./reader.css";

export function FullscreenReader(props: {
  aspectRatio: number;
  canPrevious: boolean;
  canNext: boolean;
  entryMount?: HTMLDivElement | undefined;
  unavailable: string | undefined;
  onNavigate: (direction: -1 | 1) => void;
  label: string;
  children: JSX.Element;
}) {
  const [active, setActive] = createSignal(false);
  const [pending, setPending] = createSignal(false);
  const [error, setError] = createSignal<string>();
  const entryReason = createMemo(() => props.unavailable ?? (pending() ? "Entering fullscreen. Please wait." : undefined));
  const reasonId = createUniqueId();
  let stage!: HTMLDivElement;
  let opener!: HTMLButtonElement;
  let exitButton: HTMLButtonElement | undefined;

  function syncFullscreen() {
    const entered = document.fullscreenElement === stage;
    const exited = active() && !entered;
    setActive(entered);
    if (entered) exitButton?.focus();
    if (exited) {
      setError(undefined);
      opener.focus();
    }
  }
  document.addEventListener("fullscreenchange", syncFullscreen);
  document.addEventListener("keydown", handleKeyDown);
  onCleanup(() => {
    document.removeEventListener("fullscreenchange", syncFullscreen);
    document.removeEventListener("keydown", handleKeyDown);
  });

  async function enter() {
    setError(undefined);
    setPending(true);
    try {
      if (typeof stage.requestFullscreen !== "function") throw new Error("Fullscreen unavailable");
      await stage.requestFullscreen();
    } catch {
      setError("Browser fullscreen could not be started. You can still use the workspace.");
    } finally {
      setPending(false);
      if (error() !== undefined) opener.focus();
    }
  }

  async function exit() {
    try {
      await document.exitFullscreen();
    } catch {
      setError("Browser fullscreen could not be closed. Press Escape to leave fullscreen.");
    }
  }

  function handleKeyDown(event: KeyboardEvent) {
    if (!active()) return;
    if (event.key === "Escape") {
      event.preventDefault();
      void exit();
    } else if (event.key === "Tab") {
      const controls = Array.from(stage.querySelectorAll<HTMLButtonElement>("button:not(:disabled)"));
      const index = controls.findIndex((control) => control === document.activeElement);
      const next = index < 0 ? (event.shiftKey ? controls.length - 1 : 0) : (index + (event.shiftKey ? -1 : 1) + controls.length) % controls.length;
      event.preventDefault();
      controls[next]?.focus();
    } else if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
      event.preventDefault();
      const direction = event.key === "ArrowLeft" ? -1 : 1;
      if (direction === -1 ? props.canPrevious : props.canNext) props.onNavigate(direction);
    }
  }

  const entry = (
    <div class="reader-entry-control">
      <button
        ref={(element) => {
          opener = element;
        }}
        type="button"
        disabled={entryReason() !== undefined}
        aria-describedby={entryReason() !== undefined ? reasonId : undefined}
        onClick={() => void enter()}
      >
        Reader
      </button>
      <Show when={entryReason()}>{(reason) => <span id={reasonId}>{reason()}</span>}</Show>
    </div>
  );

  return (
    <>
      <div class="reader-entry">
        <Show when={props.label !== ""}>
          <h2>{props.label}</h2>
        </Show>
        <Show when={props.entryMount} fallback={entry}>
          {(mount) => <Portal mount={mount()}>{entry}</Portal>}
        </Show>
      </div>
      <div
        ref={(element) => {
          stage = element;
        }}
        class="reader-stage"
        classList={{ "reader-active": active() }}
        role={active() ? "dialog" : undefined}
        aria-modal={active() ? true : undefined}
        aria-label={active() ? "Fullscreen Reader" : undefined}
      >
        <div class="reader-canvas" style={{ "--canvas-aspect-ratio": props.aspectRatio }}>
          {props.children}
        </div>
        <Show when={active()}>
          <div class="reader-controls" role="group" aria-label="Reader controls">
            <div>
              <button
                type="button"
                disabled={!props.canPrevious}
                onClick={() => {
                  props.onNavigate(-1);
                }}
              >
                Previous spread
              </button>
            </div>
            <p role="status" aria-live="polite">
              {props.label}
            </p>
            <div>
              <button
                type="button"
                disabled={!props.canNext}
                onClick={() => {
                  props.onNavigate(1);
                }}
              >
                Next spread
              </button>
            </div>
            <button
              ref={(element) => {
                exitButton = element;
              }}
              type="button"
              onClick={() => void exit()}
            >
              Exit reader
            </button>
          </div>
        </Show>
        <Show when={error()}>
          {(message) => (
            <p class="reader-error" role="alert">
              {message()}
            </p>
          )}
        </Show>
      </div>
    </>
  );
}
