import { For, Show } from "solid-js";
import type { Slot } from "../model/Workspace";

const spreadWidth = 2381.102;
const spreadHeight = 765.354;
export const spreadAspectRatio = spreadWidth / spreadHeight;
export const coverAspectRatio = 3;

export function SpreadCanvas(props: { slots: readonly Slot[]; text: string; label: string }) {
  return (
    <div class="canvas-frame">
      <svg
        class="spread-canvas"
        role="img"
        aria-label={`${props.label} composition`}
        viewBox={`0 0 ${String(spreadWidth)} ${String(spreadHeight)}`}
        style={{ "aspect-ratio": spreadAspectRatio }}
      >
        <rect width={spreadWidth} height={spreadHeight} fill="white" />
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
    <div
      class="cover-canvas"
      role="img"
      aria-label={`Cover: back on the left; front on the right. ${props.title}. ${getCoverCredits(props.year)}`}
      style={{ "aspect-ratio": coverAspectRatio }}
    >
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
