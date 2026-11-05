# Data API for the UI

Import hooks and Expo services from `@/data`. Import domain types and pure routing/search engines from `@tm/core`. No screen or component is changed by this layer.

## Startup

The custom Expo entrypoint `apps/mobile/index.ts` imports `data/bootstrap` before Expo Router, starting data services automatically without changing any screens. `startDataServices()` is idempotent and returns a stop function for tests or an alternative host. All hooks can also start their required observation lazily.

```ts
const stop = startDataServices(); // Already called by the app entrypoint.
// stop() cancels sync and stops network observation when the host shuts down.
```

Startup reads the persisted active bundle commit, warms its offline router/search index in the background, and checks for updates when connectivity is available. Successful updates warm the new version too, reusing the timetable decoded during integrity verification. Warm-up failures do not prevent online requests and are retried by the next query. A transition to a connected network or to Wi-Fi/ethernet triggers another check. Background scheduling and OS app-resume refresh are not installed. Call `bundleSync.check()` explicitly for a manual refresh.

Configuration is read from `EXPO_PUBLIC_MOTIS_URL`, default `http://localhost:8080`, and `EXPO_PUBLIC_BUNDLE_URL`, default `http://localhost:8090`. Expo embeds public environment values at bundle time. A native rebuild is required after installing the new Expo modules.

## Hooks

```ts
useTripPlanner(request?: TripRequest): RoutingState & {
  source?: 'server' | 'device';
  plan(request: TripRequest): Promise<void>;
  cancel(): void;
};

interface RoutingState {
  result?: TripResult;
  isLoading: boolean;
  isUpgrading: boolean;
  error?: Error;
}

usePlaceSearch(text: string, debounceMs?: number): SearchState;
interface SearchState {
  places: Place[];
  isLoading: boolean;
  isUpgrading: boolean;
  error?: Error;
}

useBundleStatus(): BundleStatus & {
  check(options?: { allowMetered?: boolean }): Promise<void>;
};
interface BundleStatus {
  status: 'idle' | 'checking' | 'downloading' | 'ready' | 'error';
  activeVersion?: string;
  activeBuiltAt?: string; // ISO build time, also available for legacy manifests via createdAt
  lastCheckedAt?: string;
  progress?: number; // 0..1 across all downloads
  error?: string;
  downloadDeferred?: boolean;
}

useConnectivity(): {
  connected: boolean;
  internetReachable: boolean;
  downloadAllowed: boolean;
  type: NetworkStateType;
};
```

A request uses `Place` endpoints and an ISO timestamp with a timezone, for example `2026-10-22T08:00:00-05:00`. Set `arriveBy: true` for a latest-departure search. A raw map coordinate can be a `Place` with `kind: 'coordinate'`, a stable ID and a display name. Domain geometry is `{lat, lon}[]`, not GeoJSON's `[lon, lat][]`.

`useTripPlanner` runs automatically when the serialized request changes. Alternatively, omit the argument and call `plan`. New requests cancel earlier ones. `isLoading` is true immediately when an automatic or imperative request is accepted, and false on its first result or error. Cancellation clears both flags. An empty device result is a completed search with no route.

Both services dispatch the server request before device work starts, then emit whichever answer is ready first. A nonempty server route is authoritative and ends routing without waiting for a cold device engine. A device answer emits immediately when it is first; a later server answer replaces it. A failed or empty server answer keeps the device result, including when it arrives after the server failure. `isUpgrading` means another engine may still improve the answer. It can be true alongside an error if the other engine is pending. `source: 'device'` means schedule-based estimates, including while connected.

The routing upgrade deadline is 8 seconds from request start. Search uses a 250 ms debounce and a 5-second remote deadline measured from dispatch, including cold device work. `usePlaceSearch.isLoading` is true from the render accepting a nonempty query, throughout debounce, until the first emission or error. Empty queries return no places and both flags false. A new query clears prior results immediately; stale requests cannot overwrite it.

Search emits either engine immediately and merges again when the other arrives. Exact/prefix name matches rank above substring and fuzzy matches, then stations above stops above POIs/addresses. Parent stations replace child platforms for general queries. A complete child code or distinguishing name suffix reveals that platform. Nearby matching feeder areas without GTFS parents are grouped for search only. Equal normalized names within 150 m are deduplicated, preferring a station and then a local entry. Same-name locations farther apart remain distinct. Server addresses/POIs are not cached for offline search.

If no bundle is installed, online routing/search can still return a server answer. Truly offline first launch requires an initial bundle download. No timetable is shipped inside the app binary.

`useBundleStatus().check({allowMetered: true})` explicitly permits cellular or unknown-network downloads. Automatic downloads require Wi-Fi or ethernet. Expo Network does not expose whether a Wi-Fi connection is metered, so this policy treats Wi-Fi as allowed. A network-type change during an automatic download aborts it. The last working bundle remains available during checks, updates and errors.

## Engines and services

```ts
new DeviceRouter(timetable: Timetable, options?: RouterOptions);
new OfflinePlaceSearch(places: Place[], limit?: number);
new MotisClient(baseUrl?: string, options?: MotisOptions);

new RoutingService(device: RoutingEngine, server: RoutingEngine, options?: StrategyOptions);
RoutingService.plan(request: TripRequest, signal?: AbortSignal): AsyncGenerator<RoutingState>;

new SearchService(device: PlaceEngine, server: PlaceEngine, options?: StrategyOptions);
SearchService.search(text: string, signal?: AbortSignal): AsyncGenerator<SearchState>;

interface RoutingEngine {
  plan(request: TripRequest, signal?: AbortSignal): Promise<TripResult>;
}
interface PlaceEngine {
  search(text: string, signal?: AbortSignal): Promise<Place[]>;
}

loadOfflineData(): Promise<{ router: DeviceRouter; search: OfflinePlaceSearch }>;
getOfflineMapSource(): Promise<OfflineMapSource | undefined>;
```

The configured `routingService`, `searchService`, and `bundleSync` singletons are exported. `StrategyOptions` accepts `online: () => boolean` and `timeoutMs`. `MotisOptions` accepts injected `fetch`, `now`, `timeoutMs`, and `dataVersion`. `RouterOptions` accepts `maxTransfers`, `maxAccessMeters`, `transferSeconds`, `now`, `searchWindowSeconds` with default 3600, and `maxItineraries` with default and hard cap 6. Tests can use in-memory engines and a fixed clock without Expo.

`loadOfflineData` decodes once per active version, sharing the same in-flight promise between warm-up and queries. Old requests can finish on their original router after an update. Failure drops the cached promise so a later request can retry. The router builds its service-day cache on the first query of a date.

Mobile uses `decodeBundleAsync(bytes, options?)` from `@tm/core`, which incrementally inflates compressed input and yields to the host roughly every 8 ms while parsing profiles, trips, and footpaths. JSON metadata parsing and an individual decode section still run synchronously. The synchronous `decodeBundle` remains available to pipeline tools. Development builds log `[offline] decode <duration> ms; longest slice <duration> ms; <count> yields` for startup and update verification. These are measured durations, not guarantees of a frame budget on every device. Timetable objects use more RAM than their compressed file size. See `services/pipeline/BENCHMARK.md` for Node measurements.

Device routing retains the requested-time arrival/transfer Pareto set and collects additional useful departures, normally in the same scan. Further scans fill sparse results, including frequency departures. Extra options are limited to the next hour for depart-after, or preceding hour for arrive-by. The first Pareto search retains its existing 24-hour horizon. Results are capped at six and deduplicated by route sequence and first transit departure. Parent-station requests include all their GTFS child stops, even beyond the usual coordinate access radius. Walking and station access remain estimates.

`TripResult` adds `dataBuiltAt?: string`, an ISO build timestamp, while `dataVersion` keeps its existing value. `Timetable` and `BundleManifest` expose `builtAt?: string`; new builds populate both. Legacy manifests derive this field from `createdAt`, and the mobile loader attaches it to older timetables. `Place` adds optional `parentStationId?: string` and `code?: string`. All hook parameters and actions retain their existing signatures. Consumers that construct `SearchState` or `RoutingState` objects must now supply the required `isLoading: boolean`.

`getOfflineMapSource` returns a `pmtiles://file://...` vector-source URL plus bounds, zooms and attribution, or undefined when the installed manifest has no map. It rejects if no bundle exists. A compatible local style, glyphs and sprites are still needed for offline rendering. See `services/pipeline/MAPS.md`.

## Update safety

The sync manager downloads immutable version paths into a temporary directory, checks size and SHA-256, cooperatively decodes the timetable, validates its version, then moves the complete directory into place. Activation writes a temporary commit record and moves it to a new, immutable filename in the same directory. No previous commit is overwritten or removed during activation. Startup chooses the newest complete commit and falls back to the previous one if metadata or required files are missing. The previous commit and version are retained. Older versions and interrupted temporary directories are cleaned up only after a successful update.

Manifests are trusted local inputs with path/name validation; SHA-256 checks detect corruption, not a malicious server. Interrupted downloads restart rather than resume. Native commit moves and download behavior still need device integration testing. This implementation is typed and linted against SDK 58 but has not been exercised on the emulator.
