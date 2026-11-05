# Local routing validation, 2026-10-22

Environment: Node v24.22.1, Linux x64, Intel Core i5-11400H at 2.70 GHz. These are Node measurements, not emulator or phone measurements.

The checked build is `2026-01-01_20261022T211941804Z`, built at `2026-10-22T21:21:01.196Z`, from the official TransMilenio feed valid 2026-01-01 through 2026-12-31. The builder validated 9,408,893 stop-time rows. Of 181,060 declared trips, 739 without stop times were excluded with a warning, leaving 180,321 usable trips. There are 8,314 stops, 1,045 routes, 1,516 patterns and 67,727 exact timing profiles.

Download sizes, decimal units:

- `timetable.bin`: 9,567,935 bytes, 9.57 MB.
- `places.json`: 1,078,694 bytes, 1.08 MB.
- `bogota.pmtiles`: 9,801,312 bytes, 9.80 MB.
- Total: 20,447,941 bytes, 20.45 MB. Routing and search without maps total 10,646,629 bytes.

`pnpm bundle:build` succeeded with binary revision 3. Optional `builtAt`, `parentStationId`, and `code` metadata were added; the compressed payload layout is unchanged. The decoder accepts revisions 1, 2 and 3. The schema-1 manifest includes optional `builtAt`; legacy manifests use `createdAt` as the build timestamp. The running bundle server at port 8090 returned the new manifest and all three immutable file paths. Byte lengths and SHA-256 digests matched the manifest.

## Reproducible comparison

`pnpm bundle:compare` uses LCG seed 20261022 to select 100 OD pairs from served stops, at whole hours from 06:00 through 19:00 Bogotá time on 2026-10-22. It queries device and local MOTIS v2.11.4 sequentially. Both receive stop places. Device routing additionally permits approximate nearby walking access/egress. Explicit station endpoints expand to their GTFS children; selected child platforms retain normal walking access. The earliest-arriving itinerary from each result is compared. Transfer count is measured on that same itinerary.

The final run returned:

- Synchronous binary decode: 1,529 ms.
- Query p50: 70.9 ms; p95: 268.6 ms; maximum: 980.4 ms.
- Device routes found: 100/100; MOTIS: 100/100.
- Local arrival within ten minutes of MOTIS: 96/100.
- Same or fewer transfers: 91/100.
- Both criteria: 87/100.
- MOTIS errors: 0.

The original implementation measured immediately before these changes returned 100/100 local routes, 61.4 ms median, 122.3 ms p95, 293.8 ms maximum, and 1,300 ms decode. Its comparison totals were also 96/91/87. The new router returns additional departures and therefore does more work. Its p95 meets the 300 ms target; the maximum shows that this is not a per-query latency guarantee.

The router collects useful later departures during its regular scans, rejects dominated candidates numerically before allocating itinerary legs, and repeats scans only to fill sparse results. Time-sorted runs and binary bounds prune irrelevant trips; walking access/egress is computed once per query. Default extra-departure window: 60 minutes. Default and hard cap: six itineraries. Initial arrival/transfer Pareto results retain the existing 24-hour search horizon. Deduplication uses route sequence and first transit departure.

Raw OD requests, arrival times, transfers and timings are in the build's `comparison.json`. The script supports an alternate `MOTIS_URL`, including its health check, and also runs with an unavailable server to measure local routing alone.

## Cold decode and reported trip

Run `pnpm --filter @tm/pipeline exec tsx src/inspect.ts` to reproduce the focused checks. It saves full results and search entries in `inspection.json` beside `comparison.json`.

The final focused run measured:

- Cooperative cold decode: 1,473 ms across 141 host yields; longest measured blocking slice: 13.0 ms.
- Router construction: 12.1 ms; search-index construction: 22.0 ms.
- Portal Norte - Unicervantes to Universidad Nacional at 16:13: first query 95.0 ms, p50 59.6 ms, p95 72.5 ms over 25 queries.
- Six device G12 options departing at 16:14:15, 16:18, 16:21, 16:24, 16:27 and 16:30 Bogotá time. The first arrives at 16:49:25. MOTIS returned six options, including a 16:15 S46/G12 connection. Local results are useful alternatives, not an exact copy of every MOTIS option.
- Device headsign: `Portal Sur T6`; mapped server headsign: `Portal Sur - JFK Coop. Financiera`. The raw MOTIS headsign was the route code `G12`, so its `tripTo.name` was used.

Mobile starts warm-up as soon as an active version is known and reuses update-verification decode work. Inflate runs in 16 KiB input chunks; parsing yields between bounded profile/trip/footpath sections, targeting an 8 ms slice. Metadata JSON parsing and individual sections remain synchronous, and runtime GC can exceed that target. Development builds log total decode time, maximum blocking slice and yield count with the `[offline]` prefix. Actual emulator timing and interaction still need to be checked by the UI author.

## Search checks and limitations

Broad `portal norte` searches put the station first and collapse numbered platforms, B924 and the nearby feeder area. The feed omits the parent for `Portal Norte Alimentación`; a matching station-name stem within 150 m supplies search-only grouping. `portal norte 2-10` returns that platform explicitly. Stop codes and parent IDs are retained by the pipeline.

Merged results deduplicate equal normalized names within 150 m, preferring stations and then local entries. Of the three MOTIS POIs named `Universidad Nacional`, one is 106 m from the station and is removed. The other two are approximately 1.09 km and 1.49 km away and remain under the requested proximity rule. This does not merge all locations on the university campus into one result.

Approximate straight-line walking can cross roads, waterways and station boundaries. No real-time vehicle data is included. Frequency service without exact times uses a deterministic headway phase. This fixed 100-pair sample is not an exhaustive correctness proof.

## Validation

The 46-test Vitest suite covers concurrency in both arrival orders, deadlines including cold local work, cancellation of engines that ignore signals, hook debounce/loading/error/stale-state behavior, startup warm-up, async decode equivalence/yield/cancellation, binary versions, station/platform selection, search grouping/ranking/deduplication, scheduled/frequency/arrive-by alternatives, calendars, overtaking, headsigns, build timestamps and sync recovery with checksum/commit failures.

`pnpm typecheck`, `pnpm test`, and `npx expo lint` in `apps/mobile` pass. Screens, components, `src/ui`, and `src/state` were not edited. No commit or deployment was made; Metro was left running. Native rendering and emulator performance were not re-tested in this implementation pass.
