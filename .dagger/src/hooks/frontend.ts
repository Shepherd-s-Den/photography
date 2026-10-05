import { type Container, dag, type Directory } from "@dagger.io/dagger";
import { NODE_IMAGE } from "../buildEnv";

/** Checks the minimal static HTML entry point without installing frontend dependencies. */
export function frontendCheck(source: Directory): Container {
  return dag
    .container()
    .from(NODE_IMAGE)
    .withDirectory("/app/frontend", source.directory("frontend"))
    .withWorkdir("/app/frontend")
    .withExec([
      "node",
      "-e",
      "const fs=require('node:fs'); const html=fs.readFileSync('index.html','utf8'); if(!html.includes('<html') || !html.includes('<title>Photography</title>')) process.exit(1);",
    ]);
}
