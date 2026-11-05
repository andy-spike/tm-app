/** Coordinates use WGS84; times are ISO 8601 instants, including a timezone. */
export interface Coordinate {
  lat: number;
  lon: number;
}
export interface Place extends Coordinate {
  id: string;
  name: string;
  /** GTFS parent station and public platform/stop code, when provided. */
  parentStationId?: string;
  code?: string;
  kind: "stop" | "station" | "street" | "poi" | "address" | "coordinate";
}
export type SystemKind =
  | "troncal"
  | "alimentador"
  | "urbano"
  | "complementario"
  | "especial"
  | "dual"
  | "cable"
  | "unknown";
export interface Route {
  id: string;
  shortName: string;
  color: string;
  agencyId: string;
  system: SystemKind;
}
export interface StopVisit {
  place: Place;
  arrival: string;
  departure: string;
}
interface BaseLeg {
  from: Place;
  to: Place;
  departure: string;
  arrival: string;
  geometry: Coordinate[];
}
export interface WalkLeg extends BaseLeg {
  mode: "walk";
  distance: number;
  estimated: boolean;
}
export interface TransitLeg extends BaseLeg {
  mode: "transit";
  route: Route;
  headsign: string;
  intermediateStops: StopVisit[];
}
export type Leg = WalkLeg | TransitLeg;
export interface Itinerary {
  id: string;
  departure: string;
  arrival: string;
  transfers: number;
  legs: Leg[];
}
export interface TripRequest {
  from: Place;
  to: Place;
  time: string;
  arriveBy?: boolean;
}
export interface TripResult {
  itineraries: Itinerary[];
  source: "server" | "device";
  dataVersion: string;
  dataBuiltAt?: string;
  generatedAt: string;
}
export interface RoutingEngine {
  plan(request: TripRequest, signal?: AbortSignal): Promise<TripResult>;
}
export interface PlaceEngine {
  search(text: string, signal?: AbortSignal): Promise<Place[]>;
}
export const systemKind = (id: string): SystemKind =>
  (
    ({
      "1": "troncal",
      "2": "alimentador",
      "3": "urbano",
      "4": "complementario",
      "5": "especial",
      "6": "dual",
      "7": "cable",
    }) as Record<string, SystemKind>
  )[id.replace(/^tm_/, "")] ?? "unknown";
