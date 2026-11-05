import {
  systemKind,
  type Coordinate,
  type Itinerary,
  type Place,
  type PlaceEngine,
  type RoutingEngine,
  type TripRequest,
  type TripResult,
} from "./domain";
/** Wire subset of MOTIS v2.11.4 openapi.yaml, /api/v5/plan and /api/v1/geocode. */
export interface MotisPlace {
  name: string;
  lat: number;
  lon: number;
  stopId?: string;
  parentId?: string;
  stopCode?: string;
  arrival?: string;
  departure?: string;
}
export interface MotisLeg {
  mode: string;
  from: MotisPlace;
  to: MotisPlace;
  startTime: string;
  endTime: string;
  distance?: number;
  routeId?: string;
  routeShortName?: string;
  routeColor?: string;
  agencyId?: string;
  headsign?: string;
  tripTo?: MotisPlace;
  routeLongName?: string;
  intermediateStops?: MotisPlace[];
  legGeometry: { points: string; precision?: number };
}
export interface MotisItinerary {
  id: string;
  startTime: string;
  endTime: string;
  transfers: number;
  legs: MotisLeg[];
}
export interface MotisPlan {
  itineraries: MotisItinerary[];
  direct?: MotisItinerary[];
}
export interface MotisMatch {
  id: string;
  name: string;
  lat: number;
  lon: number;
  type: string;
}
function place(p: MotisPlace): Place {
  return {
    id: p.stopId ?? `${p.lat},${p.lon}`,
    name: p.name,
    lat: p.lat,
    lon: p.lon,
    kind: p.stopId ? "stop" : "coordinate",
    parentStationId: p.parentId,
    code: p.stopCode,
  };
}
export function decodePolyline(points: string, precision = 5): Coordinate[] {
  const out: Coordinate[] = [];
  let i = 0,
    lat = 0,
    lon = 0;
  const value = () => {
    let result = 0,
      shift = 0,
      b: number;
    do {
      if (i >= points.length || shift > 30) throw new Error("Invalid polyline");
      b = points.charCodeAt(i++) - 63;
      result |= (b & 31) << shift;
      shift += 5;
    } while (b >= 32);
    return result & 1 ? ~(result >> 1) : result >> 1;
  };
  while (i < points.length) {
    lat += value();
    lon += value();
    out.push({ lat: lat / 10 ** precision, lon: lon / 10 ** precision });
  }
  return out;
}
/** GTFS sometimes repeats the route code in trip_headsign; use the terminal in that case. */
export function directionHeadsign(
  headsign: string | undefined,
  routeName: string | undefined,
  terminal: string,
): string {
  const value = headsign?.trim();
  return value && value.toLowerCase() !== routeName?.trim().toLowerCase()
    ? value
    : terminal.trim();
}
export function mapMotisPlan(
  plan: MotisPlan,
  generatedAt = new Date().toISOString(),
  dataVersion = "motis",
): TripResult {
  const itineraries: Itinerary[] = [
    ...plan.itineraries,
    ...(plan.direct ?? []),
  ].map((it) => ({
    id: it.id,
    departure: it.startTime,
    arrival: it.endTime,
    transfers: it.transfers,
    legs: it.legs.map((l) => {
      const base = {
        from: place(l.from),
        to: place(l.to),
        departure: l.startTime,
        arrival: l.endTime,
        geometry: decodePolyline(l.legGeometry.points, l.legGeometry.precision),
      };
      if (l.mode === "WALK")
        return {
          ...base,
          mode: "walk",
          distance: l.distance ?? 0,
          estimated: false,
        };
      return {
        ...base,
        mode: "transit",
        route: {
          id: l.routeId ?? "",
          shortName: l.routeShortName ?? "",
          color: `#${(l.routeColor ?? "666666").replace(/^#/, "")}`,
          agencyId: l.agencyId ?? "",
          system: systemKind(l.agencyId ?? ""),
        },
        headsign: directionHeadsign(
          l.headsign,
          l.routeShortName,
          l.tripTo?.name ?? l.routeLongName ?? l.to.name,
        ),
        intermediateStops: (l.intermediateStops ?? []).map((s) => ({
          place: place(s),
          arrival: s.arrival ?? s.departure ?? l.startTime,
          departure: s.departure ?? s.arrival ?? l.startTime,
        })),
      };
    }),
  }));
  return { source: "server", itineraries, generatedAt, dataVersion };
}
export interface MotisOptions {
  timeoutMs?: number;
  fetch?: typeof fetch;
  now?: () => Date;
  dataVersion?: string;
}
export class MotisClient implements RoutingEngine, PlaceEngine {
  constructor(
    private baseUrl = "http://localhost:8080",
    private options: MotisOptions = {},
  ) {}
  private async get<T>(
    path: string,
    params: URLSearchParams,
    signal?: AbortSignal,
  ): Promise<T> {
    const controller = new AbortController();
    const abort = () => controller.abort(signal?.reason);
    signal?.addEventListener("abort", abort, { once: true });
    if (signal?.aborted) abort();
    const timer = setTimeout(
      () => controller.abort(new Error("MOTIS timeout")),
      this.options.timeoutMs ?? 8000,
    );
    try {
      const response = await (this.options.fetch ?? fetch)(
        `${this.baseUrl.replace(/\/$/, "")}${path}?${params}`,
        { signal: controller.signal },
      );
      if (!response.ok) throw new Error(`MOTIS HTTP ${response.status}`);
      return (await response.json()) as T;
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener("abort", abort);
    }
  }
  async plan(request: TripRequest, signal?: AbortSignal): Promise<TripResult> {
    const location = (p: Place) =>
      p.kind === "stop" || p.kind === "station"
        ? p.id.startsWith("tm_")
          ? p.id
          : `tm_${p.id}`
        : `${p.lat},${p.lon}`;
    const params = new URLSearchParams({
      fromPlace: location(request.from),
      toPlace: location(request.to),
      time: request.time,
      arriveBy: String(request.arriveBy ?? false),
      numItineraries: "5",
      detailedTransfers: "true",
    });
    return mapMotisPlan(
      await this.get<MotisPlan>("/api/v5/plan", params, signal),
      (this.options.now?.() ?? new Date()).toISOString(),
      this.options.dataVersion,
    );
  }
  async search(text: string, signal?: AbortSignal): Promise<Place[]> {
    const matches = await this.get<MotisMatch[]>(
      "/api/v1/geocode",
      new URLSearchParams({ text }),
      signal,
    );
    return matches.map((m) => ({
      id: m.id,
      name: m.name,
      lat: m.lat,
      lon: m.lon,
      kind: /STOP/i.test(m.type)
        ? "stop"
        : /ADDRESS/i.test(m.type)
          ? "address"
          : /STREET/i.test(m.type)
            ? "street"
            : "poi",
    }));
  }
}
