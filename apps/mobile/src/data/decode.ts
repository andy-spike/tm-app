import { decodeBundleAsync } from "@tm/core";
/** Both update verification and startup decoding use the cooperative decoder. */
export function decodeOfflineBundle(bytes: Uint8Array, signal?: AbortSignal) {
  return decodeBundleAsync(bytes, {
    signal,
    onTiming: (timing) => {
      if (typeof __DEV__ !== "undefined" && __DEV__)
        console.info(
          `[offline] decode ${timing.durationMs.toFixed(0)} ms; longest slice ${timing.maxBlockingMs.toFixed(0)} ms; ${timing.yields} yields`,
        );
    },
  });
}
