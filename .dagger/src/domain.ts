/** The independently-checkable parts of this repo: the two app components plus the CI pipeline. */
export type Component = "backend" | "frontend" | "pipeline";

export const ALL_COMPONENTS: readonly Component[] = ["backend", "frontend", "pipeline"];

/** Which components a change set affects. `all` is kept distinct from listing every component so
 * callers can special-case "everything" without counting.
 */
export type Selection =
  | { readonly kind: "none" }
  | { readonly kind: "some"; readonly components: readonly Component[] }
  | { readonly kind: "all" };
