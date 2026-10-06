import * as Dagger from "@dagger.io/dagger";
import { JDK_IMAGE } from "../buildEnv";

const COURSIER_VERSION = "2.1.26";
const COURSIER_SHA256 = "eb0c35379e76f44404f777ab0a2335ca5326f6b7721b8cdfa873e87bd9f4ac0c";
const SCALAFMT_VERSION = "3.11.5";

/** Runs Scala compilation, tests, and static checks in a pinned JDK + sbt container. */
export function backendCheck(source: Dagger.Directory): Dagger.Container {
  const checks = [
    "sbt -batch -Dsbt.color=false compile",
    "sbt -batch -Dsbt.color=false test",
    "sbt -batch -Dsbt.color=false 'scalafixAll --check'",
    `curl -fsSL https://github.com/coursier/coursier/releases/download/v${COURSIER_VERSION}/coursier -o /tmp/coursier`,
    `printf '${COURSIER_SHA256}  /tmp/coursier\\n' | sha256sum -c -`,
    "chmod +x /tmp/coursier",
    `/tmp/coursier bootstrap org.scalameta:scalafmt-cli_2.13:${SCALAFMT_VERSION} --main org.scalafmt.cli.Cli --output /tmp/scalafmt`,
    "/tmp/scalafmt --test --config .scalafmt.conf src build.sbt project/plugins.sbt",
  ].join(" && ");

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
    .withExec(["sh", "-c", checks]);
}
