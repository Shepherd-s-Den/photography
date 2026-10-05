import { type Container, dag, type Directory } from "@dagger.io/dagger";

const GIT_IMAGE = "alpine/git:latest";

function gitContainer(source: Directory): Container {
  return dag.container().from(GIT_IMAGE).withMountedDirectory("/repo", source).withWorkdir("/repo");
}

/** `git diff --name-only` between HEAD and the base (default: merge-base with origin/main, with
 * fallbacks so it still works on a shallow or single-commit history).
 */
export async function changedPaths(source: Directory, base: string): Promise<string> {
  const ref = base === "" ? "origin/main" : base;
  const script =
    'git diff --name-only "$(git merge-base "$1" HEAD 2>/dev/null || git rev-list --max-parents=0 HEAD | tail -1)" HEAD';
  return gitContainer(source).withExec(["sh", "-c", script, "sh", ref]).stdout();
}

export async function gitDescribe(source: Directory): Promise<string> {
  return gitContainer(source)
    .withExec(["git", "describe", "--tags", "--always", "--dirty"])
    .stdout();
}

export async function headSha(source: Directory): Promise<string> {
  return gitContainer(source).withExec(["git", "rev-parse", "HEAD"]).stdout();
}
