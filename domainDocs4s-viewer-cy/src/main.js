import { main } from "scalajs:main.js";

async function init() {
  const scriptTag = document.getElementById("lineage-data");
  let jsonStr;
  if (scriptTag && scriptTag.textContent.trim() !== "{}" && scriptTag.textContent.trim() !== "") {
    jsonStr = scriptTag.textContent;
  } else {
    // Try multi-service first, fall back to single-service
    let resp = await fetch("./multi-data.json");
    if (resp.ok) {
      jsonStr = await resp.text();
    } else {
      resp = await fetch("./data-pipeline.json");
      jsonStr = await resp.text();
    }
  }
  window.__LINEAGE_DATA__ = jsonStr;
  main();
}

init();
