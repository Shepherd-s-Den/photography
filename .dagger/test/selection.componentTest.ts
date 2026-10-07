import { describe, expect, it } from "vitest";
import * as Selection from "../src/selection";

describe("parseChangedPaths", () => {
  it("should split comma and newline separated paths and drop blanks", () => {
    expect(Selection.parseChangedPaths("backend/a.scala,\nfrontend/index.html,  , ")).toEqual([
      "backend/a.scala",
      "frontend/index.html",
    ]);
  });
});

describe("componentsForPath", () => {
  it("should map backend and frontend paths to their own component", () => {
    expect(Selection.componentsForPath("backend/build.sbt")).toEqual(["backend"]);
    expect(Selection.componentsForPath("frontend/index.html")).toEqual(["frontend"]);
  });

  it("should map pipeline paths to the pipeline component", () => {
    expect(Selection.componentsForPath("scripts/local.py")).toEqual(["pipeline"]);
    expect(Selection.componentsForPath(".dagger/src/index.ts")).toEqual(["pipeline"]);
    expect(Selection.componentsForPath("dagger.json")).toEqual(["pipeline"]);
    expect(Selection.componentsForPath(".github/workflows/build.yml")).toEqual(["pipeline"]);
    expect(Selection.componentsForPath(".github/workflows/verify-main.yml")).toEqual(["pipeline"]);
  });

  it("should ignore documentation and unrelated paths", () => {
    expect(Selection.componentsForPath("README.md")).toEqual([]);
  });
});

describe("selectAffected", () => {
  it("should return none when no checked component changed", () => {
    expect(Selection.selectAffected(["README.md"])).toEqual({ kind: "none" });
  });

  it("should return one component when one surface changed", () => {
    expect(Selection.selectAffected(["frontend/index.html"])).toEqual({
      kind: "some",
      components: ["frontend"],
    });
  });

  it("should return all when every component changed", () => {
    expect(
      Selection.selectAffected(["backend/build.sbt", "frontend/index.html", ".dagger/x.ts"]),
    ).toEqual({
      kind: "all",
    });
  });
});

describe("componentsOf", () => {
  it("should expand each selection variant", () => {
    expect(Selection.componentsOf({ kind: "none" })).toEqual([]);
    expect(Selection.componentsOf({ kind: "all" })).toEqual(["backend", "frontend", "pipeline"]);
    expect(Selection.componentsOf({ kind: "some", components: ["backend"] })).toEqual(["backend"]);
  });
});
