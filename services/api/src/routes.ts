/** Pure request handling, separated from bindings so it runs under node --test. */
export interface ReleaseFile {
  name: string;
  size: number;
  sha256: string;
}
export interface ReleaseRow {
  version: string;
  schema_version: number;
  feed_start: string;
  feed_end: string;
  built_at: string;
  files: string;
  min_android_version: number;
  published_at: string;
}
export interface Release {
  version: string;
  schemaVersion: number;
  feedStart: string;
  feedEnd: string;
  builtAt: string;
  publishedAt: string;
  minAndroidVersion: number;
  files: (ReleaseFile & { path: string })[];
}

const SAFE = /^[0-9A-Za-z][0-9A-Za-z_.-]*$/;
export const FILE_NAMES = new Set([
  "timetable.bin",
  "places.json",
  "shapes.json",
  "bogota.pmtiles",
  "manifest.json",
]);

export function toRelease(row: ReleaseRow): Release {
  const files = JSON.parse(row.files) as ReleaseFile[];
  return {
    version: row.version,
    schemaVersion: row.schema_version,
    feedStart: row.feed_start,
    feedEnd: row.feed_end,
    builtAt: row.built_at,
    publishedAt: row.published_at,
    minAndroidVersion: row.min_android_version,
    files: files.map((f) => ({
      ...f,
      path: `/v1/bundles/${row.version}/${f.name}`,
    })),
  };
}

export type Route =
  | { kind: "health" }
  | { kind: "latest" }
  | { kind: "file"; version: string; name: string }
  | { kind: "notFound" };

export function route(method: string, pathname: string): Route {
  if (method !== "GET" && method !== "HEAD") return { kind: "notFound" };
  if (pathname === "/health") return { kind: "health" };
  if (pathname === "/v1/releases/latest") return { kind: "latest" };
  const parts = pathname.split("/");
  // ["", "v1", "bundles", version, name]
  if (
    parts.length === 5 &&
    parts[1] === "v1" &&
    parts[2] === "bundles" &&
    SAFE.test(parts[3]) &&
    !parts[3].includes("..") &&
    FILE_NAMES.has(parts[4])
  )
    return { kind: "file", version: parts[3], name: parts[4] };
  return { kind: "notFound" };
}

export function json(body: unknown, init: ResponseInit = {}): Response {
  const headers = new Headers(init.headers);
  headers.set("content-type", "application/json; charset=utf-8");
  return new Response(JSON.stringify(body), { ...init, headers });
}

/** Parses a single "bytes=a-b" range; multi-range requests are served whole. */
export function parseRange(
  header: string | null,
  size: number,
): { offset: number; length: number } | null | "invalid" {
  if (!header) return null;
  const m = /^bytes=(\d*)-(\d*)$/.exec(header.trim());
  if (!m || (m[1] === "" && m[2] === "")) return null;
  let start: number, end: number;
  if (m[1] === "") {
    const suffix = Number(m[2]);
    start = Math.max(0, size - suffix);
    end = size - 1;
  } else {
    start = Number(m[1]);
    end = m[2] === "" ? size - 1 : Math.min(Number(m[2]), size - 1);
  }
  if (start > end || start >= size) return "invalid";
  return { offset: start, length: end - start + 1 };
}
