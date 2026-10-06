import type * as Dagger from "@dagger.io/dagger";
import { argument, func, object } from "@dagger.io/dagger";
import { WORKSPACE_IGNORE } from "./buildEnv";
import { backendCheck } from "./hooks/backend";
import { frontendCheck } from "./hooks/frontend";
import { localRunnerCheck as localRunnerCheckJob } from "./hooks/localRunner";
import { pipelineCheck } from "./hooks/pipeline";
import * as Git from "./hooks/git";
import * as Selection from "./selection";

/** Affected-component CI for the photography repository. */
@object()
export class Photography {
  /** Maps changed paths relative to `base` (default: the merge-base with origin/main). */
  @func()
  async changed(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Dagger.Directory,
    base = "",
  ): Promise<string> {
    const components = Selection.componentsOf(
      Selection.selectAffected(Selection.parseChangedPaths(await Git.changedPaths(source, base))),
    );
    return components.length === 0 ? "none" : components.join(",");
  }

  /** Runs checks for affected components, or every component when `all` is true. */
  @func()
  async verify(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Dagger.Directory,
    base = "",
    all = false,
  ): Promise<string> {
    const selection = all
      ? ({ kind: "all" } as const)
      : Selection.selectAffected(Selection.parseChangedPaths(await Git.changedPaths(source, base)));
    const components = Selection.componentsOf(selection);
    const done: string[] = [];
    if (components.includes("backend")) {
      await this.backendCheck(source);
      await this.localRunnerCheck(source);
      done.push("backend");
    }
    if (components.includes("frontend")) {
      await this.frontendCheck(source);
      done.push("frontend");
    }
    if (components.includes("pipeline")) {
      await this.pipelineCheck(source);
      done.push("pipeline");
    }
    return done.length === 0 ? "verify: nothing affected" : `verify: ${done.join(", ")} ok`;
  }

  @func()
  backendCheck(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Dagger.Directory,
  ): Promise<string> {
    return backendCheck(source).stdout();
  }

  @func()
  localRunnerCheck(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Dagger.Directory,
  ): Promise<string> {
    return localRunnerCheckJob(source).stdout();
  }

  @func()
  frontendCheck(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Dagger.Directory,
  ): Promise<string> {
    return frontendCheck(source).stdout();
  }

  @func()
  pipelineCheck(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Dagger.Directory,
  ): Promise<string> {
    return pipelineCheck(source).stdout();
  }
}
