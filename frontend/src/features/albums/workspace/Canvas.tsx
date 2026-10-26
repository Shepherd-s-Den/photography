import { For, Show } from "solid-js";
import type { Slot } from "../model/Workspace";

export function SpreadCanvas(props: { slots: readonly Slot[]; text: string; label: string }) {
  return (
    <div class="canvas-frame">
      <svg class="spread-canvas" role="img" aria-label={`${props.label} composition`} viewBox="0 0 2381.102 765.354">
        <rect width="2381.102" height="765.354" fill="white" />
        <For each={props.slots}>
          {(slot) => (
            <svg x={`${String(slot.x)}%`} y={`${String(slot.y)}%`} width={`${String(slot.width)}%`} height={`${String(slot.height)}%`}>
              <Show
                when={slot.kind === "photo"}
                fallback={
                  <foreignObject width="100%" height="100%">
                    <div class="spread-owner-text">{props.text}</div>
                  </foreignObject>
                }
              >
                <rect width="100%" height="100%" fill="#dfebec" stroke="white" stroke-width="3" />
              </Show>
            </svg>
          )}
        </For>
      </svg>
    </div>
  );
}

export function CoverCanvas(props: { title: string; year: number }) {
  return (
    <div class="cover-canvas" role="img" aria-label={`Cover: back on the left; front on the right. ${props.title}. ${getCoverCredits(props.year)}`}>
      <div class="cover-back">
        <span>Back cover</span>
      </div>
      <div class="cover-front">
        <span>Front cover</span>
        <div>
          <h3>{props.title}</h3>
          <p>{getCoverCredits(props.year)}</p>
        </div>
      </div>
    </div>
  );
}

export function getCoverCredits(year: number): string {
  return year <= 2025 ? "by Cosmin & Mara" : "by Cosmin, Mara & Iacob";
}
