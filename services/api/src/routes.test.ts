import assert from "node:assert/strict";
import { test } from "node:test";
import { parseRange, route, toRelease } from "./routes.ts";

test("routes known paths and rejects unsafe ones", () => {
  assert.deepEqual(route("GET", "/v1/releases/latest"), { kind: "latest" });
  assert.deepEqual(route("GET", "/v1/bundles/2026-01-01_X/timetable.bin"), {
    kind: "file",
    version: "2026-01-01_X",
    name: "timetable.bin",
  });
  assert.equal(route("GET", "/v1/bundles/../timetable.bin").kind, "notFound");
  assert.equal(route("GET", "/v1/bundles/v/secret.txt").kind, "notFound");
  assert.equal(route("POST", "/v1/releases/latest").kind, "notFound");
});

test("parses byte ranges", () => {
  assert.equal(parseRange(null, 10), null);
  assert.deepEqual(parseRange("bytes=2-4", 10), { offset: 2, length: 3 });
  assert.deepEqual(parseRange("bytes=5-", 10), { offset: 5, length: 5 });
  assert.deepEqual(parseRange("bytes=-3", 10), { offset: 7, length: 3 });
  assert.equal(parseRange("bytes=20-", 10), "invalid");
});

test("maps rows to client releases with versioned paths", () => {
  const release = toRelease({
    version: "v1",
    schema_version: 1,
    feed_start: "2026-01-01",
    feed_end: "2026-12-31",
    built_at: "2026-11-10T00:00:00Z",
    published_at: "2026-11-10T00:00:01Z",
    min_android_version: 1,
    files: JSON.stringify([{ name: "timetable.bin", size: 1, sha256: "ab" }]),
  });
  assert.equal(release.files[0].path, "/v1/bundles/v1/timetable.bin");
  assert.equal(release.feedEnd, "2026-12-31");
});
