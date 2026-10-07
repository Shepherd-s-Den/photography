import { svelte } from "@sveltejs/vite-plugin-svelte";
import { defineConfig } from "vite";

const backend = process.env["VITE_API_TARGET"] ?? "http://127.0.0.1:8080";

export default defineConfig({
  plugins: [svelte()],
  server: {
    host: "127.0.0.1",
    strictPort: true,
    proxy: {
      "/api": backend,
      "/health": backend,
    },
  },
  build: {
    outDir: "dist",
    target: "es2023",
  },
});
