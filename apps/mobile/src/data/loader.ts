import { File } from "expo-file-system";
import { DeviceRouter, OfflinePlaceSearch } from "@tm/core";
import { decodeOfflineBundle } from "./decode";
import { bundleSync } from "./sync";
let cached:
  | {
      version: string;
      value: Promise<{ router: DeviceRouter; search: OfflinePlaceSearch }>;
    }
  | undefined;
/** Lazily decode once per active version. Existing requests keep their original router. */
export async function loadOfflineData(): Promise<{
  router: DeviceRouter;
  search: OfflinePlaceSearch;
}> {
  const directory = await bundleSync.activeDirectory();
  const version = directory.name;
  const status = bundleSync.getSnapshot();
  const builtAt =
    status.activeVersion === version ? status.activeBuiltAt : undefined;
  if (!cached || cached.version !== version) {
    const value = (async () => {
      const timetable =
        bundleSync.takePreparedTimetable(version) ??
        (await decodeOfflineBundle(
          await new File(directory, "timetable.bin").bytes(),
        ));
      timetable.builtAt ??= builtAt;
      return {
        router: new DeviceRouter(timetable),
        search: new OfflinePlaceSearch(timetable.stops),
      };
    })();
    cached = { version, value };
    value.catch(() => {
      if (cached?.value === value) cached = undefined;
    });
  }
  return cached.value;
}
