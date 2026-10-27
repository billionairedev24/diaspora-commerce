import { type RunLocationInput, type StaffRunLocation } from '@afrotransact/api-client';

import { amountMinor, wholeOrNull } from './model';

/* The model of the location form (S262): what people type, and the body it sends. */

export interface LocationForm {
  name: string;
  market: string;
  state: string;
  timeZone: string;
  areaKind: 'RADIUS' | 'POLYGON';
  radiusMiles: string;
  centerLat: string;
  centerLng: string;
  /** A drawn area: GeoJSON text (blank while the saved area is kept). */
  outline: string;
  /** Editing a drawn area: keep the zone that is registered. */
  keepArea: boolean;
  fee: string;
  minOrders: string;
  capacityOrders: string;
  link: string;
  /** Create only. */
  active: boolean;
}

/** The fee as dollars text ("7.99"). */
const dollars = (minor: number | undefined): string => (minor === undefined ? '' : `${Math.floor(minor / 100)}.${String(minor % 100).padStart(2, '0')}`);

export function newLocationForm(): LocationForm {
  return {
    name: '',
    market: 'US',
    state: '',
    timeZone: 'America/Chicago',
    areaKind: 'RADIUS',
    radiusMiles: '12',
    centerLat: '',
    centerLng: '',
    outline: '',
    keepArea: false,
    fee: '7.99',
    minOrders: '12',
    capacityOrders: '40',
    link: '',
    active: true
  };
}

export function locationFormOf(location: StaffRunLocation): LocationForm {
  const area = location.area;
  return {
    name: location.name,
    market: location.market ?? 'US',
    state: location.state ?? '',
    timeZone: location.timeZone ?? 'America/Chicago',
    areaKind: area?.kind ?? 'RADIUS',
    radiusMiles: area?.radiusMiles ? String(area.radiusMiles) : '',
    centerLat: area?.centerLat === undefined ? '' : String(area.centerLat),
    centerLng: area?.centerLng === undefined ? '' : String(area.centerLng),
    outline: '',
    keepArea: area?.kind === 'POLYGON' && !!area.geoZoneId,
    fee: dollars(location.defaultFeeMinor),
    minOrders: location.defaultMinOrders ? String(location.defaultMinOrders) : '',
    capacityOrders: location.defaultCapacityOrders ? String(location.defaultCapacityOrders) : '',
    link: location.whatsappInviteUrl ?? '',
    active: location.active !== false
  };
}

/** Whether any field differs from what the form opened with (Save stays off until something changed). */
export const locationFormChanged = (form: LocationForm, initial: LocationForm): boolean => (Object.keys(initial) as (keyof LocationForm)[]).some((k) => form[k] !== initial[k]);

const decimal = (text: string): number => {
  const t = text.trim();
  return /^-?\d{1,3}(?:\.\d{1,8})?$/.test(t) ? Number(t) : Number.NaN;
};

/** The body to send. A number that is not a number is sent as NaN-free null so the shared checks flag it. */
export function locationInputOf(form: LocationForm, saved: StaffRunLocation | null, creating: boolean): RunLocationInput {
  const fee = amountMinor(form.fee);
  const whole = (text: string): number | null => {
    const n = wholeOrNull(text);
    return n !== null && Number.isNaN(n) ? 0 : n;
  };
  const radius = decimal(form.radiusMiles);
  return {
    name: form.name.trim(),
    market: form.market.trim(),
    state: form.state.trim() ? form.state.trim() : null,
    timeZone: form.timeZone.trim() ? form.timeZone.trim() : undefined,
    areaKind: form.areaKind,
    radiusMiles: form.areaKind === 'RADIUS' ? radius : null,
    geoZoneId: form.areaKind === 'POLYGON' && form.keepArea ? (saved?.area?.geoZoneId ?? null) : null,
    polygonGeoJson: form.areaKind === 'POLYGON' && !form.keepArea ? form.outline.trim() : null,
    centerLat: decimal(form.centerLat),
    centerLng: decimal(form.centerLng),
    defaultFeeMinor: fee !== null && Number.isNaN(fee) ? -1 : fee,
    defaultMinOrders: whole(form.minOrders),
    defaultCapacityOrders: whole(form.capacityOrders),
    whatsappInviteUrl: form.link.trim(),
    ...(creating ? { active: form.active } : {})
  };
}
