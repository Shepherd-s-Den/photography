import { type Container, dag, type Directory } from "@dagger.io/dagger";
import { NODE_IMAGE } from "../buildEnv";

/** Runs the frontend's type, lint, format, boundary, coverage, and build gates. */
export function frontendCheck(source: Directory): Container {
  return dag
    .container()
    .from(NODE_IMAGE)
    .withMountedCache("/root/.npm", dag.cacheVolume("photography-frontend-npm"))
    .withDirectory("/app/frontend", source.directory("frontend"))
    .withWorkdir("/app/frontend")
    .withExec(["npm", "ci", "--no-audit", "--no-fund"])
    .withExec(["npm", "run", "verify"])
    .withExec(["npm", "run", "build"]);
}
