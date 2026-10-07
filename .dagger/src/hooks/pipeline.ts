import { type Container, dag, type Directory } from "@dagger.io/dagger";
import { NODE_IMAGE, PYTHON_IMAGE } from "../buildEnv";

/** Runs the pipeline module's type, lint, format, and unit-test gates. */
export function pipelineCheck(source: Directory): Container {
  return dag
    .container()
    .from(NODE_IMAGE)
    .withMountedCache("/root/.npm", dag.cacheVolume("photography-npm"))
    .withDirectory("/ci", source.directory(".dagger"))
    .withWorkdir("/ci")
    .withExec(["npm", "ci", "--no-audit", "--no-fund"])
    .withExec(["npx", "vitest", "run", "--coverage"]);
}

export function localSetupCheck(source: Directory): Container {
  return dag
    .container()
    .from(PYTHON_IMAGE)
    .withDirectory("/work/scripts", source.directory("scripts"))
    .withWorkdir("/work")
    .withExec(["python3", "-m", "unittest", "discover", "-s", "scripts", "-p", "test_*.py"]);
}
