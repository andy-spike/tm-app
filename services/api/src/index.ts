import {
  json,
  parseRange,
  route,
  toRelease,
  type ReleaseRow,
} from "./routes.ts";

interface Env {
  DB: D1Database;
  BUNDLES: R2Bucket;
}

const CONTENT_TYPES: Record<string, string> = {
  "timetable.bin": "application/octet-stream",
  "bogota.pmtiles": "application/vnd.pmtiles",
};

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const match = route(request.method, url.pathname);
    switch (match.kind) {
      case "health":
        return json({ ok: true });
      case "latest": {
        const row = await env.DB.prepare(
          "SELECT * FROM bundle_releases WHERE published_at IS NOT NULL ORDER BY published_at DESC LIMIT 1",
        ).first<ReleaseRow>();
        if (!row) return json({ error: "no_release" }, { status: 404 });
        // Short cache: clients poll at most daily, and a new release should appear quickly.
        return json(toRelease(row), {
          headers: { "cache-control": "public, max-age=60" },
        });
      }
      case "file": {
        const key = `${match.version}/${match.name}`;
        const head = await env.BUNDLES.head(key);
        if (!head) return json({ error: "not_found" }, { status: 404 });
        const etag = head.httpEtag;
        const headers = new Headers({
          "content-type":
            CONTENT_TYPES[match.name] ?? "application/json; charset=utf-8",
          // Paths are versioned, so content never changes.
          "cache-control": "public, max-age=31536000, immutable",
          etag,
          "accept-ranges": "bytes",
        });
        if (request.headers.get("if-none-match") === etag)
          return new Response(null, { status: 304, headers });
        const range = parseRange(request.headers.get("range"), head.size);
        if (range === "invalid") {
          headers.set("content-range", `bytes */${head.size}`);
          return new Response(null, { status: 416, headers });
        }
        if (request.method === "HEAD") {
          headers.set("content-length", String(head.size));
          return new Response(null, { headers });
        }
        const object = await env.BUNDLES.get(key, range ? { range } : {});
        if (!object) return json({ error: "not_found" }, { status: 404 });
        if (range) {
          headers.set(
            "content-range",
            `bytes ${range.offset}-${range.offset + range.length - 1}/${head.size}`,
          );
          headers.set("content-length", String(range.length));
          return new Response(object.body, { status: 206, headers });
        }
        headers.set("content-length", String(head.size));
        return new Response(object.body, { headers });
      }
      default:
        return json({ error: "not_found" }, { status: 404 });
    }
  },
} satisfies ExportedHandler<Env>;
