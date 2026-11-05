import type {
  Coordinate,
  Itinerary,
  Leg,
  Place,
  RoutingEngine,
  TripRequest,
  TripResult,
  WalkLeg,
} from "./domain";
import { directionHeadsign } from "./motis";
import type { Pattern, Service, Timetable, TimetableTrip } from "./bundle";
export function distance(a: Coordinate, b: Coordinate): number {
  const rad = Math.PI / 180,
    x = (b.lon - a.lon) * rad * Math.cos(((a.lat + b.lat) * rad) / 2),
    y = (b.lat - a.lat) * rad;
  return Math.hypot(x, y) * 6371000;
}
export const walkingSeconds = (meters: number): number =>
  Math.ceil((meters * 1.3) / 1.25);
export function serviceActive(service: Service, day: string): boolean {
  if (day in service.exceptions) return service.exceptions[day];
  return (
    day >= service.start &&
    day <= service.end &&
    service.weekdays[(new Date(`${day}T12:00:00Z`).getUTCDay() + 6) % 7] === 1
  );
}
const iso = (n: number) => new Date(n * 1000).toISOString();
type Segment =
  | { kind: "walk"; from: Place; to: Place; start: number; end: number }
  | {
      kind: "transit";
      pattern: Pattern;
      a: number;
      b: number;
      base: number;
      profile: import("./bundle").TimingProfile;
    };
interface Path {
  segment: Segment;
  previous?: Path;
}
interface Label {
  time: number;
  edge: number;
  path: Path;
  rides: number;
}
interface Run {
  trip: TimetableTrip;
  base: number;
}
export interface RouterOptions {
  maxTransfers?: number;
  maxAccessMeters?: number;
  transferSeconds?: number;
  now?: () => Date;
  /** Subsequent departure/arrival window in seconds. The first Pareto search retains its 24h horizon. */
  searchWindowSeconds?: number;
  maxItineraries?: number;
}
/** Schedule-only round-based routing. Walking geometry is a straight line, not a safe street path. */
export class DeviceRouter implements RoutingEngine {
  private cachedDay = "";
  private runs: Run[][] = [];
  private spans: { min: number; max: number }[];
  private stationRoots = new Map<string, string>();
  constructor(
    readonly timetable: Timetable,
    private options: RouterOptions = {},
  ) {
    const byId = new Map(timetable.stops.map((s) => [s.id, s]));
    for (const stop of timetable.stops) {
      let root = stop;
      const seen = new Set<string>();
      while (root.parentStationId && !seen.has(root.id)) {
        seen.add(root.id);
        const parent = byId.get(root.parentStationId);
        if (!parent) break;
        root = parent;
      }
      this.stationRoots.set(stop.id, root.id);
    }
    this.spans = timetable.patterns.map((p) => ({
      min: Math.min(0, ...p.profiles.map((profile) => profile.times[0])),
      max:
        Math.max(0, ...p.profiles.map((profile) => profile.times.at(-1) ?? 0)) +
        p.trips.reduce(
          (max, trip) =>
            Math.max(
              max,
              trip.frequency ? trip.frequency.end - trip.departure : 0,
            ),
          0,
        ),
    }));
  }
  private prepare(day: string) {
    if (this.cachedDay === day) return;
    const days = [-1, 0, 1].map((delta) => {
      const date = new Date(`${day}T00:00:00-05:00`);
      date.setUTCDate(date.getUTCDate() + delta);
      const dateKey = new Date(date.getTime() - 5 * 3600000)
        .toISOString()
        .slice(0, 10);
      return {
        base: date.getTime() / 1000,
        active: this.timetable.services.map((service) =>
          serviceActive(service, dateKey),
        ),
      };
    });
    this.runs = this.timetable.patterns.map((p) => {
      const runs: Run[] = [];
      for (const { base, active } of days)
        for (const trip of p.trips)
          if (active[trip.service]) runs.push({ trip, base });
      return runs.sort(
        (a, b) => a.base + a.trip.departure - b.base - b.trip.departure,
      );
    });
    this.cachedDay = day;
  }
  private endpointTimes(request: TripRequest) {
    const origin = request.arriveBy ? request.to : request.from;
    const destination = request.arriveBy ? request.from : request.to;
    const maxAccess = this.options.maxAccessMeters ?? 1000;
    const times = (endpoint: Place) => {
      const root =
        endpoint.kind === "station"
          ? (this.stationRoots.get(endpoint.id) ?? endpoint.id)
          : endpoint.id;
      return this.timetable.stops.map((stop) => {
        const d =
          this.stationRoots.get(stop.id) === root
            ? 0
            : distance(endpoint, stop);
        return d <= maxAccess ? walkingSeconds(d) : Infinity;
      });
    };
    return {
      access: times(origin),
      egress: times(destination),
      direct: distance(request.from, request.to),
    };
  }
  async plan(request: TripRequest, signal?: AbortSignal): Promise<TripResult> {
    const max = Math.max(1, Math.min(6, this.options.maxItineraries ?? 6));
    const window = Math.max(0, this.options.searchWindowSeconds ?? 3600) * 1000;
    signal?.throwIfAborted();
    const requested = Date.parse(request.time);
    if (!Number.isFinite(requested)) throw new Error("Invalid request time");
    const reverse = !!request.arriveBy;
    const sign = reverse ? -1 : 1;
    const endpoints = this.endpointTimes(request);
    let cursor = requested;
    const itineraries: Itinerary[] = [],
      seen = new Set<string>();
    let first: TripResult | undefined;
    for (let scan = 0; scan < max; scan++) {
      const result = await this.planAt(
        { ...request, time: new Date(cursor).toISOString() },
        signal,
        endpoints,
      );
      first ??= result;
      let next = reverse ? -Infinity : Infinity;
      for (const itinerary of result.itineraries) {
        const edge = Date.parse(
          reverse ? itinerary.arrival : itinerary.departure,
        );
        if (scan > 0 && sign * (edge - requested) > window) continue;
        const rides = itinerary.legs.filter((l) => l.mode === "transit");
        if (rides.length && (reverse ? edge <= cursor : edge >= cursor))
          next = reverse ? Math.max(next, edge) : Math.min(next, edge);
        // Walk-only routes need no repeated later copies.
        const key = rides.length
          ? rides.map((l) => l.route.id).join("|") + "@" + rides[0].departure
          : "walk";
        if (seen.has(key)) continue;
        seen.add(key);
        itineraries.push({
          ...itinerary,
          id: `device-${itineraries.length}-${key}`,
        });
      }
      if (
        itineraries.length >= max ||
        !Number.isFinite(next) ||
        !result.itineraries.some((i) =>
          i.legs.some((l) => l.mode === "transit"),
        )
      )
        break;
      cursor = next + sign * 1000;
      if (sign * (cursor - requested) > window) break;
    }
    return { ...first!, itineraries: itineraries.slice(0, max) };
  }
  private async planAt(
    request: TripRequest,
    signal: AbortSignal | undefined,
    endpoints: ReturnType<DeviceRouter["endpointTimes"]>,
  ): Promise<TripResult> {
    signal?.throwIfAborted();
    const t = Date.parse(request.time) / 1000;
    if (!Number.isFinite(t)) throw new Error("Invalid request time");
    const day = new Date(t * 1000 - 5 * 3600000).toISOString().slice(0, 10);
    this.prepare(day);
    const reverse = !!request.arriveBy,
      sign = reverse ? -1 : 1;
    const better = (a: number, b: number) => sign * a < sign * b;
    const origin = reverse ? request.to : request.from,
      destination = reverse ? request.from : request.to;
    const { stops, patterns, footpaths } = this.timetable;
    const maxAccess = this.options.maxAccessMeters ?? 1000;
    const walk = (
      from: Place,
      to: Place,
      start: number,
      end: number,
    ): WalkLeg => ({
      mode: "walk",
      from,
      to,
      departure: iso(start),
      arrival: iso(end),
      distance: distance(from, to) * 1.3,
      estimated: true,
      geometry: [from, to],
    });
    const labels: (Label | undefined)[] = stops.map((stop, index) => {
      const seconds = endpoints.access[index];
      if (!Number.isFinite(seconds)) return undefined;
      const end = t + sign * seconds;
      return {
        time: end,
        edge: t,
        rides: 0,
        path: {
          segment: {
            kind: "walk",
            from: reverse ? stop : origin,
            to: reverse ? origin : stop,
            start: reverse ? end : t,
            end: reverse ? t : end,
          },
        },
      };
    });
    const egress = endpoints.egress;
    const results: Itinerary[] = [];
    const direct = endpoints.direct;
    if (direct <= maxAccess) {
      const duration = walkingSeconds(direct);
      const start = reverse ? t - duration : t;
      results.push({
        id: "device-walk",
        departure: iso(start),
        arrival: iso(start + duration),
        transfers: 0,
        legs: [walk(request.from, request.to, start, start + duration)],
      });
    }
    const makeItinerary = (found: Label, id: string): Itinerary => {
      const segments: Segment[] = [];
      let path: Path | undefined = found.path;
      while (path) {
        segments.push(path.segment);
        path = path.previous;
      }
      if (!reverse) segments.reverse();
      const legs = segments.map((segment) =>
        segment.kind === "walk"
          ? walk(segment.from, segment.to, segment.start, segment.end)
          : this.transit(
              segment.pattern,
              segment.a,
              segment.b,
              segment.base,
              segment.profile,
            ),
      );
      // Remove artificial waiting at the origin/destination from the reported journey.
      if (legs.length >= 2 && legs[0].mode === "walk") {
        const duration =
          (Date.parse(legs[0].arrival) - Date.parse(legs[0].departure)) / 1000;
        legs[0].arrival = legs[1].departure;
        legs[0].departure = iso(
          Date.parse(legs[1].departure) / 1000 - duration,
        );
      }
      const last = legs.length - 1;
      if (last >= 1 && legs[last].mode === "walk") {
        const duration =
          (Date.parse(legs[last].arrival) - Date.parse(legs[last].departure)) /
          1000;
        legs[last].departure = legs[last - 1].arrival;
        legs[last].arrival = iso(
          Date.parse(legs[last - 1].arrival) / 1000 + duration,
        );
      }
      const transfers = Math.max(
        0,
        legs.filter((l) => l.mode === "transit").length - 1,
      );
      const itinerary = {
        id,
        departure: legs[0].departure,
        arrival: legs[legs.length - 1].arrival,
        transfers,
        legs,
      };
      return itinerary;
    };
    const alternatives = new Map<string, Itinerary>();
    const metrics = new Map<
      string,
      { departure: number; arrival: number; transfers: number }
    >();
    const maxOptions = Math.max(
      1,
      Math.min(6, this.options.maxItineraries ?? 6),
    );
    const windowEnd = t + sign * (this.options.searchWindowSeconds ?? 3600);
    let alternativeWorst = Infinity;
    const dominates = (a: Itinerary, b: Itinerary) =>
      a.transfers <= b.transfers &&
      a.departure >= b.departure &&
      a.arrival <= b.arrival;
    const remember = (
      time: number,
      edge: number,
      parent: Label,
      pattern: Pattern,
      a: number,
      b: number,
      base: number,
      profile: import("./bundle").TimingProfile,
      stop: number,
    ) => {
      const rideCount = parent.rides + 1;
      if (!Number.isFinite(egress[stop])) return;
      const arrival = time + sign * egress[stop];
      if (sign * edge < sign * t || sign * edge > sign * windowEnd) return;
      const departureTime = reverse ? arrival : edge,
        arrivalTime = reverse ? edge : arrival;
      for (const other of metrics.values())
        if (
          other.transfers <= rideCount - 1 &&
          other.departure >= departureTime &&
          other.arrival <= arrivalTime
        )
          return;
      // Bound construction work once the earliest useful alternatives are retained.
      if (alternatives.size >= maxOptions && sign * arrival > alternativeWorst)
        return;
      const found: Label = {
        time: arrival,
        edge,
        rides: rideCount,
        path: {
          previous: {
            previous: parent.path,
            segment: { kind: "transit", pattern, a, b, base, profile },
          },
          segment: {
            kind: "walk",
            from: reverse ? destination : stops[stop],
            to: reverse ? stops[stop] : destination,
            start: reverse ? arrival : time,
            end: reverse ? time : arrival,
          },
        },
      };
      const itinerary = makeItinerary(found, "candidate");
      const rides = itinerary.legs.filter((l) => l.mode === "transit");
      const key =
        rides.map((l) => l.route.id).join("|") + "@" + rides[0]?.departure;
      const old = alternatives.get(key);
      if (
        old &&
        sign * Date.parse(reverse ? old.departure : old.arrival) <=
          sign * Date.parse(reverse ? itinerary.departure : itinerary.arrival)
      )
        return;
      if (
        [...alternatives.values()].some((other) => dominates(other, itinerary))
      )
        return;
      for (const [id, other] of alternatives)
        if (dominates(itinerary, other)) {
          alternatives.delete(id);
          metrics.delete(id);
        }
      metrics.set(key, {
        departure: departureTime,
        arrival: arrivalTime,
        transfers: itinerary.transfers,
      });
      alternatives.set(key, { ...itinerary, id: `device-${key}` });
      if (alternatives.size > maxOptions) {
        const sorted = [...alternatives].sort(
          (a, b) =>
            sign *
              (Date.parse(reverse ? a[1].departure : a[1].arrival) -
                Date.parse(reverse ? b[1].departure : b[1].arrival)) ||
            a[1].transfers - b[1].transfers,
        );
        const dropped = sorted[sorted.length - 1][0];
        alternatives.delete(dropped);
        metrics.delete(dropped);
      }
      alternativeWorst =
        alternatives.size >= maxOptions
          ? [...alternatives.values()].reduce(
              (max, i) =>
                Math.max(
                  max,
                  (sign * Date.parse(reverse ? i.departure : i.arrival)) / 1000,
                ),
              -Infinity,
            )
          : Infinity;
    };
    for (
      let round = 1;
      round <= (this.options.maxTransfers ?? 3) + 1;
      round++
    ) {
      signal?.throwIfAborted();
      const next = labels.slice();
      let changed = false;
      let boundary = results.reduce(
        (best, it) => {
          const value = Date.parse(reverse ? it.departure : it.arrival) / 1000;
          return better(value, best) ? value : best;
        },
        reverse ? -Infinity : Infinity,
      );
      patterns.forEach((pattern, pi) => {
        if (!pattern.stops.some((s) => labels[s])) return;
        const runs = this.runs[pi],
          span = this.spans[pi];
        const lower = (reverse ? Math.max(t - 86400, boundary) : t) - span.max;
        const upper = (reverse ? t : Math.min(t + 86400, boundary)) - span.min;
        let lo = 0,
          hi = runs.length;
        while (lo < hi) {
          const mid = (lo + hi) >>> 1;
          if (runs[mid].base + runs[mid].trip.departure < lower) lo = mid + 1;
          else hi = mid;
        }
        for (let ri = lo; ri < runs.length; ri++) {
          const run = runs[ri];
          const cutoff = reverse ? upper : Math.min(upper, boundary - span.min);
          if (run.base + run.trip.departure > cutoff) break;
          const profile = pattern.profiles[run.trip.profile];
          const first = run.base + run.trip.departure + profile.times[0];
          const last =
            run.base +
            (run.trip.frequency?.end ?? run.trip.departure) +
            profile.times[profile.times.length - 1];
          if (
            reverse
              ? first > t || last < Math.max(t - 86400, boundary)
              : last < t || first > Math.min(t + 86400, boundary)
          )
            continue;
          let board = -1,
            base = run.base + run.trip.departure;
          let parent: Label | undefined;
          for (let step = 0; step < pattern.stops.length; step++) {
            const i = reverse ? pattern.stops.length - 1 - step : step,
              stop = pattern.stops[i];
            if (
              board >= 0 &&
              (reverse ? profile.pickup[i] : profile.dropoff[i]) !== 1
            ) {
              const arrival = base + profile.times[i * 2 + (reverse ? 1 : 0)];
              const edge = parent!.rides
                ? parent!.edge
                : reverse
                  ? base + profile.times[board * 2] + t - parent!.time
                  : base + profile.times[board * 2 + 1] - (parent!.time - t);
              const destinationTime = arrival + sign * egress[stop];
              if (
                maxOptions === 1 &&
                Number.isFinite(egress[stop]) &&
                better(destinationTime, boundary)
              )
                boundary = destinationTime;
              if (Number.isFinite(egress[stop])) {
                const a = reverse ? i : board,
                  b = reverse ? board : i;
                remember(
                  arrival,
                  edge,
                  parent!,
                  pattern,
                  a,
                  b,
                  base,
                  profile,
                  stop,
                );
              }
              const existing = next[stop];
              if (!existing || better(arrival, existing.time)) {
                const a = reverse ? i : board,
                  b = reverse ? board : i;
                next[stop] = {
                  time: arrival,
                  edge,
                  rides: parent!.rides + 1,
                  path: {
                    previous: parent!.path,
                    segment: { kind: "transit", pattern, a, b, base, profile },
                  },
                };
                changed = true;
              }
            }
            const label = labels[stop];
            if (
              !label ||
              (reverse ? profile.dropoff[i] : profile.pickup[i]) === 1
            )
              continue;
            const ready =
              label.time +
              sign *
                (label.rides > 0 ? (this.options.transferSeconds ?? 60) : 0);
            const offset = profile.times[i * 2 + (reverse ? 0 : 1)];
            let candidate = run.base + run.trip.departure;
            if (run.trip.frequency) {
              const f = run.trip.frequency,
                relative = ready - run.base - offset;
              const k = reverse
                ? Math.floor((relative - run.trip.departure) / f.headway)
                : Math.ceil((relative - run.trip.departure) / f.headway);
              candidate =
                run.base + run.trip.departure + Math.max(0, k) * f.headway;
              if (
                candidate >= run.base + f.end ||
                candidate < run.base + run.trip.departure
              )
                continue;
            }
            const departure = candidate + offset;
            if (
              sign * departure >= sign * ready &&
              (board < 0 || better(candidate, base))
            ) {
              board = i;
              base = candidate;
              parent = label;
            }
          }
        }
      });
      // Only one bounded inter-stop walk per round; never chain through a city of footpaths.
      const beforeWalk = next.slice();
      beforeWalk.forEach((label, s) => {
        if (!label || label === labels[s]) return;
        for (const f of footpaths[s]) {
          const arrival = label.time + sign * f.seconds;
          if (!next[f.to] || better(arrival, next[f.to]!.time)) {
            next[f.to] = {
              time: arrival,
              edge: label.edge,
              rides: label.rides,
              path: {
                previous: label.path,
                segment: {
                  kind: "walk",
                  from: reverse ? stops[f.to] : stops[s],
                  to: reverse ? stops[s] : stops[f.to],
                  start: reverse ? arrival : label.time,
                  end: reverse ? label.time : arrival,
                },
              },
            };
          }
        }
      });
      let best: Label | undefined;
      next.forEach((label, s) => {
        if (!label || !Number.isFinite(egress[s])) return;
        const arrival = label.time + sign * egress[s];
        if (!best || better(arrival, best.time)) {
          best = {
            time: arrival,
            edge: label.edge,
            rides: label.rides,
            path: {
              previous: label.path,
              segment: {
                kind: "walk",
                from: reverse ? destination : stops[s],
                to: reverse ? stops[s] : destination,
                start: reverse ? arrival : label.time,
                end: reverse ? label.time : arrival,
              },
            },
          };
        }
      });
      if (best) {
        const itinerary = makeItinerary(best, `device-${round}`);
        if (
          !results.some(
            (r) =>
              r.transfers <= itinerary.transfers &&
              (reverse
                ? r.departure >= itinerary.departure
                : r.arrival <= itinerary.arrival),
          )
        )
          results.push(itinerary);
      }
      for (let i = 0; i < next.length; i++) labels[i] = next[i];
      if (!changed) break;
    }
    return {
      source: "device",
      dataVersion: this.timetable.version,
      dataBuiltAt: this.timetable.builtAt,
      generatedAt: (this.options.now?.() ?? new Date()).toISOString(),
      itineraries: [
        ...results.filter(
          (r, i) =>
            !results.some(
              (other, j) =>
                j !== i &&
                other.transfers <= r.transfers &&
                (reverse
                  ? other.departure >= r.departure
                  : other.arrival <= r.arrival) &&
                (other.transfers < r.transfers ||
                  (reverse
                    ? other.departure > r.departure
                    : other.arrival < r.arrival)),
            ),
        ),
        ...[...alternatives.values()].sort(
          (a, b) =>
            sign *
            (Date.parse(reverse ? a.arrival : a.departure) -
              Date.parse(reverse ? b.arrival : b.departure)),
        ),
      ],
    };
  }
  private transit(
    p: Pattern,
    a: number,
    b: number,
    base: number,
    profile: import("./bundle").TimingProfile,
  ): Leg {
    return {
      mode: "transit",
      from: this.timetable.stops[p.stops[a]],
      to: this.timetable.stops[p.stops[b]],
      departure: iso(base + profile.times[a * 2 + 1]),
      arrival: iso(base + profile.times[b * 2]),
      route: this.timetable.routes[p.route],
      headsign: directionHeadsign(
        p.headsign,
        this.timetable.routes[p.route].shortName,
        this.timetable.stops[p.stops[p.stops.length - 1]].name,
      ),
      geometry: p.stops.slice(a, b + 1).map((s) => this.timetable.stops[s]),
      intermediateStops: p.stops.slice(a + 1, b).map((s, n) => ({
        place: this.timetable.stops[s],
        arrival: iso(base + profile.times[(a + n + 1) * 2]),
        departure: iso(base + profile.times[(a + n + 1) * 2 + 1]),
      })),
    };
  }
}
