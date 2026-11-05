import { expect, it, vi } from "vitest";
import {
  RoutingService,
  SearchService,
  OfflinePlaceSearch,
  type Place,
  type TripResult,
} from "./index";
const p: Place = {
  id: "station",
  name: "Universidad Nacional",
  kind: "station",
  lat: 4.6,
  lon: -74.1,
};
const result: TripResult = {
  source: "server",
  dataVersion: "test",
  generatedAt: "",
  itineraries: [
    { id: "one", departure: "", arrival: "", transfers: 0, legs: [] },
  ],
};
const request = { from: p, to: p, time: "2026-10-22T16:13:00-05:00" };
it("emits a server route before a pending device and never downgrades it", async () => {
  const device = vi.fn(() => new Promise<TripResult>(() => {}));
  const iterator = new RoutingService(
    { plan: device },
    { plan: async () => result },
  ).plan(request);
  expect(
    await Promise.race([
      iterator.next(),
      new Promise((resolve) => setTimeout(() => resolve("blocked"), 30)),
    ]),
  ).toMatchObject({ value: { result, isLoading: false } });
  await iterator.return(undefined);
});
it("starts server search immediately and emits it before a pending device", async () => {
  const server = vi.fn(async () => [p]);
  const iterator = new SearchService(
    { search: () => new Promise(() => {}) },
    { search: server },
  ).search("universidad");
  const next = iterator.next();
  expect(
    await Promise.race([
      next,
      new Promise((resolve) => setTimeout(() => resolve("blocked"), 30)),
    ]),
  ).toMatchObject({
    value: { places: [p], isLoading: false, isUpgrading: true },
  });
  await iterator.return(undefined);
  expect(server).toHaveBeenCalled();
});
it("collapses child platforms unless a query targets their code", async () => {
  const child = {
    ...p,
    id: "platform",
    name: "Universidad Nacional 2-10",
    kind: "stop" as const,
    parentStationId: p.id,
    code: "2-10",
  };
  const search = new OfflinePlaceSearch([child, p]);
  expect(await search.search("universidad")).toEqual([p]);
  expect(await search.search("universidad 2-10")).toEqual([child]);
});
it("merges nearby names with local stations preferred, even when local finishes last", async () => {
  const service = new SearchService(
    { search: async () => [p] },
    {
      search: async () => [
        { ...p, id: "poi1", kind: "poi" },
        { ...p, id: "poi2", kind: "poi", lat: p.lat + 0.0005 },
      ],
    },
  );
  const states = [];
  for await (const state of service.search("universidad")) states.push(state);
  expect(states.at(-1)?.places).toEqual([p]);
});

it("emits device search while server is pending, then merges its answer", async () => {
  let finish!: (places: Place[]) => void;
  const server = new Promise<Place[]>((resolve) => {
    finish = resolve;
  });
  const iterator = new SearchService(
    { search: async () => [p] },
    { search: () => server },
  ).search("universidad");
  expect((await iterator.next()).value).toMatchObject({
    places: [p],
    isLoading: false,
    isUpgrading: true,
  });
  const address = {
    ...p,
    id: "address",
    name: "Universidad calle 1",
    kind: "address" as const,
  };
  finish([address]);
  expect((await iterator.next()).value).toMatchObject({
    places: [p, address],
    isLoading: false,
    isUpgrading: false,
  });
  await iterator.return(undefined);
});
it("keeps the search deadline relative to query start, including cold device work", async () => {
  vi.useFakeTimers();
  try {
    let finish!: (places: Place[]) => void;
    const iterator = new SearchService(
      {
        search: () =>
          new Promise<Place[]>((resolve) => {
            finish = resolve;
          }),
      },
      { search: () => new Promise(() => {}) },
      { timeoutMs: 100 },
    ).search("universidad");
    const first = iterator.next();
    await vi.advanceTimersByTimeAsync(100);
    expect((await first).value).toMatchObject({
      isLoading: false,
      error: expect.any(Error),
      isUpgrading: true,
    });
    finish([p]);
    expect((await iterator.next()).value).toMatchObject({
      places: [p],
      isUpgrading: false,
    });
    await iterator.return(undefined);
  } finally {
    vi.useRealTimers();
  }
});
it("cancels while both engines ignore AbortSignal without emitting a stale result", async () => {
  const controller = new AbortController();
  const iterator = new SearchService(
    { search: () => new Promise(() => {}) },
    { search: () => new Promise(() => {}) },
  ).search("universidad", controller.signal);
  const next = iterator.next();
  controller.abort(new Error("obsolete"));
  await expect(next).rejects.toThrow("obsolete");
});
it("orders exact and prefix matches ahead of substrings, then stations ahead of POIs", async () => {
  const station = { ...p, name: "Museo" };
  const poi = { ...p, id: "poi", kind: "poi" as const, name: "Museo" };
  const prefix = { ...poi, id: "prefix", name: "Museo Nacional" };
  const substring = { ...station, id: "substring", name: "Gran Museo" };
  expect(
    await new OfflinePlaceSearch([substring, prefix, poi, station]).search(
      "museo",
    ),
  ).toEqual([station, poi, prefix, substring]);
});
it("does not deduplicate distant places with the same name", async () => {
  const other = { ...p, id: "distant", lat: p.lat + 0.1 };
  const iterator = new SearchService(
    { search: async () => [p] },
    { search: async () => [other] },
  ).search("universidad");
  const states = [];
  for await (const state of iterator) states.push(state);
  expect(states.at(-1)?.places).toEqual([p, other]);
});

it("groups nearby feeder areas with a station stem when GTFS omits the parent", async () => {
  const station = { ...p, name: "Portal Norte - Unicervantes" };
  const feeder = {
    ...p,
    id: "feeder",
    name: "Portal Norte Alimentación",
    kind: "stop" as const,
  };
  const search = new OfflinePlaceSearch([feeder, station]);
  expect(await search.search("portal norte")).toEqual([station]);
  expect(await search.search("portal norte alimentacion")).toEqual([feeder]);
});

it("does not reintroduce a collapsed feeder area from remote search", async () => {
  const station = { ...p, name: "Portal Norte - Unicervantes" };
  const feeder = {
    ...p,
    id: "feeder",
    name: "Portal Norte Alimentación",
    kind: "stop" as const,
  };
  const service = new SearchService(
    { search: async () => [station] },
    { search: async () => [feeder] },
  );
  const states = [];
  for await (const state of service.search("portal norte")) states.push(state);
  expect(states.at(-1)?.places).toEqual([station]);
});
it("dispatches the network call before synchronous device work begins", async () => {
  const calls: string[] = [];
  const service = new RoutingService(
    {
      plan: async () => {
        calls.push("device");
        return { ...result, source: "device" };
      },
    },
    {
      plan: async () => {
        calls.push("server");
        return result;
      },
    },
  );
  for await (const _state of service.plan(request)) {
    /* consume */
  }
  expect(calls).toEqual(["server", "device"]);
});
