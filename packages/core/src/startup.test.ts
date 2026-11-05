import { expect, it, vi } from "vitest";
const data = vi.hoisted(() => {
  const listeners = new Set<() => void>();
  let activeVersion: string | undefined;
  const publish = (version: string) => {
    activeVersion = version;
    listeners.forEach((fn) => fn());
  };
  return {
    load: vi.fn(async () => ({})),
    publish,
    listeners,
    sync: {
      subscribe: (fn: () => void) => {
        listeners.add(fn);
        return () => {
          listeners.delete(fn);
        };
      },
      getSnapshot: () => ({ activeVersion }),
      initialize: vi.fn(async () => {
        publish("v1");
      }),
      check: vi.fn(async () => {}),
      cancel: vi.fn(),
    },
  };
});
vi.mock("../../../apps/mobile/src/data/loader", () => ({
  loadOfflineData: data.load,
}));
vi.mock("../../../apps/mobile/src/data/sync", () => ({
  bundleSync: data.sync,
}));
vi.mock("../../../apps/mobile/src/data/connectivity", () => ({
  startConnectivity: () => () => {},
  subscribeConnectivity: () => () => {},
  getConnectivity: () => ({ connected: true, downloadAllowed: true }),
}));
import { startDataServices } from "../../../apps/mobile/src/data/services";
it("warms the installed bundle on startup and each activation without blocking or duplicating work", async () => {
  const stop = startDataServices();
  expect(startDataServices()).toBe(stop);
  await Promise.resolve();
  expect(data.load).toHaveBeenCalledTimes(1);
  data.publish("v1");
  expect(data.load).toHaveBeenCalledTimes(1);
  data.publish("v2");
  expect(data.load).toHaveBeenCalledTimes(2);
  stop();
  expect(data.listeners.size).toBe(0);
  expect(data.sync.cancel).toHaveBeenCalledOnce();
});
