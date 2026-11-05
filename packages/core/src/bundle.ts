import { deflateSync, inflateSync, Inflate, strToU8, strFromU8 } from "fflate";
import type { Place, Route } from "./domain";
export interface Service {
  start: string;
  end: string;
  weekdays: number[];
  exceptions: Record<string, boolean>;
}
/** Alternating arrival/departure offsets relative to the first departure. */
export interface TimingProfile {
  times: number[];
  pickup: number[];
  dropoff: number[];
}
export interface TimetableTrip {
  departure: number;
  profile: number;
  service: number;
  frequency?: { end: number; headway: number; exact: boolean };
}
export interface Pattern {
  route: number;
  headsign: string;
  stops: number[];
  profiles: TimingProfile[];
  trips: TimetableTrip[];
}
export interface Footpath {
  to: number;
  seconds: number;
}
export interface Timetable {
  version: string;
  builtAt?: string;
  feedStart: string;
  feedEnd: string;
  stops: Place[];
  routes: Route[];
  services: Service[];
  patterns: Pattern[];
  footpaths: Footpath[][];
}
export interface BundleManifest {
  schemaVersion: 1;
  version: string;
  builtAt?: string;
  createdAt: string;
  feedStart: string;
  feedEnd: string;
  files: { name: string; size: number; sha256: string }[];
}
const encoder = { encode: strToU8 };
/** Unsigned LEB128 with a fixed header and JSON metadata, see BUNDLE_FORMAT.md. */
export function encodeBundle(bundle: Timetable): Uint8Array {
  const { patterns, footpaths, ...metadata } = bundle;
  const meta = encoder.encode(JSON.stringify(metadata));
  const out: number[] = [];
  const put = (n: number) => {
    if (!Number.isSafeInteger(n) || n < 0)
      throw new Error("Invalid unsigned integer");
    do {
      const b = n % 128;
      n = Math.floor(n / 128);
      out.push(b | (n ? 128 : 0));
    } while (n);
  };
  const str = (s: string) => {
    const b = encoder.encode(s);
    put(b.length);
    for (const v of b) out.push(v);
  };
  put(patterns.length);
  for (const p of patterns) {
    put(p.route);
    str(p.headsign);
    put(p.stops.length);
    p.stops.forEach(put);
    put(p.profiles.length);
    for (const profile of p.profiles) {
      let prev = 0;
      profile.times.forEach((t) => {
        const d = t - prev;
        put(d >= 0 ? d * 2 : -d * 2 - 1);
        prev = t;
      });
      profile.pickup.forEach(put);
      profile.dropoff.forEach(put);
    }
    put(p.trips.length);
    let prev = 0;
    for (const t of p.trips) {
      put(t.departure - prev);
      prev = t.departure;
      put(t.profile);
      put(t.service);
      put(t.frequency ? 1 : 0);
      if (t.frequency) {
        put(t.frequency.end);
        put(t.frequency.headway);
        put(t.frequency.exact ? 1 : 0);
      }
    }
  }
  put(footpaths.length);
  for (const paths of footpaths) {
    put(paths.length);
    for (const f of paths) {
      put(f.to);
      put(f.seconds);
    }
  }
  const payload = deflateSync(new Uint8Array(out));
  const bytes = new Uint8Array(16 + meta.length + payload.length);
  bytes.set(encoder.encode("TMBG"));
  const view = new DataView(bytes.buffer);
  view.setUint32(4, 3, true);
  view.setUint32(8, meta.length, true);
  view.setUint32(12, payload.length, true);
  bytes.set(meta, 16);
  bytes.set(payload, 16 + meta.length);
  return bytes;
}
function* decodeSteps(
  bytes: Uint8Array,
  inflated?: Uint8Array,
): Generator<void, Timetable> {
  if (bytes.length < 16) throw new Error("Truncated bundle");
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const decoder = { decode: strFromU8 };
  if (
    decoder.decode(bytes.subarray(0, 4)) !== "TMBG" ||
    ![1, 2, 3].includes(view.getUint32(4, true))
  )
    throw new Error("Unsupported bundle");
  const end = 16 + view.getUint32(8, true);
  if (end + view.getUint32(12, true) !== bytes.length)
    throw new Error("Invalid bundle length");
  const meta = JSON.parse(decoder.decode(bytes.subarray(16, end))) as Omit<
    Timetable,
    "patterns" | "footpaths"
  >;
  if (
    !Array.isArray(meta.stops) ||
    !Array.isArray(meta.routes) ||
    !Array.isArray(meta.services) ||
    typeof meta.version !== "string"
  )
    throw new Error("Invalid bundle metadata");
  const body =
    view.getUint32(4, true) >= 2
      ? (inflated ?? inflateSync(bytes.subarray(end)))
      : bytes.subarray(end);
  let pos = 0;
  const get = (): number => {
    let n = 0,
      factor = 1;
    for (let i = 0; i < 8; i++) {
      if (pos >= body.length) throw new Error("Truncated varint");
      const b = body[pos++];
      n += (b & 127) * factor;
      if (!Number.isSafeInteger(n)) throw new Error("Varint overflow");
      if (!(b & 128)) return n;
      factor *= 128;
    }
    throw new Error("Varint overflow");
  };
  const count = (): number => {
    const n = get();
    if (n > body.length - pos) throw new Error("Invalid count");
    return n;
  };
  const str = () => {
    const n = count();
    const s = decoder.decode(body.subarray(pos, pos + n));
    pos += n;
    return s;
  };
  const patterns: Pattern[] = [];
  for (let n = count(); n > 0; n--) {
    const route = get(),
      headsign = str(),
      size = count();
    const stops = Array.from({ length: size }, get);
    if (!meta.routes[route] || stops.some((s) => !meta.stops[s]))
      throw new Error("Invalid pattern reference");
    const profiles: TimingProfile[] = [];
    for (let j = count(); j > 0; j--) {
      let prev = 0;
      const times = Array.from({ length: size * 2 }, () => {
        const d = get();
        prev += d % 2 ? -(d + 1) / 2 : d / 2;
        return prev;
      });
      if (j % 256 === 0) yield;
      profiles.push({
        times,
        pickup: Array.from({ length: size }, get),
        dropoff: Array.from({ length: size }, get),
      });
    }
    let departure = 0;
    const trips: TimetableTrip[] = [];
    for (let j = count(); j > 0; j--) {
      departure += get();
      const profile = get(),
        service = get(),
        hasFrequency = get();
      const frequency = hasFrequency
        ? { end: get(), headway: get(), exact: !!get() }
        : undefined;
      if (
        !profiles[profile] ||
        !meta.services[service] ||
        (frequency && frequency.headway <= 0)
      )
        throw new Error("Invalid trip reference");
      trips.push({ departure, profile, service, frequency });
      if (j % 2048 === 0) yield;
    }
    patterns.push({ route, headsign, stops, profiles, trips });
    yield;
  }
  const footpaths: Footpath[][] = [];
  for (let n = count(); n > 0; n--) {
    const paths = Array.from({ length: count() }, () => {
      const to = get(),
        seconds = get();
      if (!meta.stops[to]) throw new Error("Invalid footpath");
      return { to, seconds };
    });
    footpaths.push(paths);
    if (n % 64 === 0) yield;
  }
  if (pos !== body.length || footpaths.length !== meta.stops.length)
    throw new Error("Invalid bundle tail");
  return { ...meta, patterns, footpaths };
}

/** Synchronous codec for build tools. Mobile should use decodeBundleAsync. */
export function decodeBundle(bytes: Uint8Array): Timetable {
  const steps = decodeSteps(bytes);
  let step = steps.next();
  while (!step.done) step = steps.next();
  return step.value;
}
export interface DecodeTiming {
  durationMs: number;
  maxBlockingMs: number;
  yields: number;
}
export interface DecodeOptions {
  signal?: AbortSignal;
  yieldControl?: () => Promise<void>;
  onTiming?: (timing: DecodeTiming) => void;
}
/** Incremental inflate and cooperative parser keep rendering/network callbacks moving. */
export async function decodeBundleAsync(
  bytes: Uint8Array,
  options: DecodeOptions = {},
): Promise<Timetable> {
  const start = performance.now();
  let slice = start,
    maxBlockingMs = 0,
    yields = 0;
  const checkpoint = async () => {
    options.signal?.throwIfAborted();
    const elapsed = performance.now() - slice;
    if (elapsed < 8) return;
    maxBlockingMs = Math.max(maxBlockingMs, elapsed);
    yields++;
    await (options.yieldControl?.() ??
      new Promise<void>((resolve) => setTimeout(resolve, 0)));
    slice = performance.now();
    options.signal?.throwIfAborted();
  };
  options.signal?.throwIfAborted();
  let body: Uint8Array | undefined;
  if (bytes.length >= 16) {
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const revision = view.getUint32(4, true),
      end = 16 + view.getUint32(8, true);
    if (
      [2, 3].includes(revision) &&
      end + view.getUint32(12, true) === bytes.length
    ) {
      const chunks: Uint8Array[] = [];
      let size = 0;
      const stream = new Inflate((chunk) => {
        chunks.push(chunk);
        size += chunk.length;
      });
      for (let offset = end; offset < bytes.length; offset += 16384) {
        const next = Math.min(offset + 16384, bytes.length);
        stream.push(bytes.subarray(offset, next), next === bytes.length);
        await checkpoint();
      }
      body = new Uint8Array(size);
      let offset = 0;
      for (const chunk of chunks) {
        body.set(chunk, offset);
        offset += chunk.length;
      }
      await checkpoint();
    }
  }
  const steps = decodeSteps(bytes, body);
  let step = steps.next();
  while (!step.done) {
    await checkpoint();
    step = steps.next();
  }
  maxBlockingMs = Math.max(maxBlockingMs, performance.now() - slice);
  options.onTiming?.({
    durationMs: performance.now() - start,
    maxBlockingMs,
    yields,
  });
  return step.value;
}
