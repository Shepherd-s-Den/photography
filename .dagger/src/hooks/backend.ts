import * as Dagger from "@dagger.io/dagger";
import { JDK_IMAGE } from "../buildEnv";

/** Runs compilation, tests, and static checks in one pinned sbt invocation. */
export function backendCheck(source: Dagger.Directory): Dagger.Container {
  return Dagger.dag
    .container()
    .from(JDK_IMAGE)
    .withUser("root")
    .withEnvVariable("HOME", "/root")
    .withMountedCache("/root/.cache/coursier", Dagger.dag.cacheVolume("photography-coursier"))
    .withMountedCache("/root/.sbt", Dagger.dag.cacheVolume("photography-sbt"))
    .withMountedCache("/root/.ivy2", Dagger.dag.cacheVolume("photography-ivy2"))
    .withDirectory("/work", source.directory("backend"))
    .withWorkdir("/work")
    .withExec([
      "sbt",
      "-batch",
      "-Dsbt.color=false",
      "compile; test; scalafixAll --check; scalafmtCheckAll; scalafmtSbtCheck",
    ]);
}
