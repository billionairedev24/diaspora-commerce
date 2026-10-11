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
  if (Number.isNaN(d.getTime())) return iso;
  return dateText(d, { month: 'short', day: 'numeric', year: 'numeric' });
}

export function dateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return dateText(d, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
}

export function percent(rate: number): string {
  return `${(rate * 100).toLocaleString(numTag(), { maximumFractionDigits: 3 })} %`;
}

/** "AWAITING_FINAL_APPROVAL" → "Awaiting final approval". */
export function humanize(s: string): string {
  const t = s.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/[_-]+/g, ' ').toLowerCase().trim();
  return t.charAt(0).toUpperCase() + t.slice(1);
}

/** Dollars typed by people ("24.99") → minor units; NaN when not a price. */
export function toMinor(v: string): number {
  const n = Number(v.replace(/[$,\s]/g, ''));
  return Number.isFinite(n) && v.trim() !== '' ? Math.round(n * 100) : Number.NaN;
}

/** ISO date-time → the value of a datetime-local input (local time). */
export function toLocalInput(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** A fresh Idempotency-Key for one user action. */
export function idempotencyKey(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID() : `k-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}
