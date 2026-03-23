import { defineConfig } from "vite";
import path from "path";

// E2E test config: serves pre-built fastLinkJS output without invoking sbt
export default defineConfig({
  resolve: {
    alias: {
      "scalajs:main.js": path.resolve(
        __dirname,
        "../domainDocs4s-viewer-cy/target/scala-3.8.2/domaindocs4s-viewer-cy-fastopt/main.js"
      ),
      "elk-routed": path.resolve(__dirname, "src/elk-routed.js"),
    },
  },
  build: {
    outDir: "dist",
    target: "es2022",
  },
});
