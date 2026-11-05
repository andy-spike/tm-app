import {
  useCallback,
  useEffect,
  useRef,
  useState,
  useSyncExternalStore,
} from "react";
import type { RoutingState, SearchState, TripRequest } from "@tm/core";
import { routingService, searchService, startDataServices } from "./services";
import { bundleSync, type BundleStatus } from "./sync";
/** Pass a request to run automatically, or call plan(request). Cancels stale requests. */
export function useTripPlanner(request?: TripRequest): RoutingState & {
  source?: "server" | "device";
  plan: (request: TripRequest) => Promise<void>;
  cancel: () => void;
} {
  const key = request ? JSON.stringify(request) : undefined;
  const [snapshot, setSnapshot] = useState<{
    key?: string;
    state: RoutingState;
  }>(() => ({
    key,
    state: { isLoading: !!request, isUpgrading: false },
  }));
  const current = useRef<AbortController | undefined>(undefined);
  const scheduled = useRef<ReturnType<typeof setTimeout> | undefined>(
    undefined,
  );
  const cancel = useCallback(() => {
    clearTimeout(scheduled.current);
    current.current?.abort();
    setSnapshot((s) => ({
      ...s,
      state: { ...s.state, isLoading: false, isUpgrading: false },
    }));
  }, []);
  const plan = useCallback(async (value: TripRequest) => {
    startDataServices();
    current.current?.abort();
    const controller = new AbortController();
    current.current = controller;
    const setState = (state: RoutingState) =>
      setSnapshot({ key: JSON.stringify(value), state });
    setState({ isLoading: true, isUpgrading: false });
    try {
      for await (const next of routingService.plan(value, controller.signal))
        if (!controller.signal.aborted) setState(next);
    } catch (e) {
      if (!controller.signal.aborted)
        setState({
          isLoading: false,
          isUpgrading: false,
          error: e instanceof Error ? e : new Error(String(e)),
        });
    }
  }, []);
  useEffect(() => {
    scheduled.current = setTimeout(() => {
      if (key) void plan(JSON.parse(key) as TripRequest);
    }, 0);
    return () => {
      clearTimeout(scheduled.current);
      current.current?.abort();
    };
  }, [key, plan]);
  const state: RoutingState =
    key && snapshot.key !== key
      ? { isLoading: true, isUpgrading: false }
      : snapshot.state;
  return { ...state, source: state.result?.source, plan, cancel };
}
/** Debounced offline stop matches first, then merged server addresses/POIs. */
export function usePlaceSearch(text: string, debounceMs = 250): SearchState {
  const pending = (query: string): SearchState => ({
    places: [],
    isLoading: !!query.trim(),
    isUpgrading: false,
  });
  const [snapshot, setSnapshot] = useState<{
    text: string;
    state: SearchState;
  }>(() => ({ text, state: pending(text) }));
  useEffect(() => {
    startDataServices();
    const controller = new AbortController();
    const setState = (state: SearchState) => setSnapshot({ text, state });
    setState(pending(text));
    if (!text.trim()) return () => controller.abort();
    const timer = setTimeout(() => {
      void (async () => {
        try {
          for await (const next of searchService.search(
            text,
            controller.signal,
          ))
            if (!controller.signal.aborted) setState(next);
        } catch (e) {
          if (!controller.signal.aborted)
            setState({
              places: [],
              isLoading: false,
              isUpgrading: false,
              error: e instanceof Error ? e : new Error(String(e)),
            });
        }
      })();
    }, debounceMs);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [text, debounceMs]);
  // Derive loading during the render accepting a new query, including before its effect/debounce.
  return snapshot.text === text ? snapshot.state : pending(text);
}
/** Status stays available on sync failure. check({allowMetered:true}) opts into a cellular download. */
export function useBundleStatus(): BundleStatus & {
  check: (options?: { allowMetered?: boolean }) => Promise<void>;
} {
  useEffect(() => {
    startDataServices();
  }, []);
  return {
    ...useSyncExternalStore(
      bundleSync.subscribe,
      bundleSync.getSnapshot,
      bundleSync.getSnapshot,
    ),
    check: (options) => bundleSync.check(options),
  };
}
