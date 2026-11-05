# Bogotá timetable bundle

`@tm/core` has no platform imports. Its only runtime dependency, `fflate`, provides pure JavaScript DEFLATE and UTF-8 codecs. The app needs neither SQLite nor native decompression to read the bundle.

## Files and versioning

Each immutable build directory contains `timetable.bin`, `places.json`, and `manifest.json`. An optional `bogota.pmtiles` contains map tiles. The directory version is `<feed_start_date>_<UTC build timestamp>`. `out/latest.json` points the local server at the most recent successful build. Publishing that pointer is the last pipeline step, so an incomplete build never becomes the latest version.

The schema-1 manifest records the version, creation instant, optional ISO `builtAt`, feed date range, byte lengths and SHA-256 digests of every downloadable file. `validation.json` is a local build report, not a downloaded file. Clients request `/manifest.json`, then `/<version>/<filename>` so a concurrent rebuild cannot mix files from different versions.

## Binary header

All fixed integers are unsigned little-endian uint32. Offsets are byte offsets.

| Offset             | Length   | Meaning                                        |
| ------------------ | -------- | ---------------------------------------------- |
| 0                  | 4        | ASCII `TMBG`                                   |
| 4                  | 4        | Binary format revision, currently `3`          |
| 8                  | 4        | UTF-8 metadata length                          |
| 12                 | 4        | Stored payload length                          |
| 16                 | variable | UTF-8 metadata JSON                            |
| following metadata | variable | Raw DEFLATE payload, without zlib/gzip wrapper |

Revision 3 adds optional `builtAt` metadata and optional `parentStationId`/`code` fields on stops; its binary payload is unchanged from revision 2. The decoder also accepts revision 2 and revision 1, whose payload is uncompressed. Unknown revisions, truncated lengths, invalid varints, invalid references and trailing bytes are rejected. File integrity is verified against the manifest before activation.

Metadata is a JSON object containing `version`, optional ISO `builtAt`, `feedStart`, `feedEnd`, `stops`, `routes`, and `services`, using the exported `Timetable` types. Stops use WGS84 latitude/longitude and MOTIS-compatible `tm_` IDs. Optional `parentStationId` preserves GTFS `parent_station` using the same IDs; optional `code` preserves `stop_code`. Routes retain short names, GTFS colors, agency IDs and system kind. Service records have inclusive ISO date ranges, Monday-first weekday flags and explicit date-to-boolean exceptions. Exceptions override the weekday calendar, including services defined only by exceptions.

## Payload

Every scalar is unsigned LEB128 unless stated otherwise. Counts precede arrays. Strings are a byte count followed by UTF-8 bytes. No alignment or padding is used.

1. Pattern count.
2. For each pattern: route index, headsign string, stop count, stop indexes, profile count.
3. For each profile: `2 × stop_count` timing values, `stop_count` pickup flags, `stop_count` drop-off flags. Times alternate arrival and departure, in seconds relative to the trip's first departure. Encode differences from the previous timing value, initially zero, using zigzag signed integers. Thus a first arrival before the first departure remains representable.
4. Trip count, followed by trips sorted by first departure. Each trip stores the departure delta from the previous trip, initially zero; profile index; service index; frequency-present flag. Frequency records append exclusive end time, headway seconds and an exact-times flag. Frequency windows are not expanded into thousands of cable trips.
5. Footpath-list count, equal to the stop count. Each list stores its count, then destination stop index and walking duration in seconds for each edge.

GTFS times can exceed 24:00. They are never reduced modulo one day. Calendar checks use the trip's service date, not the date on which a passenger boards after midnight. Bogotá uses UTC-5 throughout the year. Each query examines the preceding, current and following service dates, with a 24-hour search horizon in the requested direction.

## Compilation and tradeoffs

The pipeline streams CSV from `unzip -p`. It stages unsorted stop times in temporary Node SQLite, validates references, indexes by trip and sequence, and iterates the sorted result. It does not load the 576 MB CSV into memory. Stops, trips and the compiled timetable are held in memory. Temporary SQLite needs roughly a gigabyte of local disk space during compilation.

Patterns share ordered stops and a headsign. Trips share identical timing and pickup/drop-off profiles within a pattern. Departure times are delta encoded. Different timing profiles preserve overtaking trips instead of assuming every trip has the same running time. Lossless DEFLATE reduces this feed's raw 28.4 MB binary to roughly 9.4 MB. The JSON stop index is about 0.9 MB.

Straight-line walking distances are multiplied by 1.3 and divided by 1.25 m/s, approximately 4.5 km/h. Access and egress each allow up to 1 km straight-line distance by default. Bidirectional footpaths connect stops within 400 m straight-line distance. A default 60-second transfer allowance applies before boarding another vehicle. There is one bounded footpath between rides, not an unrestricted chain of walking edges.

The route scan retains the best arrival for each stop and boarding round and returns the nondominated arrival-time/transfer-count alternatives. It also keeps a bounded set of useful later departures, rejecting alternatives dominated in departure, arrival and transfers before constructing their legs. Sparse result sets trigger additional scans with an advanced time bound. The default extra-departure window is 60 minutes, with a hard cap of six itineraries, deduplicated by route sequence and first transit departure. Parent stations expand to all their GTFS children for access and egress. Reverse scans maximize departure for arrive-by queries. Predecessor records defer itinerary construction until a result is selected. Queries prune trips outside the search horizon or already dominated by an earlier round's result. The decoder and first service-day preparation run once per loaded version/day, respectively. `decodeBundleAsync` streams DEFLATE in 16 KiB input chunks and cooperatively yields during inflate and section parsing, with an approximately 8 ms slice target. JSON metadata parsing and individual sections remain synchronous. `decodeBundle` is the synchronous tool API.

Frequency trips with `exact_times=0` have no real timetable phase. The local engine uses a deterministic headway grid starting at the frequency window's start; these are schedule estimates. It does not claim real-time cable departures. Trips with no stop times are excluded and reported as warnings. Broken references, invalid times, nonmonotonic stop sequences and an expired feed fail the build.

`places.json` is a compact JSON `Place[]`. The mobile loader reuses stops already decoded from the timetable, avoiding a second in-memory copy. `OfflinePlaceSearch` collapses children to parent stations unless a full code or distinguishing suffix is queried, ranks exact/prefix matches before other matches and stations before other kinds, and caches normalized tokens and supports accent-insensitive word prefixes plus edit distance one for tokens of at least four characters. OSM addresses and POIs remain server-only.
