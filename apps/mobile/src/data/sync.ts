import { Directory, File, Paths } from "expo-file-system";
import { type Timetable, type BundleManifest } from "@tm/core";
import { decodeOfflineBundle } from "./decode";
import { BUNDLE_URL } from "./config";
import { getConnectivity, subscribeConnectivity } from "./connectivity";
export interface BundleStatus {
  status: "idle" | "checking" | "downloading" | "ready" | "error";
  activeVersion?: string;
  activeBuiltAt?: string;
  lastCheckedAt?: string;
  progress?: number;
  error?: string;
  downloadDeferred?: boolean;
}
const safeName = /^[A-Za-z0-9_.-]+$/;
const allowedFiles = new Set([
  "timetable.bin",
  "places.json",
  "bogota.pmtiles",
]);
/** Validate remote metadata before using it in filesystem paths. */
export function parseManifest(value: unknown): BundleManifest {
  const m = value as BundleManifest;
  if (
    !m ||
    m.schemaVersion !== 1 ||
    typeof m.version !== "string" ||
    !safeName.test(m.version) ||
    m.version === "." ||
    m.version === ".." ||
    !Number.isFinite(Date.parse(m.createdAt)) ||
    !Array.isArray(m.files) ||
    m.files.length < 2 ||
    m.files.length > 3
  )
    throw new Error("Invalid bundle manifest");
  const seen = new Set<string>();
  for (const f of m.files) {
    if (
      !allowedFiles.has(f.name) ||
      seen.has(f.name) ||
      !Number.isSafeInteger(f.size) ||
      f.size <= 0 ||
      !/^[a-f0-9]{64}$/.test(f.sha256)
    )
      throw new Error("Invalid bundle file");
    seen.add(f.name);
  }
  if (!seen.has("timetable.bin") || !seen.has("places.json"))
    throw new Error("Bundle files missing");
  if (m.builtAt !== undefined && !Number.isFinite(Date.parse(m.builtAt)))
    throw new Error("Invalid bundle build time");
  return { ...m, builtAt: new Date(m.builtAt ?? m.createdAt).toISOString() };
}
/** Owns persistent versions. All updates are serialized and failed downloads preserve the active version. */
export class BundleSyncManager {
  private root = new Directory(Paths.document, "tm-bundles");
  private state: BundleStatus = { status: "idle" };
  private listeners = new Set<() => void>();
  private initialized?: Promise<void>;
  private pending?: Promise<void>;
  private active?: BundleManifest;
  private previous?: BundleManifest;
  private prepared?: Timetable;
  /** Reuse update verification work when the loader warms the activated version. */
  takePreparedTimetable(version: string): Timetable | undefined {
    if (this.prepared?.version !== version) return undefined;
    const value = this.prepared;
    this.prepared = undefined;
    return value;
  }
  private controller?: AbortController;
  private generation = 0;
  private activeCommit?: string;
  private previousCommit?: string;
  readonly getSnapshot = (): BundleStatus => this.state;
  readonly subscribe = (fn: () => void): (() => void) => {
    this.listeners.add(fn);
    return () => {
      this.listeners.delete(fn);
    };
  };
  private publish(patch: Partial<BundleStatus>) {
    this.state = { ...this.state, ...patch };
    this.listeners.forEach((fn) => fn());
  }
  private metadataFile(name: string) {
    return new File(this.root, name);
  }
  private async commit(manifest: BundleManifest): Promise<string> {
    this.generation = Math.max(Date.now(), this.generation + 1);
    const name = `commit-${String(this.generation).padStart(16, "0")}-${manifest.version}.json`;
    const temporary = this.metadataFile(`${name}.tmp`);
    await temporary.write(JSON.stringify(manifest));
    // New immutable filename: never delete/overwrite the previous commit record.
    await temporary.move(this.metadataFile(name));
    return name;
  }
  async initialize(): Promise<void> {
    return (this.initialized ??= (async () => {
      this.root.create({ intermediates: true, idempotent: true });
      const read = async (name: string) => {
        try {
          const file = this.metadataFile(name);
          if (!file.exists) return undefined;
          const m = parseManifest(JSON.parse(await file.text()));
          const dir = new Directory(this.root, m.version);
          if (
            !m.files.every(
              (f) =>
                new File(dir, f.name).exists &&
                new File(dir, f.name).size === f.size,
            )
          )
            return undefined;
          return m;
        } catch {
          return undefined;
        }
      };
      const commits = this.root
        .list()
        .filter(
          (entry): entry is File =>
            entry instanceof File &&
            /^commit-\d{16}-.+\.json$/.test(entry.name),
        )
        .sort((a, b) => b.name.localeCompare(a.name));
      for (const entry of commits) {
        this.generation = Math.max(
          this.generation,
          Number(entry.name.slice(7, 23)),
        );
        const manifest = await read(entry.name);
        if (!manifest) continue;
        if (!this.active) {
          this.active = manifest;
          this.activeCommit = entry.name;
        } else {
          this.previous = manifest;
          this.previousCommit = entry.name;
          break;
        }
      }
      if (this.active)
        this.publish({
          status: "ready",
          activeVersion: this.active.version,
          activeBuiltAt: this.active.builtAt,
        });
    })());
  }
  async activeDirectory(): Promise<Directory> {
    await this.initialize();
    if (!this.active)
      throw new Error(
        "No offline bundle installed. Connect to Wi-Fi and sync the timetable.",
      );
    return new Directory(this.root, this.active.version);
  }
  /** Explicit allowMetered permits a cellular download for this invocation only. */
  check(options: { allowMetered?: boolean } = {}): Promise<void> {
    if (this.pending) return this.pending;
    this.pending = this.performCheck(options)
      .catch((e) => {
        this.publish({
          status: "error",
          error: e instanceof Error ? e.message : String(e),
        });
      })
      .finally(() => {
        this.pending = undefined;
      });
    return this.pending;
  }
  private async performCheck(options: { allowMetered?: boolean }) {
    await this.initialize();
    const controller = new AbortController();
    this.controller = controller;
    const timeout = setTimeout(
      () => controller.abort(new Error("Bundle update timed out")),
      10 * 60 * 1000,
    );
    const stopNetworkWatch = subscribeConnectivity(() => {
      if (!options.allowMetered && !getConnectivity().downloadAllowed)
        controller.abort(
          new Error("Download paused: Wi-Fi or ethernet required"),
        );
    });
    this.publish({
      status: "checking",
      error: undefined,
      progress: undefined,
      downloadDeferred: false,
      lastCheckedAt: new Date().toISOString(),
    });
    let staging: Directory | undefined;
    try {
      const response = await fetch(`${BUNDLE_URL}/manifest.json`, {
        signal: controller.signal,
      });
      if (!response.ok)
        throw new Error(`Bundle manifest HTTP ${response.status}`);
      const manifest = parseManifest(await response.json());
      if (
        this.active &&
        (manifest.version === this.active.version ||
          Date.parse(manifest.createdAt) <= Date.parse(this.active.createdAt))
      ) {
        this.publish({ status: "ready" });
        return;
      }
      if (!options.allowMetered && !getConnectivity().downloadAllowed) {
        this.publish({
          status: this.active ? "ready" : "idle",
          downloadDeferred: true,
        });
        return;
      }
      staging = new Directory(this.root, `tmp-${manifest.version}`);
      if (staging.exists) staging.delete();
      staging.create();
      const total = manifest.files.reduce((sum, file) => sum + file.size, 0);
      let completed = 0;
      this.publish({ status: "downloading", progress: 0 });
      for (const entry of manifest.files) {
        const file = await File.downloadFileAsync(
          `${BUNDLE_URL}/${manifest.version}/${entry.name}`,
          new File(staging, entry.name),
          {
            signal: controller.signal,
            onProgress: (p) =>
              this.publish({
                progress: Math.min(1, (completed + p.bytesWritten) / total),
              }),
          },
        );
        if (
          file.size !== entry.size ||
          (await file.digest("SHA-256")) !== entry.sha256
        )
          throw new Error(`Integrity check failed: ${entry.name}`);
        completed += entry.size;
      }
      const decoded = await decodeOfflineBundle(
        await new File(staging, "timetable.bin").bytes(),
        controller.signal,
      );
      if (decoded.version !== manifest.version)
        throw new Error("Timetable version mismatch");
      const places: unknown = JSON.parse(
        await new File(staging, "places.json").text(),
      );
      if (!Array.isArray(places)) throw new Error("Invalid places index");
      await new File(staging, "manifest.json").write(JSON.stringify(manifest));
      controller.signal.throwIfAborted();
      const finalDir = new Directory(this.root, manifest.version);
      if (finalDir.exists) finalDir.delete();
      await staging.move(finalDir);
      staging = undefined;
      const commit = await this.commit(manifest);
      this.previous = this.active;
      this.previousCommit = this.activeCommit;
      this.active = manifest;
      this.activeCommit = commit;
      this.prepared = { ...decoded, builtAt: manifest.builtAt };
      this.publish({
        status: "ready",
        activeVersion: manifest.version,
        activeBuiltAt: manifest.builtAt,
        progress: 1,
      });
      // Cleanup is best-effort after the commit record is visible.
      for (const entry of this.root.list()) {
        if (
          entry instanceof File &&
          entry.name !== this.activeCommit &&
          entry.name !== this.previousCommit
        ) {
          try {
            entry.delete();
          } catch {
            /* Retry after the next update. */
          }
        }

        if (
          entry instanceof Directory &&
          entry.name !== this.active.version &&
          entry.name !== this.previous?.version
        ) {
          try {
            entry.delete();
          } catch {
            /* Retry on the next successful update. */
          }
        }
      }
    } catch (e) {
      this.publish({
        status: "error",
        error: e instanceof Error ? e.message : String(e),
      });
    } finally {
      clearTimeout(timeout);
      stopNetworkWatch();
      this.controller = undefined;
      if (staging?.exists) staging.delete();
    }
  }
  cancel(): void {
    this.controller?.abort(new Error("Bundle update cancelled"));
  }
}
export const bundleSync = new BundleSyncManager();
