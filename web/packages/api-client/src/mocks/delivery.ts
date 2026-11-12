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

export interface ShipFrom { fulfilment: 'AFROTRANSACT' | 'SELLER' | 'AFROTRANSACT_DELIVERY'; city: string }

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
  const isAT = from.fulfilment === 'AFROTRANSACT';
  const settings = getShippingSettings();
  // Launch shipping: Delivery by AfroTransact (or AfroTransact collects from the store and delivers), one flat fee.
  // No carrier is ever named and there is no express.
  return {
    days,
    label: dayLabel(days, now),
    prefix: isAT ? LABEL_DELIVERY : LABEL_STORE_DELIVERY,
    free: settings.flatFeeMinor === 0,
    feeMinor: settings.flatFeeMinor,
    cutoff: isAT ? cutoffNote(now) : null
  };
}

/** Shopper-facing option labels (launch shipping). No carrier is ever named. */
export const LABEL_DELIVERY = 'Delivery';
export const LABEL_STORE_DELIVERY = 'Delivery';
export const LABEL_PICKUP_POINT = 'Pickup';
export const LABEL_STORE_PICKUP = 'Pickup';
export const LABEL_SELLER_SHIPPING = 'Standard shipping from the seller';
export const LABEL_QUOTE_REQUIRED = 'Too far for standard delivery. Ask us for a quote';

/** Delivery options for one package: AfroTransact delivers at the staff flat fee (free pickup is added at checkout). */
export function deliveryOptions(pkg: { from: ShipFrom; sellerName: string; slowestDays: number; subtotalMinor: number }, _metro: string, now: Date): DeliveryOption[] {
  const settings = getShippingSettings();
  const isAT = pkg.from.fulfilment === 'AFROTRANSACT';
  const days = pkg.from.fulfilment === 'AFROTRANSACT_DELIVERY' ? 1 : pkg.slowestDays;
  const fee = deliveryFee(pkg.subtotalMinor, settings);
  const delivery: DeliveryOption = { key: 'afrotransact_delivery', label: isAT ? LABEL_DELIVERY : LABEL_STORE_DELIVERY, eta: dayLabel(days, now), feeMinor: fee, free: fee === 0 };
  const sellerShips = pkg.from.fulfilment === 'SELLER' && settings.sellerCarrierShippingEnabled;
  if (!sellerShips) return [delivery];
  const free = pkg.subtotalMinor >= FREE_SHIPPING_THRESHOLD_MINOR;
  return [{ key: 'carrier', label: LABEL_SELLER_SHIPPING, eta: dayLabel(pkg.slowestDays, now), feeMinor: free ? 0 : SELLER_SHIPPING_MINOR, free }, delivery];
}

/** Whether an address is inside a delivery zone (the mock's zones: the metros AfroTransact delivers in). */
export function insideDeliveryZone(metro: string): boolean {
  const here = geoOf(metro);
  return ['Houston, TX', 'Edison, NJ', 'Newark, NJ', 'Atlanta, GA', 'Dallas, TX'].some((c) => !!GEO[c] && miles(here, geoOf(c)) < 60);
}
