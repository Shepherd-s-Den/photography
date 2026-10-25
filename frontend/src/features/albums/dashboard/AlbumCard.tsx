import type { AlbumSummary } from "../model/Album";

export function AlbumCard(props: { album: AlbumSummary; onDelete: () => void }) {
  return (
    <article class="album-card">
      <a class="album-card-open" href={`/albums/${encodeURIComponent(props.album.id)}`}>
        <div class="cover-preview" aria-hidden="true">
          <span class="cover-spine" />
        </div>
        <h2>{props.album.title}</h2>
      </a>
      <div class="album-card-actions">
        <button
          id={`delete-${props.album.id}`}
          type="button"
          class="icon-button delete-action"
          aria-label={`Delete ${props.album.title}`}
          onClick={() => {
            props.onDelete();
          }}
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M3 6h18M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7m4-7v7" />
          </svg>
        </button>
      </div>
    </article>
  );
}
