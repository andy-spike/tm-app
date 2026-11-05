import { File } from "expo-file-system";
import { bundleSync } from "./sync";
export interface OfflineMapSource {
  url: string;
  bounds: [number, number, number, number];
  minzoom: number;
  maxzoom: number;
  attribution: string;
}
/** Local vector source URL for a Protomaps-compatible style. Styles, glyphs and sprites are separate assets. */
export async function getOfflineMapSource(): Promise<
  OfflineMapSource | undefined
> {
  const directory = await bundleSync.activeDirectory();
  const file = new File(directory, "bogota.pmtiles");
  if (!file.exists) return undefined;
  return {
    url: `pmtiles://${file.uri}`,
    bounds: [-74.3, 4.3, -73.95, 4.9],
    minzoom: 0,
    maxzoom: 14,
    attribution: "© OpenStreetMap contributors; Protomaps basemap",
  };
}
