/* Deliver-to place suggestions (GET /v1/geo/address-suggestions?kind=place): cities with their state, a five-digit ZIP
   as its city, streets as "Street" / "City, ST". The ZIP is never a label. Production: the BFF over the geo service. */
import { type AddressSuggestion, type AddressSuggestions } from '../schemas/location';
import { suggestAddresses } from './addresses';
import { locationForZip } from './delivery';
import { ADDRESSES, STREETS } from './fixtures/geo';

export interface MockPlace { city: string; state: string; zip: string }

/** Cities the mock knows (with the ZIP at their centre), plus a few ambiguous names to exercise the state suffix. */
const EXTRA: MockPlace[] = [
  { city: 'Georgetown', state: 'TX', zip: '78626' },
  { city: 'Georgetown', state: 'KY', zip: '40324' },
  { city: 'Georgetown', state: 'DE', zip: '19947' },
  { city: 'Austin', state: 'TX', zip: '78701' }
];

function allPlaces(): MockPlace[] {
  const out = new Map<string, MockPlace>();
  const add = (p: MockPlace) => {
    const k = `${p.city}|${p.state}`;
    if (!out.has(k)) out.set(k, p);
  };
  for (const a of ADDRESSES) add({ city: a.city, state: a.state, zip: a.zip });
  for (const [, city, state, zip] of STREETS) add({ city, state, zip });
  for (const p of EXTRA) add(p);
  return [...out.values()];
}

const placeId = (p: MockPlace) => `pl:${p.zip}:${p.state}:${encodeURIComponent(p.city)}`;

/** The place behind a suggestion id (`zip:78626` or a place id), or null. */
export function mockPlaceOf(id: string): MockPlace | null {
  if (id.startsWith('zip:')) {
    const zip = id.slice(4);
    const loc = locationForZip(zip);
    const known = allPlaces().find((p) => p.zip === zip);
    return known ?? (loc ? { city: loc.city, state: loc.state, zip } : null);
  }
  const m = /^pl:(\d{5}):([A-Z]{2}):(.+)$/.exec(id);
  return m ? { city: decodeURIComponent(m[3] ?? ''), state: m[2] ?? '', zip: m[1] ?? '' } : null;
}

const row = (id: string, label: string, secondary: string, address: AddressSuggestion['address']): AddressSuggestion => ({ id, label, secondary, address, source: 'geo', resolved: true });

export function mockPlaceSuggestions(q: string, sessionToken: string | null): AddressSuggestions {
  const text = q.trim();
  const token = sessionToken || `mock${Math.random().toString(36).slice(2, 12)}`;
  const done = (suggestions: AddressSuggestion[]): AddressSuggestions => ({ suggestions, attribution: '', sessionToken: token });
  if (/^\d+$/.test(text)) {
    const p = text.length === 5 ? mockPlaceOf(`zip:${text}`) : null;
    return done(p ? [row(`zip:${p.zip}`, p.city, p.state, { line1: '', line2: '', city: p.city, state: p.state, zip: p.zip, country: 'US', lat: null, lon: null })] : []);
  }
  const words = text.toLowerCase().split(/\s+/).filter(Boolean);
  const streets = suggestAddresses(text, 6)
    .filter((s) => /^\d/.test(s.label) || !words.length)
    .map((s) => row(placeId({ city: s.address.city, state: s.address.state, zip: s.address.zip }), s.label, `${s.address.city}, ${s.address.state}`, s.address));
  if (/^\d/.test(text)) return done(streets.slice(0, 6));
  const cities = allPlaces()
    .filter((p) => words.every((w) => p.city.toLowerCase().split(/\s+/).some((c) => c.startsWith(w)) || p.state.toLowerCase() === w))
    .sort((a, b) => a.city.localeCompare(b.city) || a.state.localeCompare(b.state))
    .slice(0, 4)
    .map((p) => row(placeId(p), p.city, p.state, { line1: '', line2: '', city: p.city, state: p.state, zip: p.zip, country: 'US', lat: null, lon: null }));
  return done([...cities, ...streets].slice(0, 6));
}
