import { MotisClient, RoutingService, SearchService } from "@tm/core";
import { MOTIS_URL } from "./config";
import {
  getConnectivity,
  startConnectivity,
  subscribeConnectivity,
} from "./connectivity";
import { loadOfflineData } from "./loader";
import { bundleSync } from "./sync";
const server = new MotisClient(MOTIS_URL);
const options = {
  online: () =>
    getConnectivity().connected || getConnectivity().type === "UNKNOWN",
};
export const routingService = new RoutingService(
  {
    plan: async (request, signal) =>
      (await loadOfflineData()).router.plan(request, signal),
  },
  server,
  options,
);
export const searchService = new SearchService(
  {
    search: async (text, signal) =>
      (await loadOfflineData()).search.search(text, signal),
  },
  server,
  options,
);
let stop: (() => void) | undefined;
/** Idempotent startup, called by the Expo entrypoint and lazily by hooks. */
export function startDataServices(): () => void {
  if (stop) return stop;
  const stopConnectivity = startConnectivity();
  let wasAllowed = false,
    wasConnected = false;
  const onNetwork = () => {
    const state = getConnectivity();
    if (
      (state.connected && !wasConnected) ||
      (state.downloadAllowed && !wasAllowed)
    )
      void bundleSync.check();
    wasAllowed = state.downloadAllowed;
    wasConnected = state.connected;
  };
  let warmedVersion: string | undefined;
  const warm = () => {
    const version = bundleSync.getSnapshot().activeVersion;
    if (!version || version === warmedVersion) return;
    warmedVersion = version;
    void loadOfflineData().catch(() => {
      if (warmedVersion === version) warmedVersion = undefined;
    });
  };
  const stopBundleWatch = bundleSync.subscribe(warm);
  warm();
  const unsubscribe = subscribeConnectivity(onNetwork);
  void bundleSync
    .initialize()
    .then(() => {
      warm();
      onNetwork();
    })
    .catch(() => bundleSync.check());
  stop = () => {
    unsubscribe();
    stopBundleWatch();
    stopConnectivity();
    bundleSync.cancel();
    stop = undefined;
  };
  return stop;
}
