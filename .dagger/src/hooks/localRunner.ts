import { dag, type Directory } from "@dagger.io/dagger";

/** Runs the standard-library local backend launcher tests. */
export function localRunnerCheck(source: Directory) {
  return dag
    .container()
    .from("python:3.14-slim")
    .withDirectory("/work/scripts", source.directory("scripts"))
    .withWorkdir("/work")
    .withExec(["python3", "-m", "unittest", "discover", "-s", "scripts", "-p", "test_*.py"]);
}
