// Focused, repeatable checks for the emulator-reported OD pair and cold decoder.
// Run: pnpm --filter @tm/pipeline exec tsx src/inspect.ts
import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import {
  decodeBundleAsync,
  DeviceRouter,
  OfflinePlaceSearch,
  MotisClient,
  mergePlaces,
  type DecodeTiming,
} from "@tm/core";
const root = resolve(import.meta.dirname, "../out");
const { version } = JSON.parse(
  await readFile(resolve(root, "latest.json"), "utf8"),
);
const bytes = await readFile(resolve(root, version, "timetable.bin"));
let decode: DecodeTiming | undefined;
const bundle = await decodeBundleAsync(bytes, {
  onTiming: (timing) => {
    decode = timing;
  },
});
let start = performance.now();
const router = new DeviceRouter(bundle);
const routerConstructionMs = performance.now() - start;
start = performance.now();
const search = new OfflinePlaceSearch(bundle.stops),
  server = new MotisClient();
const searchConstructionMs = performance.now() - start;
const searches = [];
for (const query of [
  "portal norte",
  "portal norte 2-10",
  "universidad nacional",
]) {
  const local = await search.search(query),
    remote = await server.search(query);
  searches.push({ query, local, merged: mergePlaces(local, remote, query) });
}
const request = {
  from: bundle.stops.find((p) => p.id === "tm_2000")!,
  to: bundle.stops.find((p) => p.id === "tm_7107")!,
  time: "2026-10-22T16:13:00-05:00",
};
const timings: number[] = [];
let local;
for (let i = 0; i < 25; i++) {
  start = performance.now();
  local = await router.plan(request);
  timings.push(performance.now() - start);
}
const coldQueryMs = timings[0];
timings.sort((a, b) => a - b);
const report = {
  version,
  decode,
  routerConstructionMs,
  searchConstructionMs,
  coldQueryMs,
  queryMs: { p50: timings[12], p95: timings[23], max: timings[24] },
  searches,
  local,
  server: await server.plan(request),
};
await writeFile(
  resolve(root, version, "inspection.json"),
  JSON.stringify(report, null, 2),
);
console.log(
  JSON.stringify(
    {
      ...report,
      searches: searches.map((s) => ({
        query: s.query,
        local: s.local.map((p) => p.name),
        merged: s.merged.map((p) => p.name),
      })),
      local: report.local?.itineraries.map((i) => ({
        departure: i.departure,
        arrival: i.arrival,
        transfers: i.transfers,
        routes: i.legs.flatMap((l) =>
          l.mode === "transit" ? [`${l.route.shortName} → ${l.headsign}`] : [],
        ),
      })),
      server: report.server.itineraries.length,
    },
    null,
    2,
  ),
);
