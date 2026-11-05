import { beforeEach, describe, expect, it, vi } from "vitest";
import { encodeBundle, type BundleManifest, type Timetable } from "./index";

// Exercise update/commit recovery against a filesystem with injectable failures.
const disk = vi.hoisted(() => {
  const files = new Map<string, Uint8Array>();
  const directories = new Set<string>();
  const downloads = new Map<string, Uint8Array>();
  const failures = { integrity: false, commit: false };
  const path = (parts: (string | { uri: string })[]) =>
    parts.map((p) => (typeof p === "string" ? p : p.uri)).join("/");
  class File {
    uri: string;
    constructor(...parts: (string | { uri: string })[]) {
      this.uri = path(parts);
    }
    get name() {
      return this.uri.split("/").at(-1)!;
    }
    get exists() {
      return files.has(this.uri);
    }
    get size() {
      return files.get(this.uri)?.length ?? 0;
    }
    async text() {
      return new TextDecoder().decode(await this.bytes());
    }
    async bytes() {
      const bytes = files.get(this.uri);
      if (!bytes) throw new Error("File missing");
      return bytes;
    }
    async write(value: string) {
      files.set(this.uri, new TextEncoder().encode(value));
    }
    async move(to: File) {
      if (failures.commit && to.name.startsWith("commit-"))
        throw new Error("Interrupted commit");
      if (files.has(to.uri)) throw new Error("Commit must never overwrite");
      files.set(to.uri, await this.bytes());
      files.delete(this.uri);
      this.uri = to.uri;
    }
    async digest(algorithm: string) {
      if (algorithm !== "SHA-256") throw new Error("Wrong algorithm");
      return (failures.integrity ? "b" : "a").repeat(64);
    }
    delete() {
      files.delete(this.uri);
    }
    static async downloadFileAsync(
      url: string,
      file: File,
      options: { onProgress: (p: { bytesWritten: number }) => void },
    ) {
      const bytes = downloads.get(new URL(url).pathname);
      if (!bytes) throw new Error("Download failed");
      files.set(file.uri, bytes);
      options.onProgress({ bytesWritten: bytes.length });
      return file;
    }
  }
  class Directory {
    uri: string;
    constructor(...parts: (string | { uri: string })[]) {
      this.uri = path(parts);
    }
    get name() {
      return this.uri.split("/").at(-1)!;
    }
    get exists() {
      return directories.has(this.uri);
    }
    create() {
      directories.add(this.uri);
    }
    list() {
      return [
        ...[...directories]
          .filter(
            (p) =>
              p.startsWith(`${this.uri}/`) &&
              !p.slice(this.uri.length + 1).includes("/"),
          )
          .map((p) => new Directory(p)),
        ...[...files.keys()]
          .filter(
            (p) =>
              p.startsWith(`${this.uri}/`) &&
              !p.slice(this.uri.length + 1).includes("/"),
          )
          .map((p) => new File(p)),
      ];
    }
    async move(to: Directory) {
      directories.add(to.uri);
      for (const [key, bytes] of files)
        if (key.startsWith(`${this.uri}/`)) {
          files.set(to.uri + key.slice(this.uri.length), bytes);
          files.delete(key);
        }
      directories.delete(this.uri);
      this.uri = to.uri;
    }
    delete() {
      for (const key of files.keys())
        if (key.startsWith(`${this.uri}/`)) files.delete(key);
      directories.delete(this.uri);
    }
  }
  return {
    File,
    Directory,
    Paths: { document: "documents" },
    files,
    directories,
    downloads,
    failures,
  };
});
vi.mock("../../../apps/mobile/node_modules/expo-file-system", () => disk);
vi.mock("../../../apps/mobile/src/data/connectivity", () => ({
  getConnectivity: () => ({ connected: true, downloadAllowed: true }),
  subscribeConnectivity: () => () => {},
}));
import {
  BundleSyncManager,
  parseManifest,
} from "../../../apps/mobile/src/data/sync";
let manifest: BundleManifest;
function publish(version: string, createdAt: string) {
  const timetable: Timetable = {
    version,
    feedStart: "2026-01-01",
    feedEnd: "2026-12-31",
    stops: [],
    routes: [],
    services: [],
    patterns: [],
    footpaths: [],
  };
  const binary = encodeBundle(timetable),
    places = new TextEncoder().encode("[]");
  disk.downloads.set(`/${version}/timetable.bin`, binary);
  disk.downloads.set(`/${version}/places.json`, places);
  manifest = {
    schemaVersion: 1,
    version,
    createdAt,
    feedStart: timetable.feedStart,
    feedEnd: timetable.feedEnd,
    files: [
      { name: "timetable.bin", size: binary.length, sha256: "a".repeat(64) },
      { name: "places.json", size: places.length, sha256: "a".repeat(64) },
    ],
  };
}
beforeEach(() => {
  disk.files.clear();
  disk.directories.clear();
  disk.downloads.clear();
  disk.failures.commit = false;
  disk.failures.integrity = false;
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => new Response(JSON.stringify(manifest))),
  );
  publish("v1", "2026-10-22T00:00:00Z");
});
describe("bundle sync transactions", () => {
  it("activates a verified download, retains the previous version and recovers on restart", async () => {
    const sync = new BundleSyncManager();
    await sync.check();
    expect(sync.getSnapshot().activeVersion).toBe("v1");
    expect(sync.getSnapshot().activeBuiltAt).toBe("2026-10-22T00:00:00.000Z");
    publish("v2", "2026-10-23T00:00:00Z");
    await sync.check();
    expect(sync.getSnapshot().activeVersion).toBe("v2");
    expect(disk.directories.has("documents/tm-bundles/v1")).toBe(true);
    const restart = new BundleSyncManager();
    await restart.initialize();
    expect(restart.getSnapshot().activeVersion).toBe("v2");
    expect(restart.getSnapshot().activeBuiltAt).toBe(
      "2026-10-23T00:00:00.000Z",
    );
    disk.files.delete("documents/tm-bundles/v2/timetable.bin");
    const recovered = new BundleSyncManager();
    await recovered.initialize();
    expect(recovered.getSnapshot().activeVersion).toBe("v1");
  });
  it("retains the active bundle on checksum failure and interrupted activation", async () => {
    const sync = new BundleSyncManager();
    await sync.check();
    publish("v2", "2026-10-23T00:00:00Z");
    disk.failures.integrity = true;
    await sync.check();
    expect(sync.getSnapshot()).toMatchObject({
      status: "error",
      activeVersion: "v1",
    });
    disk.failures.integrity = false;
    disk.failures.commit = true;
    await sync.check();
    expect(sync.getSnapshot().activeVersion).toBe("v1");
    const restarted = new BundleSyncManager();
    await restarted.initialize();
    expect(restarted.getSnapshot().activeVersion).toBe("v1");
  });
  it("rejects traversal, duplicate file entries and unsupported manifests", () => {
    expect(() => parseManifest({ ...manifest, version: ".." })).toThrow();
    expect(() =>
      parseManifest({
        ...manifest,
        files: [manifest.files[0], manifest.files[0]],
      }),
    ).toThrow();
    expect(() => parseManifest({ ...manifest, schemaVersion: 9 })).toThrow();
    expect(() => parseManifest({ ...manifest, builtAt: "invalid" })).toThrow();
    expect(
      parseManifest({ ...manifest, builtAt: "2026-10-21T00:00:00Z" }).builtAt,
    ).toBe("2026-10-21T00:00:00.000Z");
  });
});
