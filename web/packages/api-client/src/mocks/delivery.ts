/* Delivery promises ported from the prototype (miles, offerEta, fmtDay, nearestFc, shipOpts).
   Production: the promise service computes these per ZIP from carrier transit tables. */
import { type DeliveryOption } from '../schemas/cart';
import { type DeliveryPromise } from '../schemas/catalog';
import { type DeliveryLocation } from '../schemas/location';
import { ADDRESSES, FULFILMENT_CENTRES, GEO, PICKUP_POINTS, ZIP_PREFIXES, ZIP_PREFIXES_LONG } from './fixtures/geo';

export const DEFAULT_ZIP = '77042';
export const SELLER_SHIPPING_MINOR = 599;
export const FREE_SHIPPING_THRESHOLD_MINOR = 4900;

export interface ShipFrom { fulfilment: 'AFROTRANSACT' | 'SELLER' | 'AFROTRANSACT_DELIVERY'; city: string }

/** AfroTransact Delivery from the seller's store (S255): payments' seed pricing (V9) and service radius. */
export const LOCAL_DELIVERY = { baseMinor: 499, perMileMinor: 85, minMinor: 599, maxMinor: 2499, maxRadiusMiles: 25, detour: 1.3 } as const;

export interface LocalQuote {
  available: boolean;
  roadMiles: number;
  durationMinutes: number;
  chargeMinor: number;
}

/** Road distance store → shopper (geodesic × detour, like geo's last resort) and the customer charge. */
export function localDeliveryQuote(storeCity: string, metro: string): LocalQuote {
  const straight = miles(geoOf(storeCity), geoOf(metro));
  const roadMiles = Math.round((straight * LOCAL_DELIVERY.detour + 7.4) * 10) / 10;
  const durationMinutes = Math.round((roadMiles / 25) * 60);
  const raw = LOCAL_DELIVERY.baseMinor + Math.floor(LOCAL_DELIVERY.perMileMinor * roadMiles + 0.5);
  const chargeMinor = Math.min(LOCAL_DELIVERY.maxMinor, Math.max(LOCAL_DELIVERY.minMinor, raw));
  return { available: roadMiles <= LOCAL_DELIVERY.maxRadiusMiles, roadMiles, durationMinutes, chargeMinor };
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
  return { zip, city, state, label: `${city} ${zip}`, serviceable: true };
}

/** ZIP of the nearest sample address to a coordinate (mock reverse geocoding). */
export function nearestZip(lat: number, lon: number): string {
  const best = ADDRESSES.map((a) => ({ a, d: miles([lat, lon], geoOf(a.geo)) })).sort((x, y) => x.d - y.d)[0];
  return best?.a.zip ?? DEFAULT_ZIP;
}

export const metroOf = (loc: DeliveryLocation): string => `${loc.city}, ${loc.state}`;

/** Business days (Sundays skipped) from the shopper's metro. */
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

export function addBusinessDays(now: Date, n: number): Date {
  const d = new Date(now);
  let k = n;
  while (k > 0) {
    d.setDate(d.getDate() + 1);
    if (d.getDay() !== 0) k--;
  }
  return d;
}

/** "Today", "Tomorrow, Oct 11" or "Wednesday, Oct 14". */
export function dayLabel(n: number, now: Date): string {
  if (n === 0) return 'Today';
  const s = addBusinessDays(now, n).toLocaleDateString('en-US', { weekday: 'long', month: 'short', day: 'numeric' });
  return n === 1 ? 'Tomorrow, ' + s.split(', ').slice(1).join(', ') : s;
}

export function nearestFc(metro: string): string {
  const here = geoOf(metro);
  const best = FULFILMENT_CENTRES.map(([n, c]) => ({ n, d: miles(here, geoOf(c)) })).sort((a, b) => a.d - b.d)[0];
  return best?.n ?? 'Houston FC';
}

function cutoffNote(now: Date): string {
  const end = new Date(now);
  end.setHours(23, 59, 0, 0);
  const mins = Math.max(1, Math.round((end.getTime() - now.getTime()) / 60000));
  return `Order within ${Math.floor(mins / 60)} hrs ${mins % 60} mins.`;
}

export function deliveryPromise(from: ShipFrom, metro: string, now: Date): DeliveryPromise {
  const days = etaDays(from, metro);
  if (from.fulfilment === 'AFROTRANSACT_DELIVERY') return { days, label: dayLabel(days, now), prefix: 'AfroTransact Delivery', free: false, feeMinor: 0, expressLabel: null, cutoff: null };
  const isAT = from.fulfilment === 'AFROTRANSACT';
  return {
    days,
    label: dayLabel(days, now),
    prefix: isAT ? 'FREE delivery' : 'Delivery',
    free: isAT,
    feeMinor: isAT ? 0 : SELLER_SHIPPING_MINOR,
    expressLabel: isAT ? dayLabel(etaDays(from, metro, true), now) : null,
    cutoff: isAT ? cutoffNote(now) : null
  };
}

const money = (minor: number) => `$${(minor / 100).toFixed(2)}`;

/** Delivery options for one package (carrier, express, AfroTransact Delivery, pickup). */
export function deliveryOptions(pkg: { from: ShipFrom; sellerName: string; slowestDays: number; subtotalMinor: number; expressDays: number }, metro: string, now: Date): DeliveryOption[] {
  const isAT = pkg.from.fulfilment === 'AFROTRANSACT';
  if (pkg.from.fulfilment === 'AFROTRANSACT_DELIVERY')
    return [{ key: 'afrotransact_delivery', label: 'AfroTransact Delivery · priced by distance at checkout', eta: dayLabel(1, now), feeMinor: 0, free: false }];
  const here = geoOf(metro);
  const near = (city: string) => !!GEO[city] && miles(here, geoOf(city)) < 60;
  const out: DeliveryOption[] = [];
  const freeCarrier = isAT || pkg.subtotalMinor >= FREE_SHIPPING_THRESHOLD_MINOR;
  out.push({
    key: 'carrier',
    label: isAT ? 'UPS Ground or USPS Ground Advantage' : 'USPS Ground Advantage',
    eta: dayLabel(pkg.slowestDays, now),
    feeMinor: freeCarrier ? 0 : SELLER_SHIPPING_MINOR,
    free: freeCarrier
  });
  if (isAT) out.push({ key: 'express', label: 'UPS Next Day Air', eta: dayLabel(pkg.expressDays, now), feeMinor: 799, free: false });
  const zoneOk = ['Houston, TX', 'Edison, NJ', 'Newark, NJ', 'Atlanta, GA', 'Dallas, TX'].some(near);
  const srcOk = isAT ? FULFILMENT_CENTRES.some(([, c]) => near(c)) : near(pkg.from.city);
  if (zoneOk && srcOk) {
    const free = isAT && pkg.subtotalMinor >= 3500;
    out.push({ key: 'afrotransact_delivery', label: 'AfroTransact Delivery (our driver, photo + code on delivery)', eta: isAT ? 'Today by 9 pm' : dayLabel(1, now) + ' by 9 pm', feeMinor: free ? 0 : 499, free });
  }
  const points = PICKUP_POINTS.filter((p) => (isAT ? p.owner === 'AT' : p.owner === pkg.sellerName) && near(p.metro));
  const first = points[0];
  if (first) out.push({ key: 'pickup', label: `Pick up (${points.length} location${points.length > 1 ? 's' : ''})`, eta: first.ready, feeMinor: 0, free: true });
  return out.map((o) => ({ ...o, label: `${o.free ? 'FREE' : money(o.feeMinor)} · ${o.label}` }));
}
