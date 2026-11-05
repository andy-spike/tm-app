import type { Place, TripResult } from '@tm/core';
import { createContext, use, useState, type ReactNode } from 'react';

export type Field = 'from' | 'to';

interface TripForm {
  from?: Place;
  to?: Place;
  /** undefined means "leave now". */
  time?: Date;
  arriveBy: boolean;
  /** Last results, so the detail screen can show an itinerary without re-planning. */
  result?: TripResult;
}

interface TripFormContext extends TripForm {
  setPlace(field: Field, place: Place): void;
  swap(): void;
  setTime(time: Date | undefined, arriveBy: boolean): void;
  setResult(result: TripResult | undefined): void;
}

const Context = createContext<TripFormContext | null>(null);

export function TripFormProvider({ children }: { children: ReactNode }) {
  const [form, setForm] = useState<TripForm>({ arriveBy: false });
  const value: TripFormContext = {
    ...form,
    setPlace: (field, place) => setForm((f) => ({ ...f, [field]: place })),
    swap: () => setForm((f) => ({ ...f, from: f.to, to: f.from })),
    setTime: (time, arriveBy) => setForm((f) => ({ ...f, time, arriveBy })),
    setResult: (result) => setForm((f) => ({ ...f, result })),
  };
  return <Context value={value}>{children}</Context>;
}

export function useTripForm(): TripFormContext {
  const ctx = use(Context);
  if (!ctx) throw new Error('useTripForm must be used inside TripFormProvider');
  return ctx;
}
