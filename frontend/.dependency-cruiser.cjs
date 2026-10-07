/** @type {import('dependency-cruiser').IConfiguration} */
module.exports = {
  forbidden: [
    {
      name: "shared-stays-independent",
      comment: "Shared UI and utilities stay independent of app composition and product features.",
      severity: "error",
      from: { path: "^src/shared/" },
      to: { path: "^src/(app|features)/" },
    },
    {
      name: "features-do-not-import-app",
      comment: "Feature modules do not depend on the application composition root.",
      severity: "error",
      from: { path: "^src/features/" },
      to: { path: "^src/app/" },
    },
    {
      name: "no-circular",
      comment: "No circular dependencies.",
      severity: "error",
      from: {},
      to: { circular: true },
    },
    {
      name: "no-orphans",
      comment: "No orphan modules (the entrypoint is exempt).",
      severity: "warn",
      from: { orphan: true, pathNot: ["\\.d\\.ts$", "(^|/)main\\.ts$"] },
      to: {},
    },
  ],
  options: {
    doNotFollow: { path: "node_modules" },
    tsConfig: { fileName: "tsconfig.json" },
    tsPreCompilationDeps: true,
    enhancedResolveOptions: { extensions: [".ts", ".svelte"] },
  },
};
