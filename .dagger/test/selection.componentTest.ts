import { describe, expect, it } from "vitest";
import * as Selection from "../src/selection";

describe("parseChangedPaths", () => {
  it("should split comma and newline separated paths and drop blanks", () => {
    const paths = Selection.parseChangedPaths("backend/a.scala,\nfrontend/index.html,  , ");

    expect(paths).toEqual(["backend/a.scala", "frontend/index.html"]);
  });
});

describe("findComponentsForPath", () => {
  it("should include the backend when frontend assets are rebuilt into it", () => {
    expect(Selection.findComponentsForPath("backend/build.sbt")).toEqual(["backend"]);
    expect(Selection.findComponentsForPath("frontend/index.html")).toEqual(["backend", "frontend"]);
  });

  it("should map pipeline paths to the pipeline component", () => {
    const workflowComponents = Selection.findComponentsForPath(".github/workflows/verify-main.yml");

    expect(Selection.findComponentsForPath("scripts/local.py")).toEqual(["pipeline"]);
    expect(Selection.findComponentsForPath(".dagger/src/index.ts")).toEqual(["pipeline"]);
    expect(Selection.findComponentsForPath("dagger.json")).toEqual(["pipeline"]);
    expect(Selection.findComponentsForPath(".github/workflows/build.yml")).toEqual(["pipeline"]);
    expect(workflowComponents).toEqual(["pipeline"]);
  });

  it("should ignore documentation and unrelated paths", () => {
    expect(Selection.findComponentsForPath("README.md")).toEqual([]);
  });
});

describe("selectAffected", () => {
  it("should return none when no checked component changed", () => {
    expect(Selection.selectAffected(["README.md"])).toEqual({ kind: "none" });
  });

  it("should return all checks affected by one changed surface", () => {
    const expected = {
      kind: "some",
      components: ["backend", "frontend"],
    };

    expect(Selection.selectAffected(["frontend/index.html"])).toEqual(expected);
  });

  it("should return all when every component changed", () => {
    const selection = Selection.selectAffected([
      "backend/build.sbt",
      "frontend/index.html",
      ".dagger/x.ts",
    ]);

    expect(selection).toEqual({ kind: "all" });
  });
});

describe("listSelectedComponents", () => {
  it("should expand each selection variant", () => {
    const none = Selection.listSelectedComponents({ kind: "none" });
    const all = Selection.listSelectedComponents({ kind: "all" });
    const some = Selection.listSelectedComponents({ kind: "some", components: ["backend"] });

    expect(none).toEqual([]);
    expect(all).toEqual(["backend", "frontend", "pipeline"]);
    expect(some).toEqual(["backend"]);
  });
});
