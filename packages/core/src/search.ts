import type { Place, PlaceEngine } from "./domain";
import { distance } from "./router";
export const normalize = (text: string): string =>
  text
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
function editDistance(a: string, b: string): number {
  let row = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    const next = [i];
    for (let j = 1; j <= b.length; j++)
      next[j] = Math.min(
        next[j - 1] + 1,
        row[j] + 1,
        row[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1),
      );
    row = next;
  }
  return row[b.length];
}
const kindRank = (p: Place): number =>
  p.kind === "station" ? 0 : p.kind === "stop" ? 1 : 2;
function matchRank(name: string, query: string): number {
  return name.startsWith(query) ? 0 : name.includes(query) ? 1 : 2;
}
export function rankPlaces(places: Place[], text: string): Place[] {
  const query = normalize(text);
  return places
    .slice()
    .sort(
      (a, b) =>
        matchRank(normalize(a.name), query) -
          matchRank(normalize(b.name), query) ||
        kindRank(a) - kindRank(b) ||
        a.name.localeCompare(b.name),
    );
}
/** Name/proximity duplicates favor stations, then local entries, irrespective of arrival order. */
export function mergePlaces(
  local: Place[],
  remote: Place[],
  text: string,
): Place[] {
  const stations = local.filter((p) => p.kind === "station");
  const canonical = (place: Place): Place => {
    if (place.kind !== "stop") return place;
    const parent = stations.find((station) => {
      const stem = normalize(station.name.split(/\s+-\s+/)[0]);
      const name = normalize(place.name);
      return (
        place.parentStationId === station.id ||
        ((name === stem || name.startsWith(`${stem} `)) &&
          distance(place, station) <= 150)
      );
    });
    if (!parent) return place;
    const parentWords = new Set(normalize(parent.name).split(" "));
    const suffix = normalize(place.name)
      .split(" ")
      .filter((w) => !parentWords.has(w))
      .join(" ");
    const targeted = [normalize(place.code ?? ""), suffix]
      .filter(Boolean)
      .some((target) => ` ${normalize(text)} `.includes(` ${target} `));
    return targeted ? place : parent;
  };
  const candidates = [
    ...local.map((place) => ({ place: canonical(place), local: true })),
    ...remote.map((place) => ({ place: canonical(place), local: false })),
  ].sort(
    (a, b) =>
      kindRank(a.place) - kindRank(b.place) ||
      Number(b.local) - Number(a.local),
  );
  const kept: Place[] = [];
  for (const { place } of candidates) {
    if (
      kept.some(
        (p) =>
          p.id === place.id ||
          (normalize(p.name) === normalize(place.name) &&
            distance(p, place) <= 150),
      )
    )
      continue;
    kept.push(place);
  }
  return rankPlaces(kept, text);
}
/** Normalized names and parent relationships are cached once, without native dependencies. */
export class OfflinePlaceSearch implements PlaceEngine {
  private entries: {
    place: Place;
    name: string;
    words: string[];
    parent?: Place;
    targets: string[];
  }[];
  constructor(
    places: Place[],
    private limit = 20,
  ) {
    const byId = new Map(places.map((p) => [p.id, p]));
    const parents = new Set(
      places.flatMap((p) => (p.parentStationId ? [p.parentStationId] : [])),
    );
    const stations = places
      .filter((p) => p.kind === "station" || parents.has(p.id))
      .map((station) => ({
        station,
        stem: normalize(station.name.split(/\s+-\s+/)[0]),
      }));
    const stationsByWord = new Map<string, typeof stations>();
    for (const entry of stations) {
      const word = entry.stem.split(" ")[0];
      const group = stationsByWord.get(word) ?? [];
      group.push(entry);
      stationsByWord.set(word, group);
    }
    this.entries = places.map((original) => {
      const place = parents.has(original.id)
        ? { ...original, kind: "station" as const }
        : original;
      const name = normalize(place.name),
        words = name.split(" ");
      let parent = place.parentStationId
        ? byId.get(place.parentStationId)
        : undefined;
      // Some GTFS feeder areas omit parent_station, e.g. Portal Norte Alimentación.
      // Only group a matching station stem within 150m; unrelated nearby stops stay distinct.
      if (!parent && place.kind === "stop")
        parent = stationsByWord
          .get(words[0])
          ?.find(
            ({ station, stem }) =>
              station.id !== place.id &&
              (name === stem || name.startsWith(`${stem} `)) &&
              distance(station, place) <= 150,
          )?.station;
      const seen = new Set([place.id]);
      while (parent?.parentStationId && !seen.has(parent.id)) {
        seen.add(parent.id);
        parent = byId.get(parent.parentStationId);
      }
      if (parent) parent = { ...parent, kind: "station" };
      const parentWords = new Set(normalize(parent?.name ?? "").split(" "));
      const suffix = words.filter((w) => !parentWords.has(w)).join(" ");
      // Codes must match completely: platform 2-1 must not match platform 2-10.
      const targets = [normalize(place.code ?? ""), suffix].filter(Boolean);
      return { place, name, words, parent, targets };
    });
  }
  async search(text: string, signal?: AbortSignal): Promise<Place[]> {
    signal?.throwIfAborted();
    const query = normalize(text);
    if (!query) return [];
    const tokens = query.split(" "),
      matches = new Map<string, { place: Place; score: number }>();
    for (const entry of this.entries) {
      let score = matchRank(entry.name, query),
        valid = true;
      for (const token of tokens) {
        if (entry.words.some((w) => w.startsWith(token))) continue;
        if (
          token.length >= 4 &&
          entry.words.some(
            (w) =>
              Math.abs(w.length - token.length) <= 1 &&
              editDistance(token, w) <= 1,
          )
        )
          score += 4;
        else {
          valid = false;
          break;
        }
      }
      if (!valid) continue;
      const targeted = entry.targets.some((target) =>
        ` ${query} `.includes(` ${target} `),
      );
      const place = entry.parent && !targeted ? entry.parent : entry.place;
      // A more specific child query should not produce its parent as another result.
      if (
        entry.parent &&
        !targeted &&
        tokens.some(
          (t) =>
            !normalize(entry.parent!.name)
              .split(" ")
              .some((w) => w.startsWith(t)),
        )
      )
        continue;
      const previous = matches.get(place.id);
      if (!previous || score < previous.score)
        matches.set(place.id, { place, score });
    }
    return [...matches.values()]
      .sort(
        (a, b) =>
          a.score - b.score ||
          kindRank(a.place) - kindRank(b.place) ||
          a.place.name.localeCompare(b.place.name),
      )
      .slice(0, this.limit)
      .map((m) => m.place);
  }
}
