// Bogotá is UTC-5 all year (no DST), so we format with a fixed offset instead of
// relying on Intl time zone support in Hermes.
const OFFSET_MS = -5 * 60 * 60 * 1000;

const local = (iso: string) => new Date(new Date(iso).getTime() + OFFSET_MS);
const pad = (n: number) => String(n).padStart(2, '0');

export function clock(iso: string): string {
  const d = local(iso);
  return `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`;
}

export function minutesBetween(a: string, b: string): number {
  return Math.round((new Date(b).getTime() - new Date(a).getTime()) / 60000);
}

export function duration(a: string, b: string): string {
  const m = minutesBetween(a, b);
  if (m < 1) return '<1 min';
  if (m < 60) return `${m} min`;
  const h = Math.floor(m / 60);
  return m % 60 ? `${h} h ${m % 60} min` : `${h} h`;
}

export function distance(meters: number): string {
  return meters < 1000 ? `${Math.round(meters / 10) * 10} m` : `${(meters / 1000).toFixed(1)} km`;
}

/** ISO timestamp in Bogotá local time, e.g. 2026-10-22T08:00:00-05:00. */
export function bogotaIso(date: Date): string {
  const d = new Date(date.getTime() + OFFSET_MS);
  return (
    `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}` +
    `T${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:00-05:00`
  );
}

export function dayLabel(date: Date, now = new Date()): string {
  const day = (x: Date) => local(x.toISOString()).toISOString().slice(0, 10);
  if (day(date) === day(now)) return 'Hoy';
  if (day(date) === day(new Date(now.getTime() + 86400000))) return 'Mañana';
  const d = local(date.toISOString());
  return `${pad(d.getUTCDate())}/${pad(d.getUTCMonth() + 1)}`;
}

/** Route colors may come with or without '#'. Falls back to the brand gray. */
export function hex(color: string | undefined, fallback = '#5B6270'): string {
  if (!color) return fallback;
  const c = color.startsWith('#') ? color : `#${color}`;
  return /^#[0-9a-fA-F]{6}$/.test(c) ? c : fallback;
}

/** Black or white text, whichever reads better on the given background. */
export function onColor(bg: string): string {
  const n = parseInt(bg.slice(1), 16);
  const [r, g, b] = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((v) => {
    const s = v / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b > 0.4 ? '#16181D' : '#FFFFFF';
}

/** "22/10/2026" from an ISO timestamp, in Bogotá time. */
export function shortDate(iso: string, withYear = true): string {
  const d = local(iso);
  return `${pad(d.getUTCDate())}/${pad(d.getUTCMonth() + 1)}${withYear ? `/${d.getUTCFullYear()}` : ''}`;
}
