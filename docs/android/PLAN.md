# Native Android app: v1 plan

Goal: a native Android app for getting around Bogotá, modelled on Paradero's rider-first UX (one question per screen, big minutes, saved stops, honest data labels), that works with no network. v1 ships the offline schedule and the on-device trip planner. MOTIS and live data come later.

## What we take from Paradero

Paradero (croma-labs/paradero, Expo) answers "when does my bus come?" with live data from a hosted API. Its strongest UX ideas carry over:

| Paradero idea | v1 Android equivalent |
| --- | --- |
| Home is "¿Para dónde vas?" plus nearby stops on a map | Same, from on-device data and the phone's location |
| Next bus per stop, in large minutes | Next scheduled departures per stop, grouped by route and direction |
| Every route at a stop, filterable | Route filter chips on the stop screen |
| Route codes as riders read them (`HK635` → `K635`, `H635`) | Same aliasing in search and on badges |
| Always say whether a time is live or scheduled | Every time says "Horario programado"; v1 never implies live data |
| Saved stops and routes on the home screen | Room-backed favourites, shown first on Cerca and Guardados |
| No account, no ads, location never leaves the phone | Same. v1 sends no location anywhere; the API only serves bundle files |
| Trip planner (Paradero's roadmap) | Shipped in v1 as an offline RAPTOR planner |

## Defaults chosen

| Area | Default | Reason |
| --- | --- | --- |
| Language | Kotlin 2.4, JDK 21 toolchain | Current stable compiler; AGP 9 built-in Kotlin |
| Build | AGP 9.5, Gradle 9, version catalog, R8 full mode | Current stable toolchain |
| UI | Jetpack Compose (BOM 2026.09), Material 3, edge-to-edge | Native, fast, accessible by default |
| Navigation | Navigation 3 with typed, serializable keys | Current Compose-first navigation library |
| DI | Hilt (KSP) | Standard, compile-time checked |
| Async | Coroutines + Flow, `StateFlow` UI state | Lifecycle-aware collection in Compose |
| Local data | Room (saved stops/routes, recent places), DataStore (settings) | Local-first; no account |
| Map | MapLibre Android 13 with an offline Protomaps PMTiles basemap | No Google key; works offline |
| Location | Platform `LocationManager` via `LocationManagerCompat` | No Play Services dependency |
| Backend | Cloudflare Worker (`services/api`) with R2 for bundle files and D1 for release metadata | Data updates without app releases; edge-cached immutable files; later home for alerts, accounts and a MOTIS proxy |
| Local backend | Wrangler (Miniflare) on this checkout's bundle port, state in `.world/wrangler` | No Docker; isolated per worktree |
| Updates | WorkManager daily check, unmetered network only, SHA-256 verified, atomic swap | Protects data plans, never activates a broken bundle |
| First run | Timetable and map ship in the APK | Works offline on first launch |
| Locale | Spanish (source) and English | Bogotá riders first |
| Time zone | All schedule maths in `America/Bogota` | GTFS service days are local |
| Performance | Baseline profile, primitive-array router, decode off the main thread | Fast cold start and sub-100 ms queries |

## Architecture

```
apps/android/
  core/transit   Pure Kotlin/JVM: bundle decoder, RAPTOR router, departures, search, route codes. Unit tested on the real bundle.
  core/data      Android library: bundle store and Cloudflare API sync (OkHttp), Room, DataStore, location.
  app            Compose UI, navigation, theme, map, Hilt graph.
  baselineprofile  Macrobenchmark module that generates the startup profile.
services/api/    Cloudflare Worker: GET /v1/releases/latest (D1), GET /v1/bundles/<version>/<file> (R2, immutable, range requests).
                 scripts/publish-bundle.mjs uploads the latest pipeline output, then publishes its D1 row.
```

The pipeline already writes `timetable.bin` (format revision 3, see `packages/core/BUNDLE_FORMAT.md`). The Kotlin decoder reads the same file, so the TypeScript and Kotlin clients share one data contract. A Gradle task copies the latest pipeline output into the APK's assets at build time; binaries stay out of git.

### Data flow

1. Launch: `TransitRepository` opens the active bundle (downloaded, else bundled asset) and decodes it on `Dispatchers.Default`. Screens render immediately with skeletons and fill in when the timetable is ready.
2. Queries (nearby, departures, route detail, trip plans) run on the in-memory timetable. Service-day preparation is cached per Bogotá date.
3. Sync: a daily unmetered WorkManager job, or "Buscar actualización", reads `/v1/releases/latest` from the Worker, downloads newer files to a staging directory, verifies size and SHA-256, swaps directories atomically and reloads.

### Router

RAPTOR over flat `IntArray`s: one round per ride, up to 4 rides. Access and egress walks reach up to 1 km (straight line × 1.3 at 1.25 m/s, matching the TS router). Transfers use the bundle's precomputed footpaths plus a 60 s allowance. Each query returns the Pareto set of arrival time and transfers, then repeats from the next departure to list up to 5 options. Arrive-by runs the same algorithm backwards. Frequency-based trips (TransMiCable) expand to a headway grid and are labelled as estimates.

## Screens

| Screen | Answers | Contents |
| --- | --- | --- |
| Cerca (home) | Where can I catch a bus near me? | Map with nearby stops, "¿Para dónde vas?" field, saved stops with next buses, nearby list with route badges |
| Buscar | Where is that stop or route? | Unified search over stations, stops and routes; recent searches |
| Paradero | When does my bus come? | Map header, save star, route filter chips, next bus in large minutes, later departures, "Horario programado" label |
| Ruta | Where does this route go? | Direction toggle, first and last bus, stop timeline, line on the map |
| Viaje | How do I get there? | From/to with "Mi ubicación", swap, leave now / depart at / arrive by, options list |
| Detalle del viaje | What exactly do I do? | Map with legs, step timeline with walking, boarding and alighting stops |
| Guardados | My usual stops and routes | Saved stops with next departures, saved routes |
| Ajustes | Is my data current? | Timetable version and validity, update check, appearance, privacy, attributions |

## UX rules

- One question per screen; the primary answer is the largest text.
- Times are minutes away when under an hour, otherwise a clock time. Each shows that it is scheduled.
- Loading content reserves its final size; nothing jumps.
- Every tappable target is at least 48 dp; every control has a full content description; colour is never the only signal.
- Respect system dark theme, font scale and reduced animations.
- No network spinner blocks any v1 screen; offline is the normal case.

## Visual design

Modelled on the SBB Mobile app, in TransMilenio colours. Defined in `ui/theme/Theme.kt` and `ui/components/Chrome.kt`.

- Top-level screens open with a TransMilenio-red header band (`RedHeader`); the planner's from/to card sits on it.
- Content is white cards on a light grey ground; related rows share one card (`groupItem`). Dark mode uses charcoal with grey cards and a deeper red band.
- Type is Source Sans 3, a humanist sans in the Frutiger tradition, set bold for headings and times, with tabular figures. The "a. m./p. m." marker is set smaller (`clockText`).
- Lines are solid colour plates with bold codes. Stops are pictogram tiles: red for TransMilenio stations, SITP blue for street stops.
- Connections show the journey to scale (`TripBar`): rides in their line colour, walks dotted grey, line plates above where each ride starts.
- The selected tab turns red; there is no pill indicator. Primary buttons are red with 8 dp corners.

## Validation

- JVM tests for the decoder, router (real bundle, sample OD pairs), search ranking and route codes.
- `pnpm check` (root) runs the existing workspace checks plus `./gradlew check` for Android.
- Manual checks on emulator `tm-agent-1`, then an independent QA pass through the main flows.
- Release build with R8 measured for cold start and query latency.

## Later

MOTIS online routing (proxied by the Worker) merged with device results, live arrivals, service alerts in D1, saved trips with reminders, widget, map-based origin picking, more languages.
