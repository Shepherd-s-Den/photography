/** Base images and shared constants for the pipeline. */
export const JDK_IMAGE = "sbtscala/scala-sbt:eclipse-temurin-25.0.4_7_1.13.0_3.8.4";
export const NODE_IMAGE = "node:24-slim";

/** Trees the pipeline never needs; `.git` remains available for affected-path selection. */
export const WORKSPACE_IGNORE = [
  "**/node_modules",
  "**/target",
  "**/dist",
  "**/.bsp",
  "**/.scala-build",
  ".dagger/sdk",
];
