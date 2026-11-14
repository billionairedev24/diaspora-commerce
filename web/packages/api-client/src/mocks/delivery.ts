/* Delivery promises ported from the prototype (miles, offerEta, fmtDay, nearestFc, shipOpts).
   Production: the promise service computes these per ZIP from carrier transit tables. */
import { type DeliveryOption } from '../schemas/cart';
import { type DeliveryPromise } from '../schemas/catalog';
import { type DeliveryLocation } from '../schemas/location';
import { ADDRESSES, FULFILMENT_CENTRES, GEO, ZIP_PREFIXES, ZIP_PREFIXES_LONG } from './fixtures/geo';
import { deliveryFee,getShippingSettings } from './shipping-settings';

export const DEFAULT_ZIP = '77042';
export const SELLER_SHIPPING_MINOR = 599;
export const FREE_SHIPPING_THRESHOLD_MINOR = 4900;

export interface ShipFrom {
  fulfilment: 'AFROTRANSACT' | 'SELLER' | 'AFROTRANSACT_DELIVERY';
  city: string;
  /** The seller's configured handling days (seller-shipped offers); null/undefined = not configured. */
  handling?: number | null;
}

/** AfroTransact Delivery from the seller's store (S255): payments' seed pricing (V9) and service radius. */
export type PlatformDeliveryPricingMode = 'FLAT' | 'PER_WEIGHT' | 'DISTANCE';

export interface PlatformDeliveryConfig {
  mode: PlatformDeliveryPricingMode;
  flatFeeMinor: number;
  baseMinor: number;
  perLbMinor: number;
  perMileMinor: number;
  minMinor: number;
  maxMinor: number;
  maxRadiusMiles: number;
  detour: number;
}

export const platformDeliveryConfig: PlatformDeliveryConfig = {
  mode: 'DISTANCE',
  flatFeeMinor: 599,
  baseMinor: 499,
  perLbMinor: 50,
  perMileMinor: 85,
  minMinor: 599,
  maxMinor: 2499,
  maxRadiusMiles: 25,
  detour: 1.3
};

export function setPlatformDeliveryPricing(config: Partial<PlatformDeliveryConfig>) {
  Object.assign(platformDeliveryConfig, config);
}

export const LOCAL_DELIVERY = platformDeliveryConfig;

export interface LocalQuote {
  available: boolean;
  roadMiles: number;
  durationMinutes: number;
  chargeMinor: number;
  pricingMode?: PlatformDeliveryPricingMode;
}

/** Road distance store → shopper (geodesic × detour, like geo's last resort) and the customer charge. */
export function localDeliveryQuote(storeCity: string, metro: string, weightLbs = 1.0): LocalQuote {
  const straight = miles(geoOf(storeCity), geoOf(metro));
  const roadMiles = Math.round((straight * platformDeliveryConfig.detour + 7.4) * 10) / 10;
  const durationMinutes = Math.round((roadMiles / 25) * 60);
  let raw: number;
  if (platformDeliveryConfig.mode === 'FLAT') {
    raw = platformDeliveryConfig.flatFeeMinor;
  } else if (platformDeliveryConfig.mode === 'PER_WEIGHT') {
    raw = platformDeliveryConfig.baseMinor + Math.floor(platformDeliveryConfig.perLbMinor * Math.max(0.1, weightLbs) + 0.5);
  } else {
    raw = platformDeliveryConfig.baseMinor + Math.floor(platformDeliveryConfig.perMileMinor * roadMiles + 0.5);
  }
  const chargeMinor = Math.min(platformDeliveryConfig.maxMinor, Math.max(platformDeliveryConfig.minMinor, raw));
  return { available: roadMiles <= platformDeliveryConfig.maxRadiusMiles, roadMiles, durationMinutes, chargeMinor, pricingMode: platformDeliveryConfig.mode };
}

export function miles(a: [number, number], b: [number, number]): number {
  const R = 3959;
  const t = Math.PI / 180;
  const [la1, lo1] = a;
  const [la2, lo2] = b;
  const x = Math.sin(((la2 - la1) * t) / 2) ** 2 + Math.cos(la1 * t) * Math.cos(la2 * t) * Math.sin(((lo2 - lo1) * t) / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(x));
}

const geoOf = (metro: string): [number, number] => GEO[metro] ?? GEO['Houston, TX'] ?? [29.76, -95.37];

export function locationForZip(zip: string): DeliveryLocation | null {
  if (!/^\d{5}$/.test(zip)) return null;
  const metro = ZIP_PREFIXES_LONG[zip] ?? ZIP_PREFIXES_LONG[zip.slice(0, 3)] ?? ZIP_PREFIXES[zip.slice(0, 2)];
  if (!metro) return null;
  const [city = '', state = ''] = metro.split(', ');
  return { zip, city, state, label: city, serviceable: true, source: 'chosen' };
}

/** ZIP of the nearest sample address to a coordinate (mock reverse geocoding). */
export function nearestZip(lat: number, lon: number): string {
  const best = ADDRESSES.map((a) => ({ a, d: miles([lat, lon], geoOf(a.geo)) })).sort((x, y) => x.d - y.d)[0];
  return best?.a.zip ?? DEFAULT_ZIP;
}

export const metroOf = (loc: DeliveryLocation): string => `${loc.city}, ${loc.state}`;

/**
 * Ranking-only estimate of calendar closeness (the mock's search filters and sorts use it). It is NEVER shown as a date: a
 * date comes from `arrivalWindow`, which needs a real basis, like the promise service behind the real BFF.
 */
export function etaDays(from: ShipFrom, metro: string, express = false): number {
  if (from.fulfilment === 'AFROTRANSACT_DELIVERY') return 1;
  const here = geoOf(metro);
  if (from.fulfilment === 'AFROTRANSACT') {
    const d = Math.min(...FULFILMENT_CENTRES.map(([, c]) => miles(here, geoOf(c))));
    return express ? (d < 400 ? 0 : 1) : d < 700 ? 1 : 2;
  }
  const d = miles(here, GEO[from.city] ?? here);
  return d < 250 ? 2 : d < 900 ? 3 : 5;
}

/* The business calendar of the promise service: weekends and the US federal holidays (observed rules). */
const nthWeekday = (y: number, m: number, wd: number, n: number): number => 1 + ((7 + wd - new Date(Date.UTC(y, m, 1)).getUTCDay()) % 7) + (n - 1) * 7;
const lastWeekday = (y: number, m: number, wd: number): number => {
  const last = new Date(Date.UTC(y, m + 1, 0));
  return last.getUTCDate() - ((7 + last.getUTCDay() - wd) % 7);
};
function federalHolidays(y: number): Set<string> {
  const fixed: [number, number][] = [[0, 1], [5, 19], [6, 4], [10, 11], [11, 25]];
  const days: Date[] = fixed.map(([m, d]) => {
    const t = new Date(Date.UTC(y, m, d));
    if (t.getUTCDay() === 6) t.setUTCDate(d - 1);
    if (t.getUTCDay() === 0) t.setUTCDate(d + 1);
    return t;
  });
  days.push(new Date(Date.UTC(y, 0, nthWeekday(y, 0, 1, 3))), new Date(Date.UTC(y, 1, nthWeekday(y, 1, 1, 3))), new Date(Date.UTC(y, 4, lastWeekday(y, 4, 1))),
    new Date(Date.UTC(y, 8, nthWeekday(y, 8, 1, 1))), new Date(Date.UTC(y, 9, nthWeekday(y, 9, 1, 2))), new Date(Date.UTC(y, 10, nthWeekday(y, 10, 4, 4))));
  return new Set(days.map((d) => d.toISOString().slice(0, 10)));
}
export const isBusinessDay = (d: Date): boolean => d.getUTCDay() !== 0 && d.getUTCDay() !== 6 && !federalHolidays(d.getUTCFullYear()).has(d.toISOString().slice(0, 10));

/** n business days after d (weekends and federal holidays skipped), at UTC midnight. */
export function addBusinessDays(d: Date, n: number): Date {
  const out = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()));
  for (let k = n; k > 0; ) {
    out.setUTCDate(out.getUTCDate() + 1);
    if (isBusinessDay(out)) k--;
  }
  return out;
}

const NO_DATE = { basis: 'NONE' as const, label: '' as const, earliestDate: undefined, latestDate: undefined, days: 0 as const, cutoff: null };
type ArrivalReason = 'NO_PLACE' | 'NO_ORIGIN' | 'NO_SERVICE' | 'UNAVAILABLE';
export type Arrival = { basis: 'PROMISE'; reason?: undefined; label: string; earliestDate: string; latestDate: string; days: number; cutoff: string | null }
  | { basis: 'NONE'; reason: ArrivalReason; label: ''; earliestDate?: undefined; latestDate?: undefined; days: 0; cutoff: null };

/** "Nov 18 – 20", "Nov 30 – Dec 2" or "Wed, Nov 18": the window as shoppers read it (same rule as the BFF's WindowLabel). */
export function windowLabel(from: Date, to: Date): string {
  const f = (d: Date, o: Intl.DateTimeFormatOptions) => d.toLocaleDateString('en-US', { ...o, timeZone: 'UTC' });
  if (from.getTime() === to.getTime()) return f(from, { weekday: 'short', month: 'short', day: 'numeric' });
  if (from.getUTCMonth() === to.getUTCMonth()) return `${f(from, { month: 'short', day: 'numeric' })} – ${f(to, { day: 'numeric' })}`;
  return `${f(from, { month: 'short', day: 'numeric' })} – ${f(to, { month: 'short', day: 'numeric' })}`;
}

/**
 * The delivery window of a package, only when there is a real basis (the promise service's rule): AfroTransact's own stock is
 * dated from the nearest fulfilment centre; a seller-shipped offer needs the seller's configured handling time and where it
 * ships from; AfroTransact Delivery from a store has no basis. Ship day = today before the 14:00 cut-off on a business day, else the next
 * one, plus the handling days; transit is a distance band; weekends and federal holidays never count.
 */
export function arrivalWindow(from: ShipFrom, metro: string, now: Date): Arrival {
  const none = (reason: ArrivalReason): Arrival => ({ ...NO_DATE, reason });
  if (from.fulfilment === 'AFROTRANSACT_DELIVERY') return none('NO_ORIGIN');
  const here = geoOf(metro);
  let band: [number, number];
  let handling = 0;
  if (from.fulfilment === 'AFROTRANSACT') {
    const d = Math.min(...FULFILMENT_CENTRES.map(([, c]) => miles(here, geoOf(c))));
    band = d < 700 ? [1, 2] : [2, 3];
  } else {
    if (from.handling === null || from.handling === undefined || !GEO[from.city]) return none('NO_ORIGIN');
    handling = from.handling;
    const d = miles(here, GEO[from.city] ?? here);
    band = d < 250 ? [1, 2] : d < 900 ? [2, 3] : [3, 5];
  }
  const today = new Date(Date.UTC(now.getFullYear(), now.getMonth(), now.getDate()));
  const shipsToday = isBusinessDay(today) && now.getHours() < (from.fulfilment === 'AFROTRANSACT' ? 14 : 12);
  const ship = shipsToday ? today : addBusinessDays(today, 1);
  const base = addBusinessDays(ship, handling);
  const earliest = addBusinessDays(base, band[0]);
  const latest = addBusinessDays(base, band[1]);
  const days = Math.max(0, Math.round((earliest.getTime() - today.getTime()) / 86_400_000));
  return { basis: 'PROMISE', label: windowLabel(earliest, latest), earliestDate: earliest.toISOString().slice(0, 10), latestDate: latest.toISOString().slice(0, 10), days, cutoff: null };
}

export function nearestFc(metro: string): string {
  const here = geoOf(metro);
  const best = FULFILMENT_CENTRES.map(([n, c]) => ({ n, d: miles(here, geoOf(c)) })).sort((a, b) => a.d - b.d)[0];
  return best?.n ?? 'Houston FC';
}

/**
 * How an offer is delivered and what it costs; dated only when `dated` and there is a real basis (`arrivalWindow`). Cards and
 * rails pass `dated: false`: like the real BFF they carry the method and the fee, no date; `days` stays as the ranking estimate
 * the mock's own search filters use and is not a promise.
 */
export function deliveryPromise(from: ShipFrom, metro: string, now: Date, dated = false): DeliveryPromise {
  const isAT = from.fulfilment === 'AFROTRANSACT';
  const settings = getShippingSettings();
  const window = dated ? arrivalWindow(from, metro, now) : ({ ...NO_DATE, reason: 'NO_ORIGIN' } as Arrival);
  // Launch shipping: plain "Delivery", one flat fee. No carrier is ever named and there is no express.
  return {
    days: window.basis === 'PROMISE' ? window.days : etaDays(from, metro),
    label: window.label,
    prefix: isAT ? LABEL_DELIVERY : LABEL_STORE_DELIVERY,
    free: settings.flatFeeMinor === 0,
    feeMinor: settings.flatFeeMinor,
    cutoff: null,
    basis: window.basis,
    ...(window.basis === 'NONE' ? { reason: window.reason } : { earliestDate: window.earliestDate, latestDate: window.latestDate })
  };
}

/** The window of a package: known only when every line's is; it covers the slowest line (like the BFF's mergeArrivals). */
export function mergeWindows(list: Arrival[]): Arrival {
  const first = list[0];
  if (!first) return { ...NO_DATE, reason: 'NO_ORIGIN' };
  let latest = first;
  let earliest = first;
  for (const a of list) {
    if (a.basis === 'NONE') return a;
    if (latest.basis === 'PROMISE' && a.latestDate > latest.latestDate) latest = a;
    if (earliest.basis === 'PROMISE' && a.earliestDate > earliest.earliestDate) earliest = a;
  }
  if (earliest.basis !== 'PROMISE' || latest.basis !== 'PROMISE') return { ...NO_DATE, reason: 'NO_ORIGIN' };
  const e = new Date(`${earliest.earliestDate}T00:00:00Z`);
  const l = new Date(`${latest.latestDate}T00:00:00Z`);
  return { basis: 'PROMISE', label: windowLabel(e, l), earliestDate: earliest.earliestDate, latestDate: latest.latestDate, days: earliest.days, cutoff: null };
}

/** Shopper-facing option labels (launch shipping). No carrier is ever named. */
export const LABEL_DELIVERY = 'Delivery';
export const LABEL_STORE_DELIVERY = 'Delivery';
export const LABEL_PICKUP_POINT = 'Pickup';
export const LABEL_STORE_PICKUP = 'Pickup';
export const LABEL_SELLER_SHIPPING = 'Standard shipping from the seller';
export const LABEL_QUOTE_REQUIRED = 'Too far for standard delivery. Ask us for a quote';

/** Delivery options for one package: plain "Delivery" at the staff flat fee (free pickup is added at checkout), dated only by a real window. */
export function deliveryOptions(pkg: { from: ShipFrom; sellerName: string; arrival: Arrival; subtotalMinor: number }, _metro: string, _now: Date): DeliveryOption[] {
  const settings = getShippingSettings();
  const isAT = pkg.from.fulfilment === 'AFROTRANSACT';
  const fee = deliveryFee(pkg.subtotalMinor, settings);
  const a = pkg.arrival;
  const dates = a.basis === 'PROMISE' ? { earliestDate: a.earliestDate, latestDate: a.latestDate } : {};
  const delivery: DeliveryOption = { key: 'afrotransact_delivery', label: isAT ? LABEL_DELIVERY : LABEL_STORE_DELIVERY, eta: a.label, feeMinor: fee, free: fee === 0, ...dates };
  const sellerShips = pkg.from.fulfilment === 'SELLER' && settings.sellerCarrierShippingEnabled;
  if (!sellerShips) return [delivery];
  const free = pkg.subtotalMinor >= FREE_SHIPPING_THRESHOLD_MINOR;
  return [{ key: 'carrier', label: LABEL_SELLER_SHIPPING, eta: a.label, feeMinor: free ? 0 : SELLER_SHIPPING_MINOR, free, ...dates }, delivery];
}

/** Whether an address is inside a delivery zone (the mock's zones: the metros AfroTransact delivers in). */
export function insideDeliveryZone(metro: string): boolean {
  const here = geoOf(metro);
  return ['Houston, TX', 'Edison, NJ', 'Newark, NJ', 'Atlanta, GA', 'Dallas, TX'].some((c) => !!GEO[c] && miles(here, geoOf(c)) < 60);
}
