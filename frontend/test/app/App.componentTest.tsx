import { render, screen } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import { App } from "../../src/app/App";

describe("empty app shell", () => {
  it("should render an empty main landmark", () => {
    render(() => <App />);

    expect(screen.getByRole("main")).toBeInTheDocument();
  });
});
