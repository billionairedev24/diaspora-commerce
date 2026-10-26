import {
  type RunCutoffPolicy,
  type RunDropMode,
  type RunScheduleInput,
  type StaffRunDetail,
  type StaffRunLocation
} from '@afrotransact/api-client';

/* The model of the run screens: times in the location's time zone, the form as people type it, and the body it sends. */

const pad = (n: number): string => String(n).padStart(2, '0');

/** A zone Intl knows, else UTC (a location's time zone comes from delivery-runs; never trust it blindly). */
export const zoneOf = (tz: string | undefined | null): string => {
  if (!tz) return 'UTC';
  try {
    new Intl.DateTimeFormat('en', { timeZone: tz });
    return tz;
  } catch {
    return 'UTC';
  }
};

/** The wall-clock parts of an instant in a time zone. */
function partsIn(ms: number, tz: string): Record<string, number> {
  const f = new Intl.DateTimeFormat('en-US', { timeZone: tz, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' });
  return Object.fromEntries(f.formatToParts(new Date(ms)).filter((p) => p.type !== 'literal').map((p) => [p.type, Number(p.value)]));
}

/** How far the zone's wall clock is ahead of UTC at an instant. */
function offsetMs(ms: number, tz: string): number {
  const p = partsIn(ms, tz);
  return Date.UTC(p.year ?? 1970, (p.month ?? 1) - 1, p.day ?? 1, p.hour ?? 0, p.minute ?? 0, p.second ?? 0) - ms;
}

/**
 * The instant for a wall-clock time typed in a time zone ("2026-11-05T20:00" in America/Chicago), as ISO 8601 in UTC;
 * null when it is not a date and time. A time the clocks skip (spring forward) lands just after the gap.
 */
export function zonedInstant(local: string, tz: string): string | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(local);
  if (!m) return null;
  const wall = Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]), Number(m[4]), Number(m[5]));
  const first = wall - offsetMs(wall, tz);
  const second = wall - offsetMs(first, tz);
  const ms = Number.isNaN(second) ? Number.NaN : second;
  return Number.isNaN(ms) ? null : new Date(ms).toISOString();
}

/** An instant as the wall-clock text a datetime-local input holds, in a time zone ("2026-11-05T20:00"); "" for none. */
export function toZonedLocal(iso: string | null | undefined, tz: string): string {
  if (!iso) return '';
  const ms = Date.parse(iso);
  if (Number.isNaN(ms)) return '';
  const p = partsIn(ms, tz);
  return `${String(p.year).padStart(4, '0')}-${pad(p.month ?? 1)}-${pad(p.day ?? 1)}T${pad(p.hour ?? 0)}:${pad(p.minute ?? 0)}`;
}

/** The form as people type it. Amounts are dollars text; times are wall-clock times in the location's zone. */
export interface RunForm {
  locationId: string;
  windowStart: string;
  windowEnd: string;
  cutoffAt: string;
  opensAt: string;
  sellerPickupAt: string;
  minOrders: string;
  capacityOrders: string;
  feeMode: 'RULE' | 'CUSTOM';
  fee: string;
  dropMode: RunDropMode;
  cutoffPolicy: RunCutoffPolicy;
  extendDays: string;
  notes: string;
}

/** Days from now a new run's delivery window starts, and how long before it orders close. */
const DELIVERY_IN_DAYS = 7;
const CLOSE_BEFORE_DAYS = 3;

/** A new run's form: the location's defaults and a delivery a week away, orders closing three days before it. */
export function newRunForm(location: StaffRunLocation | undefined, nowMs: number): RunForm {
  const tz = zoneOf(location?.timeZone);
  const day = (offsetDays: number, hh: string): string => {
    const local = toZonedLocal(new Date(nowMs + offsetDays * 86_400_000).toISOString(), tz).slice(0, 10);
    return `${local}T${hh}`;
  };
  return {
    locationId: location?.id ?? '',
    windowStart: day(DELIVERY_IN_DAYS, '10:00'),
    windowEnd: day(DELIVERY_IN_DAYS, '14:00'),
    cutoffAt: day(DELIVERY_IN_DAYS - CLOSE_BEFORE_DAYS, '20:00'),
    opensAt: '',
    sellerPickupAt: '',
    minOrders: location?.defaultMinOrders ? String(location.defaultMinOrders) : '',
    capacityOrders: location?.defaultCapacityOrders ? String(location.defaultCapacityOrders) : '',
    feeMode: 'RULE',
    fee: '',
    dropMode: location?.dropMode ?? 'DOORSTEP',
    cutoffPolicy: location?.defaultCutoffPolicy ?? 'EXTEND',
    extendDays: String(location?.defaultExtendDays ?? 7),
    notes: ''
  };
}

/** The form of a run being edited (times in the location's zone). */
export function runFormOf(run: StaffRunDetail, location: StaffRunLocation | undefined): RunForm {
  const tz = zoneOf(run.location?.timeZone ?? location?.timeZone);
  return {
    locationId: run.locationId,
    windowStart: toZonedLocal(run.windowStart, tz),
    windowEnd: toZonedLocal(run.windowEnd, tz),
    cutoffAt: toZonedLocal(run.cutoffAt, tz),
    opensAt: toZonedLocal(run.opensAt, tz),
    sellerPickupAt: toZonedLocal(run.sellerPickupAt, tz),
    minOrders: String(run.minOrders),
    capacityOrders: run.capacityOrders ? String(run.capacityOrders) : '',
    // A saved run holds a fee; showing it as a custom amount keeps what customers were quoted.
    feeMode: 'CUSTOM',
    fee: `${Math.floor(run.feeMinor / 100)}.${String(run.feeMinor % 100).padStart(2, '0')}`,
    dropMode: (run.dropMode as RunDropMode | undefined) ?? 'DOORSTEP',
    cutoffPolicy: run.cutoffPolicy ?? 'EXTEND',
    extendDays: String(run.extendDays ?? 7),
    notes: run.notes ?? ''
  };
}

/** Whether any field differs from what the form opened with (Save stays off until something changed). */
export const runFormChanged = (form: RunForm, initial: RunForm): boolean => (Object.keys(initial) as (keyof RunForm)[]).some((k) => form[k] !== initial[k]);

/** An amount typed by people ("7.99", "$7") in minor units; null when blank, NaN when not an amount. */
export function amountMinor(text: string): number | null {
  const t = text.trim();
  if (!t) return null;
  const m = /^\$?\s*(\d{1,3})(?:\.(\d{0,2}))?$/.exec(t);
  if (!m) return Number.NaN;
  return Number(m[1]) * 100 + Number((m[2] ?? '').padEnd(2, '0'));
}

const wholeOrNull = (text: string): number | null => {
  const t = text.trim();
  if (!t) return null;
  return /^\d{1,6}$/.test(t) ? Number(t) : Number.NaN;
};

/** What the form says about its numbers before it is sent (NaN means "not a number"). */
export interface RunFormNumbers {
  minOrders: number | null;
  capacityOrders: number | null;
  feeMinor: number | null;
  extendDays: number | null;
}

export function formNumbers(form: RunForm): RunFormNumbers {
  return {
    minOrders: wholeOrNull(form.minOrders),
    capacityOrders: wholeOrNull(form.capacityOrders),
    feeMinor: form.feeMode === 'CUSTOM' ? amountMinor(form.fee) : null,
    extendDays: wholeOrNull(form.extendDays)
  };
}

/** The body to send, with times converted from the location's zone to instants. */
export function runInputOf(form: RunForm, tz: string): RunScheduleInput {
  const n = formNumbers(form);
  const at = (local: string): string | null => (local ? zonedInstant(local, tz) : null);
  return {
    locationId: form.locationId,
    windowStart: at(form.windowStart) ?? '',
    windowEnd: at(form.windowEnd) ?? '',
    cutoffAt: at(form.cutoffAt) ?? '',
    opensAt: at(form.opensAt),
    sellerPickupAt: at(form.sellerPickupAt),
    feeMinor: n.feeMinor,
    minOrders: n.minOrders,
    capacityOrders: n.capacityOrders,
    dropMode: form.dropMode,
    cutoffPolicy: form.cutoffPolicy,
    extendDays: n.extendDays,
    notes: form.notes.trim() ? form.notes.trim() : ''
  };
}

/** A short description of a location's area for the form and the run: a radius around the town, or a drawn zone. */
export const areaKind = (location: StaffRunLocation | undefined | null): 'RADIUS' | 'POLYGON' | null => location?.area?.kind ?? null;
