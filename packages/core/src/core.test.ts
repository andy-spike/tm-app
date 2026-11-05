import { describe, expect, it, vi } from "vitest";
import {
  decodeBundle,
  decodeBundleAsync,
  directionHeadsign,
  encodeBundle,
  DeviceRouter,
  serviceActive,
  mapMotisPlan,
  MotisClient,
  OfflinePlaceSearch,
  RoutingService,
  SearchService,
  type Timetable,
  type Place,
  type TripResult,
  type TripRequest,
} from "./index";
const place = (id: string, lon: number): Place => ({
  id,
  lat: 4.6,
  lon,
  kind: "stop",
  name: id,
});
function network(): Timetable {
  return {
    version: "test",
    feedStart: "2026-01-01",
    feedEnd: "2026-12-31",
    stops: [place("A", -74.1), place("B", -74.08), place("C", -74.06)],
    routes: [
      {
        id: "r",
        shortName: "1",
        color: "#FF0000",
        agencyId: "1",
        system: "troncal",
      },
    ],
    services: [
      {
        start: "2026-01-01",
        end: "2026-12-31",
        weekdays: [1, 1, 1, 1, 1, 1, 1],
        exceptions: {},
      },
    ],
    footpaths: [[], [], []],
    patterns: [
      {
        route: 0,
        headsign: "C",
        stops: [0, 1, 2],
        profiles: [
          {
            times: [0, 0, 600, 600, 1200, 1200],
            pickup: [0, 0, 0],
            dropoff: [0, 0, 0],
          },
        ],
        trips: [{ departure: 28800, service: 0, profile: 0 }],
      },
    ],
  };
}
const request = (b: Timetable): TripRequest => ({
  from: b.stops[0],
  to: b.stops[2],
  time: "2026-10-22T07:55:00-05:00",
});
const result = (source: "server" | "device"): TripResult => ({
  source,
  dataVersion: "test",
  generatedAt: "2026-10-22T13:00:00Z",
  itineraries: [
    { id: "x", departure: "", arrival: "", transfers: 0, legs: [] },
  ],
});
describe("bundle codec", () => {
  it("roundtrips signed profiles, frequencies and calendar exceptions", () => {
    const b = network();
    b.patterns[0].profiles[0].times[0] = -10;
    b.services[0].exceptions["2026-10-22"] = false;
    b.patterns[0].trips[0].frequency = {
      end: 40000,
      headway: 30,
      exact: false,
    };
    expect(decodeBundle(encodeBundle(b))).toEqual(b);
  });
  it("rejects truncated, unknown versions and corrupt references", () => {
    const bytes = encodeBundle(network());
    expect(() => decodeBundle(bytes.slice(0, -1))).toThrow();
    bytes[4] = 99;
    expect(() => decodeBundle(bytes)).toThrow();
    const b = network();
    b.patterns[0].stops[0] = 99;
    expect(() => decodeBundle(encodeBundle(b))).toThrow("reference");
  });
});
describe("RAPTOR", () => {
  it("finds scheduled route with visits and matching timestamps", async () => {
    const b = network();
    const r = await new DeviceRouter(b).plan(request(b));
    expect(r.itineraries).toHaveLength(1);
    expect(r.itineraries[0].arrival).toBe("2026-10-22T13:20:00.000Z");
    const leg = r.itineraries[0].legs.find((l) => l.mode === "transit");
    expect(leg?.mode === "transit" && leg.intermediateStops[0].arrival).toBe(
      "2026-10-22T13:10:00.000Z",
    );
  });
  it("honors holidays and service ranges without inventing departures", async () => {
    const b = network();
    b.services[0].exceptions["2026-10-22"] = false;
    b.services[0].exceptions["2026-10-23"] = false;
    expect(
      (await new DeviceRouter(b).plan(request(b))).itineraries,
    ).toHaveLength(0);
    expect(serviceActive(b.services[0], "2027-01-01")).toBe(false);
  });
  it("routes arriveBy backwards", async () => {
    const b = network();
    const r = await new DeviceRouter(b).plan({
      ...request(b),
      arriveBy: true,
      time: "2026-10-22T08:25:00-05:00",
    });
    expect(r.itineraries[0].departure).toBe("2026-10-22T13:00:00.000Z");
    expect(r.itineraries[0].arrival).toBe("2026-10-22T13:20:00.000Z");
  });
  it("handles frequency windows and previous-day >24h times", async () => {
    const b = network();
    b.patterns[0].trips[0].frequency = {
      end: 32400,
      headway: 300,
      exact: false,
    };
    let r = await new DeviceRouter(b).plan({
      ...request(b),
      time: "2026-10-22T08:02:00-05:00",
    });
    expect(r.itineraries[0].arrival).toBe("2026-10-22T13:25:00.000Z");
    b.patterns[0].trips = [{ departure: 25 * 3600, service: 0, profile: 0 }];
    r = await new DeviceRouter(b).plan({
      ...request(b),
      time: "2026-10-23T00:55:00-05:00",
    });
    expect(r.itineraries[0].arrival).toBe("2026-10-23T06:20:00.000Z");
  });
  it("preserves Pareto arrival vs transfers and can walk between stops", async () => {
    const b = network();
    b.stops.push(place("D", -74.075));
    b.footpaths.push([]);
    b.footpaths[1].push({ to: 3, seconds: 100 });
    b.footpaths[3].push({ to: 1, seconds: 100 });
    b.patterns[0].profiles[0].times = [0, 0, 300, 300, 2400, 2400];
    b.patterns.push({
      route: 0,
      headsign: "express",
      stops: [3, 2],
      profiles: [{ times: [0, 0, 300, 300], pickup: [0, 0], dropoff: [0, 0] }],
      trips: [{ departure: 29400, profile: 0, service: 0 }],
    });
    const r = await new DeviceRouter(b, { maxAccessMeters: 50 }).plan(
      request(b),
    );
    expect(r.itineraries.map((i) => i.transfers)).toEqual([0, 1]);
    expect(r.itineraries[1].arrival).toBe("2026-10-22T13:15:00.000Z");
  });
  it("finds overtaking trips with distinct timing profiles", async () => {
    const b = network();
    b.patterns[0].profiles.push({
      times: [0, 0, 60, 60, 120, 120],
      pickup: [0, 0, 0],
      dropoff: [0, 0, 0],
    });
    b.patterns[0].trips.push({ departure: 29100, service: 0, profile: 1 });
    const r = await new DeviceRouter(b).plan(request(b));
    expect(r.itineraries[0].arrival).toBe("2026-10-22T13:07:00.000Z");
  });
  it("honors pickup restrictions", async () => {
    const b = network();
    b.patterns[0].profiles[0].pickup[0] = 1;
    expect(
      (await new DeviceRouter(b).plan(request(b))).itineraries,
    ).toHaveLength(0);
  });
});
describe("departure alternatives", () => {
  it("returns distinct next departures within an hour, capped at six", async () => {
    const b = network();
    b.patterns[0].trips = Array.from({ length: 25 }, (_, n) => ({
      departure: 28800 + n * 240,
      profile: 0,
      service: 0,
    }));
    // A duplicate GTFS run must not become a duplicate itinerary.
    b.patterns.push({ ...b.patterns[0] });
    const r = await new DeviceRouter(b).plan(request(b));
    expect(r.itineraries).toHaveLength(6);
    expect(r.itineraries.map((i) => i.departure)).toEqual(
      Array.from({ length: 6 }, (_, n) =>
        new Date(Date.parse("2026-10-22T13:00:00Z") + n * 240000).toISOString(),
      ),
    );
    const limited = await new DeviceRouter(b, {
      searchWindowSeconds: 600,
    }).plan(request(b));
    expect(limited.itineraries).toHaveLength(2);
  });
  it("returns previous arrival options for arrive-by, and expands frequencies", async () => {
    const b = network();
    b.patterns[0].trips[0].frequency = {
      end: 32400,
      headway: 240,
      exact: false,
    };
    const router = new DeviceRouter(b);
    const forward = await router.plan({
      ...request(b),
      time: "2026-10-22T08:00:00-05:00",
    });
    expect(forward.itineraries).toHaveLength(6);
    const reverse = await router.plan({
      ...request(b),
      time: "2026-10-22T08:45:00-05:00",
      arriveBy: true,
    });
    expect(reverse.itineraries).toHaveLength(6);
    expect(reverse.itineraries[0].departure).toBe("2026-10-22T13:24:00.000Z");
    expect(reverse.itineraries[1].departure).toBe("2026-10-22T13:20:00.000Z");
  });
  it("boards and alights at children even outside the normal coordinate access radius", async () => {
    const b = network();
    b.stops.push(
      { ...place("parent-a", -74.2), kind: "station" },
      { ...place("parent-c", -73.9), kind: "station" },
    );
    b.stops[0].parentStationId = "parent-a";
    b.stops[2].parentStationId = "parent-c";
    b.footpaths.push([], []);
    const r = await new DeviceRouter(b, { maxAccessMeters: 1 }).plan({
      ...request(b),
      from: b.stops[3],
      to: b.stops[4],
    });
    expect(r.itineraries).toHaveLength(1);
    expect(
      r.itineraries[0].legs.find((l) => l.mode === "transit")?.from.id,
    ).toBe("A");
  });
  it("uses the pattern terminal for a missing or route-code headsign and carries build time", async () => {
    const b = network();
    b.builtAt = "2026-10-22T00:00:00.000Z";
    for (const headsign of ["", "1", "South terminal"]) {
      b.patterns[0].headsign = headsign;
      const r = await new DeviceRouter(b).plan(request(b));
      expect(r.dataBuiltAt).toBe(b.builtAt);
      const leg = r.itineraries[0].legs.find((l) => l.mode === "transit");
      expect(leg?.mode === "transit" && leg.headsign).toBe(
        headsign === "South terminal" ? headsign : "C",
      );
    }
  });
});
describe("MOTIS", () => {
  it("maps color, agency, times, intermediate stops and polyline", () => {
    const b = network();
    const p = {
      name: "A",
      lat: b.stops[0].lat,
      lon: b.stops[0].lon,
      stopId: "tm_A",
    };
    const r = mapMotisPlan({
      itineraries: [
        {
          id: "i",
          startTime: "2026-10-22T13:00:00Z",
          endTime: "2026-10-22T13:10:00Z",
          transfers: 0,
          legs: [
            {
              mode: "BUS",
              from: p,
              to: p,
              startTime: "2026-10-22T13:00:00Z",
              endTime: "2026-10-22T13:10:00Z",
              agencyId: "tm_1",
              routeColor: "ff0000",
              routeShortName: "B10",
              headsign: "B10",
              tripTo: { ...p, name: "Terminal Sur" },
              intermediateStops: [p],
              legGeometry: {
                points: "_p~iF~ps|U_ulLnnqC_mqNvxq`@",
                precision: 5,
              },
            },
          ],
        },
      ],
    });
    const l = r.itineraries[0].legs[0];
    expect(l.mode === "transit" && l.route.system).toBe("troncal");
    expect(l.mode === "transit" && l.headsign).toBe("Terminal Sur");
    expect(l.geometry[0]).toEqual({ lat: 38.5, lon: -120.2 });
  });
  it("uses tagged IDs and forwards arriveBy", async () => {
    const b = network();
    const mockFetch = vi
      .fn<(_input: unknown, _init?: unknown) => Promise<Response>>()
      .mockResolvedValue(new Response(JSON.stringify({ itineraries: [] })));
    await new MotisClient("http://localhost:8080", { fetch: mockFetch }).plan({
      ...request(b),
      arriveBy: true,
    });
    const url = new URL(String(mockFetch.mock.calls[0][0]));
    expect(url.searchParams.get("fromPlace")).toBe("tm_A");
    expect(url.searchParams.get("arriveBy")).toBe("true");
  });
});
describe("local-first strategy and search", () => {
  it("emits a fast server immediately and retains local on error", async () => {
    const service = new RoutingService(
      { plan: async () => result("device") },
      { plan: async () => result("server") },
    );
    const updates = [];
    for await (const state of service.plan(request(network())))
      updates.push(state);
    expect(updates.map((s) => s.result?.source)).toEqual(["server"]);
    const failed = new RoutingService(
      { plan: async () => result("device") },
      {
        plan: async () => {
          throw new Error("offline");
        },
      },
    );
    const states = [];
    for await (const state of failed.plan(request(network())))
      states.push(state);
    expect(states.at(-1)?.result?.source).toBe("device");
    expect(states.at(-1)?.error?.message).toBe("offline");
  });
  it("times out engines that ignore cancellation", async () => {
    const service = new RoutingService(
      { plan: async () => result("device") },
      { plan: () => new Promise(() => {}) },
      { timeoutMs: 10 },
    );
    const states = [];
    for await (const state of service.plan(request(network())))
      states.push(state);
    expect(states.at(-1)?.isUpgrading).toBe(false);
    expect(states.at(-1)?.error).toBeDefined();
  });
  it("aborts obsolete requests and never emits late upgrades", async () => {
    const controller = new AbortController();
    const service = new RoutingService(
      { plan: async () => result("device") },
      { plan: () => new Promise(() => {}) },
    );
    const iterator = service.plan(request(network()), controller.signal);
    await iterator.next();
    controller.abort();
    await expect(iterator.next()).rejects.toThrow();
  });
  it("normalizes accents and supports one-character typos", async () => {
    const search = new OfflinePlaceSearch([
      { ...place("a", 0), name: "Estación Héroes" },
    ]);
    expect(await search.search("estacion hero")).toHaveLength(1);
    expect(await search.search("heross")).toHaveLength(1);
  });
  it("merges remote results by ID and can work offline", async () => {
    const p = place("a", 0),
      other = place("b", 1);
    const service = new SearchService(
      { search: async () => [p] },
      { search: async () => [p, other] },
    );
    const states = [];
    for await (const state of service.search("a")) states.push(state);
    expect(states[0].places).toEqual([p, other]);
    expect(states[1].places).toEqual([p, other]);
  });
});

it("cooperative decode matches synchronous decode and accepts revision 2", async () => {
  const b = network();
  b.stops[0].parentStationId = "parent";
  b.stops[0].code = "2-10";
  b.builtAt = "2026-10-22T00:00:00.000Z";
  const bytes = encodeBundle(b);
  expect(new DataView(bytes.buffer).getUint32(4, true)).toBe(3);
  expect(await decodeBundleAsync(bytes)).toEqual(b);
  new DataView(bytes.buffer).setUint32(4, 2, true);
  expect(await decodeBundleAsync(bytes)).toEqual(decodeBundle(bytes));
  await expect(decodeBundleAsync(bytes.slice(0, -1))).rejects.toThrow();
  const controller = new AbortController();
  controller.abort(new Error("cancelled"));
  await expect(
    decodeBundleAsync(bytes, { signal: controller.signal }),
  ).rejects.toThrow("cancelled");
});
it("keeps real direction headsigns but replaces route codes and blanks", () => {
  expect(directionHeadsign(" G12 ", "G12", "Portal Sur")).toBe("Portal Sur");
  expect(directionHeadsign("", "G12", "Portal Sur")).toBe("Portal Sur");
  expect(directionHeadsign("Museo Nacional", "G12", "Portal Sur")).toBe(
    "Museo Nacional",
  );
});

it("cooperative decode yields to the host and observes cancellation between chunks", async () => {
  const bytes = encodeBundle(network());
  let clock = 0;
  const now = vi
    .spyOn(performance, "now")
    .mockImplementation(() => (clock += 10));
  const yieldControl = vi.fn(async () => {});
  try {
    const onTiming = vi.fn();
    expect(await decodeBundleAsync(bytes, { yieldControl, onTiming })).toEqual(
      network(),
    );
    expect(yieldControl).toHaveBeenCalled();
    expect(onTiming).toHaveBeenCalledWith(
      expect.objectContaining({ yields: expect.any(Number) }),
    );
    const controller = new AbortController();
    await expect(
      decodeBundleAsync(bytes, {
        signal: controller.signal,
        yieldControl: async () => {
          controller.abort(new Error("cancel decode"));
        },
      }),
    ).rejects.toThrow("cancel decode");
  } finally {
    now.mockRestore();
  }
});

it("keeps a specifically selected child platform subject to normal access distances", async () => {
  const b = network();
  b.stops.push(
    { ...place("parent", -74.2), kind: "station" },
    { ...place("far-platform", -74.3), parentStationId: "parent" },
  );
  b.stops[0].parentStationId = "parent";
  b.footpaths.push([], []);
  const router = new DeviceRouter(b, { maxAccessMeters: 1 });
  expect(
    (await router.plan({ ...request(b), from: b.stops[3] })).itineraries,
  ).toHaveLength(1);
  expect(
    (await router.plan({ ...request(b), from: b.stops[4] })).itineraries,
  ).toHaveLength(0);
});
