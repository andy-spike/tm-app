// Generates the Android app's offline MapLibre styles (light and dark) for the bundled
// Protomaps archive. Glyphs are fetched once into the app's assets; no sprites are used.
import { mkdir, writeFile, stat } from "node:fs/promises";
import { resolve } from "node:path";
import { layers, namedFlavor } from "@protomaps/basemaps";

const root = resolve(import.meta.dirname, "../../..");
const out = resolve(root, "apps/android/app/src/main/assets/map");
const fonts = ["Noto Sans Regular", "Noto Sans Medium", "Noto Sans Italic"];
// Latin, Latin-1 supplement/extended and general punctuation cover Spanish street names.
const ranges = ["0-255", "256-511", "8192-8447"];

for (const [name, flavorName] of [["light", "light"], ["dark", "dark"]] as const) {
  const flavor = namedFlavor(flavorName);
  const style = {
    version: 8,
    name: `tm-${name}`,
    // The app substitutes the absolute archive path at runtime.
    sources: {
      protomaps: {
        type: "vector",
        url: "pmtiles://{archive}",
        attribution: "© OpenStreetMap",
      },
    },
    glyphs: "asset://map/glyphs/{fontstack}/{range}.pbf",
    layers: layers("protomaps", flavor, { lang: "es" }).filter(
      // POIs need sprites and compete with transit stops; drop them.
      (l) => !l.id.startsWith("pois") && !("layout" in l && l.layout && "icon-image" in l.layout),
    ),
  };
  await mkdir(out, { recursive: true });
  await writeFile(resolve(out, `style-${name}.json`), JSON.stringify(style));
}

for (const font of fonts)
  for (const range of ranges) {
    const dir = resolve(out, "glyphs", font);
    const file = resolve(dir, `${range}.pbf`);
    try {
      await stat(file);
      continue;
    } catch {}
    const url = `https://raw.githubusercontent.com/protomaps/basemaps-assets/main/fonts/${encodeURIComponent(font)}/${range}.pbf`;
    const res = await fetch(url);
    if (!res.ok) throw new Error(`${url}: ${res.status}`);
    await mkdir(dir, { recursive: true });
    await writeFile(file, Buffer.from(await res.arrayBuffer()));
  }
console.log(`Wrote styles and glyphs to ${out}`);
