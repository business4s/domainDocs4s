import { defineConfig } from "vite";
import scalaJSPlugin from "@scala-js/vite-plugin-scalajs";
import path from "path";

export default defineConfig({
  plugins: [
    scalaJSPlugin({
      cwd: "..",
      projectID: "viewerCy",
    }),
  ],
  resolve: {
    alias: {
      "elk-routed": path.resolve(__dirname, "src/elk-routed.js"),
    },
  },
  build: {
    outDir: "dist",
    target: "es2022",
  },
});
