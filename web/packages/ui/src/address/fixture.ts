import { type AddressSuggestionItem, type SearchAddresses } from './types';

export interface FixtureAddress {
  line1: string;
  city: string;
  state: string;
  zip: string;
}

/** In-memory SearchAddresses for stories, tests and offline demos (prefix / contains / ZIP match). */
export function createFixtureAddressSearch(addresses: FixtureAddress[], { delayMs = 0 } = {}): SearchAddresses {
  return async (query, { signal }) => {
    if (delayMs) await new Promise((r) => setTimeout(r, delayMs));
    if (signal.aborted) throw new DOMException('Aborted', 'AbortError');
    const v = query.trim().toLowerCase();
    if (!v) return [];
    return addresses
      .filter((a) => a.line1.toLowerCase().includes(v) || a.zip.startsWith(v) || `${a.line1} ${a.city}`.toLowerCase().includes(v))
      .slice(0, 5)
      .map<AddressSuggestionItem>((a, i) => ({
        id: `fx-${i}-${a.zip}`,
        label: a.line1,
        secondary: `${a.city}, ${a.state} ${a.zip}`,
        address: { line1: a.line1, line2: '', city: a.city, state: a.state, zip: a.zip, country: 'US', lat: null, lon: null },
        source: 'fixture'
      }));
  };
}

export const SAMPLE_ADDRESSES: FixtureAddress[] = [
  { line1: '9800 Westheimer Rd', city: 'Houston', state: 'TX', zip: '77042' },
  { line1: '2400 Fountain View Dr', city: 'Houston', state: 'TX', zip: '77057' },
  { line1: '7100 Bellaire Blvd', city: 'Houston', state: 'TX', zip: '77074' },
  { line1: '1250 Grand Concourse', city: 'Bronx', state: 'NY', zip: '10456' },
  { line1: '8605 Colesville Rd', city: 'Silver Spring', state: 'MD', zip: '20910' },
  { line1: '455 Edgewood Ave SE', city: 'Atlanta', state: 'GA', zip: '30312' },
  { line1: '1430 Oak Tree Rd', city: 'Iselin', state: 'NJ', zip: '08830' },
  { line1: '2901 Cedar Ave S', city: 'Minneapolis', state: 'MN', zip: '55407' }
];
