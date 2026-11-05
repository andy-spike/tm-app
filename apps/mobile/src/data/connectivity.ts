import * as Network from "expo-network";
import { useEffect, useSyncExternalStore } from "react";
export interface Connectivity {
  connected: boolean;
  internetReachable: boolean;
  downloadAllowed: boolean;
  type: Network.NetworkStateType;
}
let state: Connectivity = {
  connected: false,
  internetReachable: false,
  downloadAllowed: false,
  type: Network.NetworkStateType.UNKNOWN,
};
const listeners = new Set<() => void>();
let subscription:
  | ReturnType<typeof Network.addNetworkStateListener>
  | undefined;
function update(value: Network.NetworkState) {
  const type = value.type ?? Network.NetworkStateType.UNKNOWN;
  state = {
    type,
    connected: !!value.isConnected,
    internetReachable: !!value.isInternetReachable,
    downloadAllowed:
      !!value.isConnected &&
      (type === Network.NetworkStateType.WIFI ||
        type === Network.NetworkStateType.ETHERNET),
  };
  listeners.forEach((fn) => fn());
}
export const getConnectivity = (): Connectivity => state;
export const subscribeConnectivity = (fn: () => void): (() => void) => {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
};
/** Start once from startDataServices. Localhost servers only require a connected network. */
export function startConnectivity(): () => void {
  if (!subscription) {
    subscription = Network.addNetworkStateListener(update);
    void Network.getNetworkStateAsync()
      .then(update)
      .catch(() => {});
  }
  return () => {
    subscription?.remove();
    subscription = undefined;
  };
}
/** Wi-Fi/ethernet permit automatic downloads. SDK 58 does not expose metered status. */
export function useConnectivity(): Connectivity {
  useEffect(() => {
    startConnectivity();
  }, []);
  return useSyncExternalStore(
    subscribeConnectivity,
    getConnectivity,
    getConnectivity,
  );
}
