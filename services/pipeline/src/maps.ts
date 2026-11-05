import { spawn } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
const output = resolve(import.meta.dirname, "../.tmp/bogota.pmtiles");
await mkdir(resolve(output, ".."), { recursive: true });
const response = await fetch(
  "https://build-metadata.protomaps.dev/builds.json",
);
if (!response.ok) throw new Error("Unable to discover Protomaps builds");
const builds = (await response.json()) as { key: string; version: string }[];
const build = builds.at(-1);
if (!build || !/^[\w.-]+\.pmtiles$/.test(build.key))
  throw new Error("Invalid Protomaps build");
const source = `https://build.protomaps.com/${build.key}`;
const binary = process.env.PMTILES_BIN ?? "pmtiles";
await new Promise<void>((res, rej) => {
  const child = spawn(
    binary,
    [
      "extract",
      source,
      output,
      "--bbox=-74.30,4.30,-73.95,4.90",
      "--maxzoom=14",
      "--download-threads=4",
    ],
    { stdio: "inherit" },
  );
  child.on("error", rej);
  child.on("exit", (code) =>
    code === 0 ? res() : rej(new Error(`pmtiles exited ${code}`)),
  );
});
await writeFile(
  `${output}.json`,
  JSON.stringify(
    {
      source,
      basemapVersion: build.version,
      bbox: [-74.3, 4.3, -73.95, 4.9],
      maxzoom: 14,
      attribution:
        "© OpenStreetMap contributors; Protomaps basemap, ODbL produced work",
    },
    null,
    2,
  ),
);
console.log(`TM_PMTILES_PATH=${output} pnpm bundle:build`);
