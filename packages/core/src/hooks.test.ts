import { afterEach, beforeEach, expect, it, vi } from "vitest";
import type { SearchState, RoutingState, TripRequest } from "./index";
// A minimal hook host lets the mobile data hooks run without loading the native renderer.
// State, dependency changes, cleanup, and debounce timers are exercised through the real hooks.
const host = vi.hoisted(() => {
  let index = 0;
  const slots: unknown[] = [],
    effects: (() => void)[] = [];
  return {
    begin: () => {
      index = 0;
    },
    flush: () => {
      for (const effect of effects.splice(0)) effect();
    },
    reset: () => {
      index = 0;
      slots.length = 0;
      effects.length = 0;
    },
    useState: <T>(initial: T | (() => T)) => {
      const slot = index++;
      if (!(slot in slots))
        slots[slot] =
          typeof initial === "function" ? (initial as () => T)() : initial;
      return [
        slots[slot] as T,
        (value: T | ((old: T) => T)) => {
          slots[slot] =
            typeof value === "function"
              ? (value as (old: T) => T)(slots[slot] as T)
              : value;
        },
      ];
    },
    useRef: <T>(initial: T) => {
      const slot = index++;
      slots[slot] ??= { current: initial };
      return slots[slot] as { current: T };
    },
    useCallback: <T>(fn: T, deps: unknown[]) => {
      const slot = index++;
      const old = slots[slot] as { value: T; deps: unknown[] } | undefined;
      if (old && old.deps.every((dep, i) => Object.is(dep, deps[i])))
        return old.value;
      slots[slot] = { value: fn, deps };
      return fn;
    },
    useEffect: (effect: () => void | (() => void), deps: unknown[]) => {
      const slot = index++;
      const old = slots[slot] as
        | { deps: unknown[]; cleanup?: () => void }
        | undefined;
      if (old && old.deps.every((dep, i) => Object.is(dep, deps[i]))) return;
      effects.push(() => {
        old?.cleanup?.();
        slots[slot] = { deps, cleanup: effect() };
      });
    },
  };
});
const engines = vi.hoisted(() => ({ search: vi.fn(), plan: vi.fn() }));
vi.mock("../../../apps/mobile/node_modules/react", () => ({
  ...host,
  useSyncExternalStore: vi.fn(),
}));
vi.mock("../../../apps/mobile/src/data/services", () => ({
  startDataServices: vi.fn(),
  searchService: { search: engines.search },
  routingService: { plan: engines.plan },
}));
vi.mock("../../../apps/mobile/src/data/sync", () => ({ bundleSync: {} }));
import {
  usePlaceSearch,
  useTripPlanner,
} from "../../../apps/mobile/src/data/hooks";
function renderSearch(text: string) {
  host.begin();
  return usePlaceSearch(text);
}
function renderPlanner(request?: TripRequest) {
  host.begin();
  return useTripPlanner(request);
}
beforeEach(() => {
  host.reset();
  engines.search.mockReset();
  engines.plan.mockReset();
  vi.useFakeTimers();
});
afterEach(() => {
  vi.useRealTimers();
});
it("shows loading throughout debounce, clears it on first emission, and rejects stale search emissions", async () => {
  let finish!: (value: SearchState) => void;
  engines.search.mockImplementation(async function* () {
    yield await new Promise<SearchState>((resolve) => {
      finish = resolve;
    });
  });
  expect(renderSearch("portal").isLoading).toBe(true);
  host.flush();
  await vi.advanceTimersByTimeAsync(249);
  expect(engines.search).not.toHaveBeenCalled();
  expect(renderSearch("portal").isLoading).toBe(true);
  await vi.advanceTimersByTimeAsync(1);
  finish({ places: [], isLoading: false, isUpgrading: true });
  await vi.advanceTimersByTimeAsync(0);
  expect(renderSearch("portal")).toMatchObject({
    isLoading: false,
    isUpgrading: true,
  });
  expect(renderSearch("universidad").isLoading).toBe(true);
  host.flush();
  expect(renderSearch(" ").isLoading).toBe(false);
  host.flush();
  await vi.advanceTimersByTimeAsync(250);
  expect(engines.search).toHaveBeenCalledTimes(1);
});
it("stops loading on a search error", async () => {
  engines.search.mockImplementation(async function* () {
    throw new Error("offline");
  });
  renderSearch("portal");
  host.flush();
  await vi.advanceTimersByTimeAsync(250);
  expect(renderSearch("portal")).toMatchObject({
    isLoading: false,
    error: expect.any(Error),
  });
});
const request: TripRequest = {
  from: { id: "a", name: "A", kind: "station", lat: 4.6, lon: -74.1 },
  to: { id: "b", name: "B", kind: "station", lat: 4.7, lon: -74.1 },
  time: "2026-10-22T16:13:00-05:00",
};
it("shows automatic and imperative trip loading until first emission and clears on cancel", async () => {
  let finish!: (value: RoutingState) => void;
  engines.plan.mockImplementation(async function* () {
    yield await new Promise<RoutingState>((resolve) => {
      finish = resolve;
    });
  });
  expect(renderPlanner(request).isLoading).toBe(true);
  host.flush();
  await vi.advanceTimersByTimeAsync(0);
  finish({ isLoading: false, isUpgrading: true });
  await vi.advanceTimersByTimeAsync(0);
  expect(renderPlanner(request)).toMatchObject({
    isLoading: false,
    isUpgrading: true,
  });
  const changed = { ...request, time: "2026-10-22T17:00:00-05:00" };
  expect(renderPlanner(changed).isLoading).toBe(true);
  host.flush();
  await vi.advanceTimersByTimeAsync(0);
  renderPlanner(changed).cancel();
  expect(renderPlanner(changed)).toMatchObject({
    isLoading: false,
    isUpgrading: false,
  });
});
it("stops trip loading on error", async () => {
  engines.plan.mockImplementation(async function* () {
    throw new Error("no data");
  });
  const planner = renderPlanner();
  expect(planner.isLoading).toBe(false);
  await planner.plan(request);
  expect(renderPlanner()).toMatchObject({
    isLoading: false,
    error: expect.any(Error),
  });
});

it("ignores a late emission from a search replaced during cold loading", async () => {
  let finish!: (value: SearchState) => void;
  engines.search.mockImplementation(async function* () {
    yield await new Promise<SearchState>((resolve) => {
      finish = resolve;
    });
  });
  renderSearch("old");
  host.flush();
  await vi.advanceTimersByTimeAsync(250);
  renderSearch("new");
  host.flush();
  finish({ places: [], isLoading: false, isUpgrading: false });
  await vi.advanceTimersByTimeAsync(0);
  expect(renderSearch("new").isLoading).toBe(true);
});

it("cancels an automatic trip before its deferred dispatch", async () => {
  renderPlanner(request);
  host.flush();
  renderPlanner(request).cancel();
  await vi.advanceTimersByTimeAsync(0);
  expect(engines.plan).not.toHaveBeenCalled();
  expect(renderPlanner(request).isLoading).toBe(false);
});
