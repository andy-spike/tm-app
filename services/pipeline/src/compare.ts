import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import {
  decodeBundle,
  DeviceRouter,
  MotisClient,
  type TripRequest,
} from "@tm/core";
const root = resolve(import.meta.dirname, "../out");
const { version } = JSON.parse(
  await readFile(resolve(root, "latest.json"), "utf8"),
) as { version: string };
let start = performance.now();
const bundle = decodeBundle(
  await readFile(resolve(root, version, "timetable.bin")),
);
const decodeMs = performance.now() - start;
const motisUrl = process.env.MOTIS_URL ?? "http://localhost:8080";
const router = new DeviceRouter(bundle),
  server = new MotisClient(motisUrl, {
    timeoutMs: 10000,
  });
let online = false;
try {
  online = (
    await fetch(`${motisUrl}/api/v1/health`, {
      signal: AbortSignal.timeout(1000),
    })
  ).ok;
} catch {
  /* Offline benchmark remains useful. */
}
const served = [...new Set(bundle.patterns.flatMap((p) => p.stops))];
let seed = 20261022;
const random = () => {
  seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
  return seed / 2 ** 32;
};
const timings: number[] = [];
const records = [];
let localFound = 0,
  serverFound = 0,
  both = 0,
  within10 = 0,
  sameOrFewer = 0,
  meetsBoth = 0,
  errors = 0;
for (let i = 0; i < Number(process.env.TM_COMPARE_COUNT ?? 100); i++) {
  const from = bundle.stops[served[Math.floor(random() * served.length)]],
    to = bundle.stops[served[Math.floor(random() * served.length)]];
  const request: TripRequest = {
    from,
    to,
    time: `${bundle.feedStart.slice(0, 4)}-10-22T${String(6 + Math.floor(random() * 14)).padStart(2, "0")}:00:00-05:00`,
  };
  start = performance.now();
  const local = await router.plan(request);
  timings.push(performance.now() - start);
  const l = [...local.itineraries].sort((a, b) =>
    a.arrival.localeCompare(b.arrival),
  )[0];
  if (l) localFound++;
  let remote;
  if (online) {
    try {
      remote = await server.plan(request);
    } catch {
      errors++;
    }
  }
  const s = remote?.itineraries
    .slice()
    .sort((a, b) => a.arrival.localeCompare(b.arrival))[0];
  if (s) serverFound++;
  if (l && s) {
    both++;
    const within = Date.parse(l.arrival) <= Date.parse(s.arrival) + 600000,
      fewer = l.transfers <= s.transfers;
    if (within) within10++;
    if (fewer) sameOrFewer++;
    if (within && fewer) meetsBoth++;
  }
  records.push({
    from: from.id,
    to: to.id,
    time: request.time,
    localArrival: l?.arrival,
    serverArrival: s?.arrival,
    localTransfers: l?.transfers,
    serverTransfers: s?.transfers,
    queryMs: timings.at(-1),
  });
  if ((i + 1) % 10 === 0) console.log(`Compared ${i + 1} pairs`);
}
timings.sort((a, b) => a - b);
const report = {
  version,
  pairs: timings.length,
  serverAvailable: online,
  decodeMs,
  queryMs: {
    median: timings[Math.floor(timings.length / 2)],
    p95: timings[Math.floor(timings.length * 0.95)],
    max: timings.at(-1),
  },
  localFound,
  serverFound,
  both,
  within10,
  sameOrFewer,
  meetsBoth,
  errors,
  records,
};
await writeFile(
  resolve(root, version, "comparison.json"),
  JSON.stringify(report, null, 2),
);
console.log(JSON.stringify({ ...report, records: undefined }, null, 2));
