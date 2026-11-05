import { spawn } from "node:child_process";
import { DatabaseSync } from "node:sqlite";
import {
  mkdir,
  readFile,
  writeFile,
  rm,
  copyFile,
  stat,
} from "node:fs/promises";
import { createHash } from "node:crypto";
import { resolve } from "node:path";
import { parse } from "csv-parse";
import {
  distance,
  walkingSeconds,
  encodeBundle,
  systemKind,
  type Timetable,
  type Pattern,
  type Place,
  type Service,
  type BundleManifest,
} from "@tm/core";
const root = resolve(import.meta.dirname, "../../..");
const input = resolve(root, "services/motis/input/transmilenio.gtfs.zip");
let mapPath = process.env.TM_PMTILES_PATH;
if (!mapPath) {
  const candidate = resolve(root, "services/pipeline/.tmp/bogota.pmtiles");
  try {
    await stat(candidate);
    mapPath = candidate;
  } catch {
    /* Optional map extract has not been downloaded. */
  }
}
type Row = Record<string, string>;
async function* rows(name: string): AsyncGenerator<Row> {
  const child = spawn("unzip", ["-p", input, name], {
    stdio: ["ignore", "pipe", "pipe"],
  });
  let stderr = "";
  child.stderr.on("data", (b: Buffer) => {
    stderr += b.toString();
  });
  const exited = new Promise<void>((res, rej) => {
    child.on("error", rej);
    child.on("close", (code) =>
      code === 0 ? res() : rej(new Error(`unzip ${name}: ${stderr}`)),
    );
  });
  const parser = child.stdout.pipe(
    parse({ columns: true, bom: true, skip_empty_lines: true, trim: true }),
  );
  for await (const row of parser) yield row as Row;
  await exited;
}
const collect = async (name: string) => {
  const result: Row[] = [];
  for await (const row of rows(name)) result.push(row);
  return result;
};
const date = (s: string) =>
  `${s.slice(0, 4)}-${s.slice(4, 6)}-${s.slice(6, 8)}`;
const seconds = (s: string) => {
  const parts = s.split(":").map(Number);
  const n = parts[0] * 3600 + parts[1] * 60 + parts[2];
  if (!Number.isFinite(n) || n < 0) throw new Error(`Invalid GTFS time: ${s}`);
  return n;
};
const required = <T>(map: Map<string, T>, id: string, kind: string): T => {
  const value = map.get(id);
  if (value === undefined) throw new Error(`Missing ${kind} ${id}`);
  return value;
};
await mkdir(resolve(root, "services/pipeline/.tmp"), { recursive: true });
const temp = resolve(root, "services/pipeline/.tmp/stop-times.sqlite");
await rm(temp, { force: true });
const db = new DatabaseSync(temp);
try {
  const info = (await collect("feed_info.txt"))[0];
  const feedStart = date(info.feed_start_date),
    feedEnd = date(info.feed_end_date);
  if (feedEnd < new Date().toISOString().slice(0, 10))
    throw new Error(`Feed expired ${feedEnd}`);
  const version = `${feedStart}_${new Date().toISOString().replace(/[-:.]/g, "")}`;
  const agencies = new Set(
    (await collect("agency.txt")).map((r) => r.agency_id),
  );
  const routeRows = await collect("routes.txt");
  const routes = routeRows.map((r) => {
    if (!agencies.has(r.agency_id))
      throw new Error(`Missing agency ${r.agency_id}`);
    return {
      id: `tm_${r.route_id}`,
      shortName: r.route_short_name,
      color: `#${r.route_color || "666666"}`,
      agencyId: r.agency_id,
      system: systemKind(r.agency_id),
    };
  });
  const routeIds = new Map(routeRows.map((r, i) => [r.route_id, i]));
  const stopRows = await collect("stops.txt");
  const stops: Place[] = stopRows.map((s) => {
    const lat = Number(s.stop_lat),
      lon = Number(s.stop_lon);
    if (
      !Number.isFinite(lat) ||
      !Number.isFinite(lon) ||
      Math.abs(lat) > 90 ||
      Math.abs(lon) > 180
    )
      throw new Error(`Invalid stop ${s.stop_id}`);
    return {
      id: `tm_${s.stop_id}`,
      name: s.stop_name,
      lat,
      lon,
      kind: s.location_type === "1" ? "station" : "stop",
      parentStationId: s.parent_station ? `tm_${s.parent_station}` : undefined,
      code: s.stop_code || undefined,
    };
  });
  const stopIds = new Map(stopRows.map((s, i) => [s.stop_id, i]));
  for (const s of stopRows)
    if (s.parent_station) required(stopIds, s.parent_station, "parent station");
  const serviceRows = await collect("calendar.txt");
  const services: Service[] = serviceRows.map((s) => ({
    start: date(s.start_date),
    end: date(s.end_date),
    weekdays: [
      "monday",
      "tuesday",
      "wednesday",
      "thursday",
      "friday",
      "saturday",
      "sunday",
    ].map((d) => Number(s[d])),
    exceptions: {},
  }));
  const serviceIds = new Map(serviceRows.map((s, i) => [s.service_id, i]));
  for (const r of await collect("calendar_dates.txt")) {
    if (!serviceIds.has(r.service_id)) {
      serviceIds.set(r.service_id, services.length);
      services.push({
        start: feedStart,
        end: feedEnd,
        weekdays: [0, 0, 0, 0, 0, 0, 0],
        exceptions: {},
      });
    }
    services[required(serviceIds, r.service_id, "service")].exceptions[
      date(r.date)
    ] = r.exception_type === "1";
  }
  const tripRows = await collect("trips.txt");
  const trips = new Map(
    tripRows.map((t) => [
      t.trip_id,
      {
        route: required(routeIds, t.route_id, "route"),
        service: required(serviceIds, t.service_id, "service"),
        headsign: t.trip_headsign ?? "",
      },
    ]),
  );
  const frequencies = new Map<
    string,
    { start: number; end: number; headway: number; exact: boolean }[]
  >();
  for (const f of await collect("frequencies.txt")) {
    required(trips, f.trip_id, "frequency trip");
    const item = {
      start: seconds(f.start_time),
      end: seconds(f.end_time),
      headway: Number(f.headway_secs),
      exact: f.exact_times === "1",
    };
    if (item.headway <= 0 || item.end <= item.start)
      throw new Error("Invalid frequency");
    const list = frequencies.get(f.trip_id) ?? [];
    list.push(item);
    frequencies.set(f.trip_id, list);
  }
  db.exec(
    "PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF; PRAGMA temp_store=FILE; CREATE TABLE times(trip TEXT, seq INTEGER, stop INTEGER, arr INTEGER, dep INTEGER, pickup INTEGER, dropoff INTEGER); BEGIN",
  );
  const insert = db.prepare("INSERT INTO times VALUES(?,?,?,?,?,?,?)");
  let count = 0;
  for await (const r of rows("stop_times.txt")) {
    required(trips, r.trip_id, "stop_times trip");
    const stop = required(stopIds, r.stop_id, "stop");
    const arr = seconds(r.arrival_time),
      dep = seconds(r.departure_time);
    if (dep < arr) throw new Error("Departure before arrival");
    insert.run(
      r.trip_id,
      Number(r.stop_sequence),
      stop,
      arr,
      dep,
      Number(r.pickup_type || 0),
      Number(r.drop_off_type || 0),
    );
    if (++count % 1000000 === 0)
      console.log(`Read ${count.toLocaleString()} stop times`);
  }
  db.exec("COMMIT; CREATE INDEX trip_order ON times(trip,seq)");
  const patterns: Pattern[] = [],
    patternIds = new Map<string, number>(),
    profileIds: Map<string, number>[] = [];
  interface TimeRow {
    trip: string;
    seq: number;
    stop: number;
    arr: number;
    dep: number;
    pickup: number;
    dropoff: number;
  }
  let current: TimeRow[] = [],
    seenTrips = 0;
  function flush() {
    if (!current.length) return;
    seenTrips++;
    const id = current[0].trip,
      trip = required(trips, id, "trip"),
      base = current[0].dep;
    for (let i = 1; i < current.length; i++)
      if (
        current[i].arr < current[i - 1].dep ||
        current[i].seq <= current[i - 1].seq
      )
        throw new Error(`Non-monotonic trip ${id}`);
    const key = JSON.stringify([
      trip.route,
      trip.headsign,
      current.map((r) => r.stop),
    ]);
    let pi = patternIds.get(key);
    if (pi === undefined) {
      pi = patterns.length;
      patternIds.set(key, pi);
      patterns.push({
        route: trip.route,
        headsign: trip.headsign,
        stops: current.map((r) => r.stop),
        profiles: [],
        trips: [],
      });
      profileIds.push(new Map());
    }
    const p = patterns[pi],
      profile = {
        times: current.flatMap((r) => [r.arr - base, r.dep - base]),
        pickup: current.map((r) => r.pickup),
        dropoff: current.map((r) => r.dropoff),
      };
    const pk = JSON.stringify(profile);
    let profileIndex = profileIds[pi].get(pk);
    if (profileIndex === undefined) {
      profileIndex = p.profiles.length;
      profileIds[pi].set(pk, profileIndex);
      p.profiles.push(profile);
    }
    const freq = frequencies.get(id);
    if (freq)
      for (const f of freq)
        p.trips.push({
          departure: f.start,
          profile: profileIndex,
          service: trip.service,
          frequency: { end: f.end, headway: f.headway, exact: f.exact },
        });
    else
      p.trips.push({
        departure: base,
        profile: profileIndex,
        service: trip.service,
      });
  }
  for (const raw of db
    .prepare("SELECT * FROM times ORDER BY trip,seq")
    .iterate()) {
    const row = raw as unknown as TimeRow;
    if (current.length && row.trip !== current[0].trip) {
      flush();
      current = [];
    }
    current.push(row);
  }
  flush();
  const warnings =
    trips.size === seenTrips
      ? []
      : [
          `${trips.size - seenTrips} trips have no stop times and were excluded`,
        ];
  warnings.forEach((warning) => console.warn(warning));
  patterns.forEach((p) => p.trips.sort((a, b) => a.departure - b.departure));
  const grid = new Map<string, number[]>();
  const cell = (p: Place) => [
    Math.floor(p.lat / 0.004),
    Math.floor(p.lon / 0.004),
  ];
  stops.forEach((s, i) => {
    const key = cell(s).join(",");
    const list = grid.get(key) ?? [];
    list.push(i);
    grid.set(key, list);
  });
  const footpaths = stops.map((s, i) => {
    const [x, y] = cell(s);
    const result: { to: number; seconds: number }[] = [];
    for (let dx = -1; dx <= 1; dx++)
      for (let dy = -1; dy <= 1; dy++)
        for (const to of grid.get(`${x + dx},${y + dy}`) ?? []) {
          const d = distance(s, stops[to]);
          if (to !== i && d <= 400)
            result.push({ to, seconds: Math.max(1, walkingSeconds(d)) });
        }
    return result;
  });
  const builtAt = new Date().toISOString();
  const bundle: Timetable = {
    version,
    builtAt,
    feedStart,
    feedEnd,
    stops,
    routes,
    services,
    patterns,
    footpaths,
  };
  const out = resolve(root, "services/pipeline/out", version);
  await mkdir(out, { recursive: true });
  await writeFile(resolve(out, "timetable.bin"), encodeBundle(bundle));
  await writeFile(resolve(out, "places.json"), JSON.stringify(stops));
  // A locally provided archive is optional; routing builds never depend on a map provider.
  if (mapPath) await copyFile(mapPath, resolve(out, "bogota.pmtiles"));
  const names = [
    "timetable.bin",
    "places.json",
    ...(mapPath ? ["bogota.pmtiles"] : []),
  ];
  const files = await Promise.all(
    names.map(async (name) => ({
      name,
      size: (await stat(resolve(out, name))).size,
      sha256: createHash("sha256")
        .update(await readFile(resolve(out, name)))
        .digest("hex"),
    })),
  );
  const manifest: BundleManifest = {
    schemaVersion: 1,
    version,
    createdAt: builtAt,
    builtAt,
    feedStart,
    feedEnd,
    files,
  };
  await writeFile(
    resolve(out, "validation.json"),
    JSON.stringify(
      {
        warnings,
        inputTrips: trips.size,
        routedTrips: seenTrips,
        stopTimes: count,
      },
      null,
      2,
    ),
  );
  await writeFile(
    resolve(out, "manifest.json"),
    JSON.stringify(manifest, null, 2),
  );
  await writeFile(
    resolve(root, "services/pipeline/out/latest.json"),
    JSON.stringify({ version }),
  );
  console.log(
    JSON.stringify(
      {
        version,
        stops: stops.length,
        routes: routes.length,
        trips: seenTrips,
        patterns: patterns.length,
        profiles: patterns.reduce((n, p) => n + p.profiles.length, 0),
        files,
      },
      null,
      2,
    ),
  );
} finally {
  db.close();
  await rm(temp, { force: true });
}
