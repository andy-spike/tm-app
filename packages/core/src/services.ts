import { mergePlaces } from "./search";
import type {
  Place,
  PlaceEngine,
  RoutingEngine,
  TripRequest,
  TripResult,
} from "./domain";
export interface RoutingState {
  result?: TripResult;
  isLoading: boolean;
  isUpgrading: boolean;
  error?: Error;
}
export interface SearchState {
  places: Place[];
  isLoading: boolean;
  isUpgrading: boolean;
  error?: Error;
}
export interface StrategyOptions {
  timeoutMs?: number;
  online?: () => boolean;
}
const errorOf = (e: unknown) => (e instanceof Error ? e : new Error(String(e)));
type Answer<T> = { value: T } | { error: Error };
/** Races even engines that ignore AbortSignal and releases its listener on settlement. */
function settleWithin<T>(
  promise: Promise<T>,
  signal: AbortSignal,
): Promise<Answer<T>> {
  return new Promise((resolve) => {
    const finish = (answer: Answer<T>) => {
      signal.removeEventListener("abort", abort);
      resolve(answer);
    };
    const abort = () => finish({ error: errorOf(signal.reason ?? "Aborted") });
    signal.addEventListener("abort", abort, { once: true });
    if (signal.aborted) abort();
    promise.then(
      (value) => finish({ value }),
      (error) => finish({ error: errorOf(error) }),
    );
  });
}
/** Start both engines before awaiting either; only the remote engine has a deadline. */
async function* answers<T>(
  local: (signal: AbortSignal) => Promise<T>,
  remote: (signal: AbortSignal) => Promise<T>,
  online: boolean,
  timeoutMs: number,
  signal?: AbortSignal,
): AsyncGenerator<{
  source: "device" | "server";
  answer: Answer<T>;
  pending: boolean;
}> {
  signal?.throwIfAborted();
  const device = new AbortController(),
    server = new AbortController();
  const abort = () => {
    device.abort(signal?.reason);
    server.abort(signal?.reason);
  };
  signal?.addEventListener("abort", abort, { once: true });
  const timer = online
    ? setTimeout(() => server.abort(new Error("Upgrade timeout")), timeoutMs)
    : undefined;
  const pending = new Map<
    "device" | "server",
    Promise<{ source: "device" | "server"; answer: Answer<T> }>
  >();
  // Queue the network call first so synchronous device computation cannot delay dispatch.
  if (online)
    pending.set(
      "server",
      settleWithin(
        Promise.resolve().then(() => remote(server.signal)),
        server.signal,
      ).then((answer) => ({ source: "server", answer })),
    );
  pending.set(
    "device",
    settleWithin(
      Promise.resolve().then(() => local(device.signal)),
      device.signal,
    ).then((answer) => ({ source: "device", answer })),
  );
  try {
    while (pending.size) {
      const event = await Promise.race(pending.values());
      signal?.throwIfAborted();
      pending.delete(event.source);
      yield { ...event, pending: pending.size > 0 };
    }
  } finally {
    clearTimeout(timer);
    abort();
    signal?.removeEventListener("abort", abort);
  }
}
/** Emit whichever engine finishes first; a nonempty server route is authoritative. */
export class RoutingService {
  constructor(
    private device: RoutingEngine,
    private server: RoutingEngine,
    private options: StrategyOptions = {},
  ) {}
  async *plan(
    request: TripRequest,
    signal?: AbortSignal,
  ): AsyncGenerator<RoutingState> {
    let result: TripResult | undefined, error: Error | undefined;
    for await (const event of answers(
      (s) => this.device.plan(request, s),
      (s) => this.server.plan(request, s),
      this.options.online?.() ?? true,
      this.options.timeoutMs ?? 8000,
      signal,
    )) {
      if ("error" in event.answer) error = event.answer.error;
      else if (event.source === "server") {
        if (event.answer.value.itineraries.length) {
          yield {
            result: event.answer.value,
            isLoading: false,
            isUpgrading: false,
          };
          return;
        }
        error = new Error("Server returned no itineraries");
      } else result = event.answer.value;
      yield { result, error, isLoading: false, isUpgrading: event.pending };
    }
  }
}
export class SearchService {
  constructor(
    private device: PlaceEngine,
    private server: PlaceEngine,
    private options: StrategyOptions = {},
  ) {}
  async *search(
    text: string,
    signal?: AbortSignal,
  ): AsyncGenerator<SearchState> {
    let local: Place[] = [],
      remote: Place[] = [],
      error: Error | undefined;
    for await (const event of answers(
      (s) => this.device.search(text, s),
      (s) => this.server.search(text, s),
      this.options.online?.() ?? true,
      this.options.timeoutMs ?? 5000,
      signal,
    )) {
      if ("error" in event.answer) error = event.answer.error;
      else if (event.source === "device") local = event.answer.value;
      else remote = event.answer.value;
      yield {
        places: mergePlaces(local, remote, text),
        error,
        isLoading: false,
        isUpgrading: event.pending,
      };
    }
  }
}
