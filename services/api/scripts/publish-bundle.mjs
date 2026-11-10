#!/usr/bin/env node
// Uploads the pipeline's latest bundle to R2 and publishes its release row in D1.
// Local by default (checkout-local Miniflare state); pass --remote for the deployed account.
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { spawnSync } from "node:child_process";

const api = resolve(import.meta.dirname, "..");
const root = resolve(api, "../..");
const remote = process.argv.includes("--remote");
const target = remote ? ["--remote"] : ["--local", "--persist-to", resolve(root, ".world/wrangler")];
const out = resolve(root, "services/pipeline/out");
const { version } = JSON.parse(readFileSync(resolve(out, "latest.json"), "utf8"));
const dir = resolve(out, version);
const manifest = JSON.parse(readFileSync(resolve(dir, "manifest.json"), "utf8"));

const wrangler = (...args) => {
  const result = spawnSync("pnpm", ["exec", "wrangler", ...args], { cwd: api, stdio: "inherit" });
  if (result.status !== 0) throw new Error(`wrangler ${args.slice(0, 3).join(" ")} failed`);
};
const sql = (s) => `'${String(s).replaceAll("'", "''")}'`;

wrangler("d1", "migrations", "apply", "tm", ...target);
for (const file of [...manifest.files.map((f) => f.name), "manifest.json"]) {
  console.log(`Uploading ${version}/${file}`);
  wrangler("r2", "object", "put", `tm-bundles/${version}/${file}`, "--file", resolve(dir, file), ...target);
}
// Publish last: apps never see a release whose files are incomplete.
wrangler(
  "d1", "execute", "tm", ...target, "--command",
  `INSERT INTO bundle_releases (version, schema_version, feed_start, feed_end, built_at, files, published_at)
   VALUES (${sql(version)}, ${manifest.schemaVersion}, ${sql(manifest.feedStart)}, ${sql(manifest.feedEnd)},
           ${sql(manifest.builtAt ?? manifest.createdAt)}, ${sql(JSON.stringify(manifest.files))},
           strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
   ON CONFLICT(version) DO UPDATE SET files = excluded.files, published_at = excluded.published_at`,
);
console.log(`Published ${version}${remote ? " (remote)" : " (local)"}`);
