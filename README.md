# TM App

A clean, ad-free public transport app for Bogotá (TransMilenio, SITP zonal, TransMiCable), inspired by SBB Mobile.

## Stack

| Layer | Choice | Why |
| --- | --- | --- |
| Mobile app | [Expo](https://docs.expo.dev) (React Native, TypeScript, Expo Router) | One codebase for Android and iOS, native feel, fast iteration |
| Map | [MapLibre React Native](https://github.com/maplibre/maplibre-react-native) | Open source vector maps, no Google/Mapbox key or per-load fees |
| Routing engine | [MOTIS 2](https://github.com/motis-project/motis) | Trip planning, address/stop search, reverse geocoding and map tiles in one fast server |
| Timetable data | Official TransMilenio GTFS (via [Transitland](https://www.transit.land/explore/operators/o-d2g6-transmilenio)) | Every trunk, feeder, zonal and cable route |
| Street data | OpenStreetMap (Geofabrik Colombia, cropped to Bogotá) | Walking legs and address search |

## Layout

```
apps/mobile/        Expo app
services/motis/     Routing server: config.yml + setup script (binary and data are downloaded, not committed)
```

## Getting started

Requirements: Node 24, pnpm, `osmium` (`brew install osmium-tool`), Android SDK for device builds.

```bash
pnpm install
pnpm motis:setup     # downloads MOTIS, GTFS and OSM, then imports (~5 min first time)
pnpm motis           # routing server on http://localhost:8080 (also serves a debug web UI)
pnpm android         # builds the dev app and installs it on a device/emulator
pnpm mobile          # later runs: just start the JS dev server
```

On a USB-connected phone, run `adb reverse tcp:8080 tcp:8080` so the app can reach the server on your machine. Or set `EXPO_PUBLIC_MOTIS_URL` in `apps/mobile/.env.local`.

The map library has native code, so the app does not run in Expo Go. Use the dev build from `pnpm android`.

## Updating the timetable

TransMilenio publishes a new GTFS zip every few months under a new filename. Update `GTFS_URL` in `services/motis/scripts/setup.sh` (current URL is listed in the [Transitland Atlas entry](https://github.com/transitland/transitland-atlas/blob/main/feeds/transmilenio.gov.co.dmfr.json)), then run `pnpm motis:setup --refresh`.

## Offline data and hybrid routing

`packages/core` is the platform-independent `@tm/core` package. It defines places, itineraries and legs, a versioned binary timetable codec, the on-device round-based transit router, stop search, a typed MOTIS client, and local-first routing/search services. Both routing engines return the same `TripResult` type. The services emit a device result first and replace it with a server result within a deadline. Server failure keeps the device result.

`services/pipeline` streams the official GTFS archive into temporary Node SQLite, validates it, deduplicates patterns and timing profiles, and writes immutable bundles under `services/pipeline/out/<version>/`. SQLite is a build-time tool; the app uses a pure JavaScript decoder and an in-memory search index. The feed's 739 trips without stop times are excluded with a recorded warning. Broken references and invalid schedules fail the build.

`apps/mobile/src/data` provides SDK 58 bundle sync, lazy loading, connectivity observation, and hooks. A custom Expo entrypoint starts sync automatically; screens remain unchanged. See the [UI integration contract](apps/mobile/src/data/README.md) for startup, hook/service signatures, state transitions and limitations. See the [binary format](packages/core/BUNDLE_FORMAT.md) for encoding and routing semantics.

```bash
pnpm bundle:build    # build timetable, stop index, manifest and validation report
pnpm bundle:serve    # serve the latest manifest and immutable version paths on port 8090
pnpm motis          # online routing/geocoding on port 8080, in another terminal
adb reverse tcp:8080 tcp:8080
adb reverse tcp:8090 tcp:8090
pnpm test           # core Vitest suite
pnpm typecheck      # all workspace packages
pnpm bundle:compare # deterministic 100-pair local/MOTIS comparison; MOTIS is optional
```

Set `EXPO_PUBLIC_BUNDLE_URL` and `EXPO_PUBLIC_MOTIS_URL` in `apps/mobile/.env.local` when localhost is not appropriate. After adding the Expo file-system and network modules, rebuild the native development app with `pnpm android` before using the data APIs. Automatic bundle downloads require Wi-Fi/ethernet by default; hooks expose an explicit cellular override. The app must download its first bundle while connected before offline routing works.

For optional offline map data, install the official `pmtiles` CLI and run `pnpm bundle:maps`, then `pnpm bundle:build`. Builds automatically include `.tmp/bogota.pmtiles` when available; `TM_PMTILES_PATH` can point at another local archive. The extracted Protomaps archive covers Bogotá through zoom 14. MapLibre's pinned Android and iOS native versions support PMTiles sources. Styles, glyphs, sprites and native rendering verification remain separate UI work. See [offline map notes](services/pipeline/MAPS.md).

The measured 2026 feed contains 8,314 stops, 1,045 routes, 180,321 usable trips, 1,516 patterns and 67,727 timing profiles. Download sizes are approximately 9.39 MB for the timetable, 0.90 MB for stop search, and 9.80 MB for optional map tiles. A 100-pair Node benchmark found local routes for every pair, with 69 ms median and 129 ms p95 queries. Against local MOTIS, 96/100 arrived within ten minutes, 91/100 used the same or fewer transfers, and 87/100 satisfied both. These are schedule estimates using approximate walking links, not measured phone performance or real-time service guarantees. The benchmark writes raw OD pairs and timings into the version's `comparison.json`; rerun it after a feed or router change.

Detailed measurements, sample methodology and validation are recorded in [the benchmark report](services/pipeline/BENCHMARK.md).

## Native Android app (v1, offline)

`apps/android` is a native Kotlin app: Jetpack Compose, Material 3, Navigation 3, Hilt, Room, MapLibre. It ships the timetable, route shapes and an offline Bogotá map inside the APK, so it works with no connection from the first launch. It answers four questions offline: which stops are near me, when the next scheduled bus leaves, where a route goes, and how to get somewhere (an on-device RAPTOR planner, depart-at and arrive-by). Every time is labelled as scheduled. See the [plan](docs/android/PLAN.md) and [module notes](apps/android/AGENTS.md).

`services/api` is a Cloudflare Worker that publishes new timetables: release metadata in D1, immutable files in R2, range requests for resumable downloads. The app checks once a day on Wi-Fi, verifies sizes and SHA-256 digests, and swaps the new data in without a restart.

```bash
pnpm api            # local Worker (Wrangler/Miniflare) on this checkout's bundle port, state in .world/wrangler
pnpm api:publish    # upload services/pipeline/out/<latest> to local R2 and publish it in D1 (--remote for Cloudflare)
pnpm --filter @tm/pipeline map-style   # regenerate offline map styles and glyphs
pnpm android:check  # Kotlin tests and Android lint (part of pnpm check)
```

## Working with agents

See [the agent worktree workflow](docs/agent-workflow.md) for T3 Code setup, isolated service commands, device ownership and validated merges into main. Run `pnpm worktree:setup` once in each checkout before starting services. Service ports are printed by `pnpm world:status`; use those ports instead of the fixed single-checkout examples above.

## Expo development builds

The mobile app uses Expo SDK 58 (`expo ~58.0.23`), React Native 0.88.1, and SDK-compatible Expo modules. `expo-dev-client` supplies the native development launcher and debugging tools. Metro automatically selects the development client when it is installed; Expo Go cannot load this app's MapLibre native module.

After pulling dependency or native configuration updates, run `pnpm worktree:setup` in your checkout. Rebuild the development app before testing it. If that checkout already has generated Android files, regenerate them first:

```bash
pnpm --filter @tm/mobile exec expo prebuild --clean --platform android --no-install
pnpm android --device <assigned-adb-serial>
pnpm mobile
```

The prebuild command regenerates ignored native files from app.json and config plugins; keep native customizations in those sources. Use the assigned emulator and forward the ports shown by `pnpm world:status`, as described in the agent workflow. Build iOS on a Mac with Xcode, regenerating its native project after native dependency changes as well.

Validate dependency alignment with `pnpm --filter @tm/mobile exec expo install --check`. Run Expo Doctor against `apps/mobile`, rather than the monorepo root, and run `pnpm check` for the workspace checks.
