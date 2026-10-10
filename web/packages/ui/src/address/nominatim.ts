import { type AddressSuggestionItem, type SearchAddresses } from './types';

export interface NominatimOptions {
  /** Defaults to the public OSM instance; use your own for production traffic (usage policy: max 1 req/s). */
  endpoint?: string;
  /** ISO 3166-1 alpha-2 codes to restrict results (e.g. ["us"]). */
  countryCodes?: string[];
  /** Contact email sent as the `email` parameter, as the Nominatim usage policy asks. */
  email?: string;
  limit?: number;
  language?: string;
  fetch?: typeof fetch;
}

interface NominatimResult {
  place_id: number;
  lat: string;
  lon: string;
  display_name: string;
  address?: Record<string, string | undefined>;
}

const STATE_CODES: Record<string, string> = {
  Alabama: 'AL', Alaska: 'AK', Arizona: 'AZ', Arkansas: 'AR', California: 'CA', Colorado: 'CO', Connecticut: 'CT', Delaware: 'DE', 'District of Columbia': 'DC', Florida: 'FL', Georgia: 'GA',
  Hawaii: 'HI', Idaho: 'ID', Illinois: 'IL', Indiana: 'IN', Iowa: 'IA', Kansas: 'KS', Kentucky: 'KY', Louisiana: 'LA', Maine: 'ME', Maryland: 'MD', Massachusetts: 'MA', Michigan: 'MI',
  Minnesota: 'MN', Mississippi: 'MS', Missouri: 'MO', Montana: 'MT', Nebraska: 'NE', Nevada: 'NV', 'New Hampshire': 'NH', 'New Jersey': 'NJ', 'New Mexico': 'NM', 'New York': 'NY',
  'North Carolina': 'NC', 'North Dakota': 'ND', Ohio: 'OH', Oklahoma: 'OK', Oregon: 'OR', Pennsylvania: 'PA', 'Rhode Island': 'RI', 'South Carolina': 'SC', 'South Dakota': 'SD',
  Tennessee: 'TN', Texas: 'TX', Utah: 'UT', Vermont: 'VT', Virginia: 'VA', Washington: 'WA', 'West Virginia': 'WV', Wisconsin: 'WI', Wyoming: 'WY'
};

/** Maps one Nominatim jsonv2 result (addressdetails=1) to a suggestion. Exported for tests. */
export function fromNominatim(r: NominatimResult): AddressSuggestionItem {
  const a = r.address ?? {};
  const street = [a.house_number, a.road].filter(Boolean).join(' ');
  const city = a.city ?? a.town ?? a.village ?? a.hamlet ?? a.suburb ?? '';
  const stateName = a.state ?? '';
  const state = STATE_CODES[stateName] ?? stateName;
  const zip = (a.postcode ?? '').slice(0, 10);
  const line1 = street || (r.display_name.split(',')[0] ?? '').trim();
  return {
    id: `osm-${r.place_id}`,
    label: line1,
    secondary: [city, [state, zip].filter(Boolean).join(' ')].filter(Boolean).join(', '),
    address: { line1, line2: '', city, state, zip, country: (a.country_code ?? '').toUpperCase(), lat: Number(r.lat), lon: Number(r.lon) },
    source: 'osm'
  };
}

/** OpenStreetMap Nominatim implementation of SearchAddresses. */
export function createNominatimSearch(options: NominatimOptions = {}): SearchAddresses {
  const endpoint = options.endpoint ?? 'https://nominatim.openstreetmap.org/search';
  const doFetch = options.fetch ?? fetch;
  return async (query, { signal }) => {
    const q = query.trim();
    if (q.length < 3) return [];
    const p = new URLSearchParams({ q, format: 'jsonv2', addressdetails: '1', limit: String(options.limit ?? 5) });
    if (options.countryCodes?.length) p.set('countrycodes', options.countryCodes.join(','));
    if (options.email) p.set('email', options.email);
    if (options.language) p.set('accept-language', options.language);
    const res = await doFetch(`${endpoint}?${p.toString()}`, { signal, headers: { Accept: 'application/json' } });
    if (!res.ok) throw new Error(`Nominatim ${res.status}`);
    const rows = (await res.json()) as NominatimResult[];
    return rows.map(fromNominatim);
  };
}
