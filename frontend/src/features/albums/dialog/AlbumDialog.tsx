import { Show, createMemo, createSignal, onCleanup, onMount, untrack } from "solid-js";
import type { AlbumClient, AlbumSummary } from "../model/Album";
import { failureMessage, validateTitle } from "../model/Presentation";

export type AlbumAction = { kind: "create" } | { kind: "rename"; album: AlbumSummary } | { kind: "delete"; album: AlbumSummary };

export function AlbumDialog(props: {
  client: AlbumClient;
  action: AlbumAction;
  onClose: () => void;
  onSaved: () => Promise<void>;
  returnFocus: () => void;
}) {
  const [title, setTitle] = createSignal(untrack(() => (props.action.kind === "create" ? "" : props.action.album.title)));
  const [error, setError] = createSignal<string>();
  const [pending, setPending] = createSignal(false);
  const [closing, setClosing] = createSignal(false);
  const deleting = createMemo(() => props.action.kind === "delete");
  const heading = createMemo(() =>
    props.action.kind === "delete" ? `Delete ${props.action.album.title}?` : props.action.kind === "create" ? "Create an album" : "Edit album name",
  );
  const unavailable = createMemo(() => (pending() ? "Saving album. Please wait." : closing() ? "Closing album dialog." : undefined));
  let dialog: HTMLDialogElement;
  let input: HTMLInputElement;
  let cancel: HTMLButtonElement;

  onMount(() => {
    dialog.showModal();
    if (deleting()) cancel.focus();
    else input.focus();
  });
  onCleanup(() => {
    dialog.close();
    props.returnFocus();
  });

  async function close() {
    setClosing(true);
    // Let Solid apply the closing animation before awaiting it.
    await Promise.resolve();
    // Native animation cancellation (including reduced motion) must still finish closing.
    await Promise.allSettled(dialog.getAnimations().map((animation) => animation.finished));
    props.onClose();
  }

  async function submit(event: SubmitEvent) {
    event.preventDefault();
    const validation = deleting() ? undefined : validateTitle(title());
    if (validation !== undefined) {
      setError(validation);
      return;
    }
    setPending(true);
    setError(undefined);
    try {
      const action = props.action;
      if (action.kind === "create") await props.client.create(title().trim());
      else if (action.kind === "rename") await props.client.rename(action.album.id, title().trim());
      else await props.client.remove(action.album.id);
      await props.onSaved();
      await close();
    } catch (failure) {
      setError(failureMessage(failure));
      setPending(false);
    }
  }

  return (
    <dialog
      ref={(element) => {
        dialog = element;
      }}
      aria-label={heading()}
      role={deleting() ? "alertdialog" : "dialog"}
      class="album-dialog"
      classList={{ "confirmation-dialog": deleting(), "is-closing": closing() }}
      onCancel={(event) => {
        event.preventDefault();
        if (unavailable() === undefined) void close();
      }}
    >
      <Show when={deleting()}>
        <span class="confirmation-warning" aria-hidden="true">
          !
        </span>
      </Show>
      <header class="dialog-heading">
        <h2>{heading()}</h2>
        <Show when={!deleting()}>
          <button
            type="button"
            class="panel-close"
            aria-label="Close"
            disabled={unavailable() !== undefined}
            aria-describedby="album-dialog-feedback"
            onClick={() => {
              void close();
            }}
          >
            ×
          </button>
        </Show>
      </header>
      <form
        aria-label="Album details"
        onSubmit={(event) => {
          void submit(event);
        }}
      >
        <Show
          when={deleting()}
          fallback={
            <label>
              Album name
              <input
                ref={(element) => {
                  input = element;
                }}
                value={title()}
                disabled={unavailable() !== undefined}
                onInput={(event) => {
                  setTitle(event.currentTarget.value);
                }}
                aria-describedby="album-dialog-feedback"
              />
            </label>
          }
        >
          <p>This permanently deletes the album. Deleting cannot be undone.</p>
        </Show>
        <div id="album-dialog-feedback">
          <Show when={error()}>{(message) => <p role="alert">{message()}</p>}</Show>
          <Show when={unavailable()}>{(reason) => <p role="status">{reason()}</p>}</Show>
        </div>
        <div class="dialog-actions">
          <button
            ref={(element) => {
              cancel = element;
            }}
            type="button"
            class="secondary"
            disabled={unavailable() !== undefined}
            aria-describedby="album-dialog-feedback"
            onClick={() => {
              void close();
            }}
          >
            Cancel
          </button>
          <button type="submit" classList={{ danger: deleting() }} disabled={unavailable() !== undefined} aria-describedby="album-dialog-feedback">
            {deleting() ? "Delete permanently" : props.action.kind === "create" ? "Create" : "Save"}
          </button>
        </div>
      </form>
    </dialog>
  );
}
