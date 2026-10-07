import { render, screen } from "@testing-library/svelte";
import { describe, expect, it } from "vitest";
import App from "../../src/app/App.svelte";

describe("empty app shell", () => {
  it("should render an empty main landmark", () => {
    render(App);

    expect(screen.getByRole("main")).toBeInTheDocument();
  });
});
