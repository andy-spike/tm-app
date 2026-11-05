# Offline map data

The extracted archive covers `[-74.30, 4.30, -73.95, 4.90]`, zooms 0–14, from Protomaps' 2026-10-22 daily build, basemap version 4.15.2. The local test archive contains 694 tile entries and is 9,801,312 bytes. Extracting it required 31 HTTP requests and about 10 MB of transfer. No full planet download was needed.

To reproduce with the [official pmtiles CLI](https://docs.protomaps.com/pmtiles/cli):

```bash
pnpm bundle:maps  # requires pmtiles on PATH, or PMTILES_BIN=/absolute/path/to/pmtiles
pnpm bundle:build
```

`bundle:maps` discovers the latest public daily build and writes `.tmp/bogota.pmtiles` plus provenance metadata. `bundle:build` automatically includes this file when present. `TM_PMTILES_PATH=/absolute/path/to/another.pmtiles` overrides it. The executable and downloaded archive are ignored by git. No cloud account or deployment is involved.

## MapLibre compatibility

The installed React Native 11.5.0 package pins Android Native 13.6.1 in `android/gradle.properties` and iOS Native 6.31.0 in its podspec. Both include native PMTiles support:

- [Android documentation](https://github.com/maplibre/maplibre-native/blob/main/platform/android/docs/data/PMTiles.md) documents support since 11.7.0 and `pmtiles://file:///absolute/path` for local archives. It also warns that PMTiles sources cannot be downloaded as offline packs.
- [iOS changelog](https://github.com/maplibre/maplibre-native/blob/main/platform/ios/CHANGELOG.md) records `pmtiles://` support before 6.31.0.

The data API `getOfflineMapSource()` returns a source URL, bounds, zoom range and attribution after the archive has synced. Point a Protomaps-compatible vector style at that URL. The extracted archive alone does not provide a style, glyphs or sprites. Those resources must also be local for a fully offline rendered map. This task creates no map UI or style and does not verify native rendering on a device.

An alternative for styles based on ordinary HTTP vector sources is MapLibre React Native's `OfflineManager.createPack` region download. That is separate from downloading a PMTiles archive; do not attempt to put a PMTiles source inside an OfflineManager pack.

Keep visible OpenStreetMap contributor attribution and follow the [Protomaps basemap download licensing notes](https://docs.protomaps.com/basemaps/downloads). The source data is ODbL; the basemap is a produced work.
