/* Display formatting (amounts are minor units). Formats follow the back office's active language: Providers sets the
   Intl tag from the locale (en-US, fr-FR, es-US, ar, yo-NG) before any page renders. Back-office data loads in the
   browser after the session check, so these run client side. */

import { formatDateParts, type FormatPreferences, numberTag, validZone } from '@afrotransact/i18n';

let tag = 'en-US';
let prefs: FormatPreferences = {};

/** Called by Providers with the active locale's Intl tag. */
export function setFormatLocale(next: string): void {
  tag = next;
}

/** My account › Preferences: time zone, date order and number style (applied by the shell once they load). */
export function setFormatPreferences(next: FormatPreferences): void {
  prefs = next;
}

const numTag = () => numberTag(prefs.numberFormat, tag);
const zoned = (o: Intl.DateTimeFormatOptions): Intl.DateTimeFormatOptions => {
  const tz = validZone(prefs.timeZone);
  return tz ? { ...o, timeZone: tz } : o;
};
const dateText = (d: Date, o: Intl.DateTimeFormatOptions) => formatDateParts(d, tag, zoned(o), prefs.dateFormat ?? 'AUTO');

export function money(minor: number, currency = 'USD'): string {
  return new Intl.NumberFormat(numTag(), { style: 'currency', currency }).format(minor / 100);
}

export function dollars(major: number): string {
  return new Intl.NumberFormat(numTag(), { style: 'currency', currency: 'USD', maximumFractionDigits: major % 1 ? 2 : 0 }).format(major);
}

export function shortDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso.length === 10 ? `${iso}T12:00:00` : iso);
  return dateText(d, { month: 'short', day: 'numeric', year: 'numeric' });
}

export function dateTime(iso: string): string {
  return dateText(new Date(iso), { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
}

export function time(iso: string): string {
  return new Date(iso).toLocaleTimeString(tag, zoned({ hour: 'numeric', minute: '2-digit' }));
}

export function minutesLabel(min: number): string {
  const h = Math.floor(min / 60);
  const m = min % 60;
  return h && m ? `${h} h ${m} min` : h ? `${h} h` : `${m} min`;
}

/** ISO date (YYYY-MM-DD) in local time. */
export function isoDay(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** Dollars typed by people ("24.99") → minor units; NaN when not a price. */
export function toMinor(v: string): number {
  const n = Number(v.replace(/[$,\s]/g, ''));
  return Number.isFinite(n) && v.trim() !== '' ? Math.round(n * 100) : Number.NaN;
}

/** A fresh Idempotency-Key for one user action. */
export function idempotencyKey(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID() : `k-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}
