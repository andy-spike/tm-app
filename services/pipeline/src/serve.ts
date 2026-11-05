import { createServer } from "node:http";
import { readFile, stat } from "node:fs/promises";
import { createReadStream } from "node:fs";
import { resolve } from "node:path";
const port = Number(process.env.TM_BUNDLE_PORT ?? 8090);
if (!Number.isInteger(port) || port < 1 || port > 65535) {
  throw new Error("Invalid TM_BUNDLE_PORT");
}
const root = resolve(import.meta.dirname, "../out");
createServer(async (req, res) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Cache-Control", "no-store");
  try {
    const latest = JSON.parse(
      await readFile(resolve(root, "latest.json"), "utf8"),
    ) as { version: string };
    const path = new URL(req.url ?? "/", "http://localhost").pathname;
    const parts = path.split("/").filter(Boolean);
    const version = parts.length === 2 ? parts[0] : latest.version,
      name = parts.at(-1) ?? "manifest.json";
    if (
      !/^[a-zA-Z0-9_.-]+$/.test(version) ||
      ![
        "manifest.json",
        "timetable.bin",
        "places.json",
        "bogota.pmtiles",
      ].includes(name)
    ) {
      res.writeHead(404).end();
      return;
    }
    const file = resolve(root, version, name),
      info = await stat(file);
    res.setHeader(
      "Content-Type",
      name.endsWith(".json") ? "application/json" : "application/octet-stream",
    );
    res.setHeader("Content-Length", info.size);
    createReadStream(file)
      .on("error", () => res.destroy())
      .pipe(res);
  } catch {
    res.writeHead(404).end("Build a bundle with pnpm bundle:build first");
  }
}).listen(port, "0.0.0.0", () =>
  console.log(
    `Bundle server http://localhost:${port} (versioned paths supported)`,
  ),
);
