/* Address autocomplete over the prototype's sample addresses and streets (port of acSuggest for the
   street field). Production: the geo service proxies Google Places or OSM Nominatim. */
import { type AddressSuggestion } from '../schemas/location';
import { ADDRESSES, STREETS } from './fixtures/geo';
import { levenshtein } from './matching';

export function suggestAddresses(input: string, limit = 5): AddressSuggestion[] {
  const v = input.trim().toLowerCase();
  if (!v) return [];
  const zipOnly = /^\d{2,5}$/.test(v);
  const m = /^(\d+)\s*(.*)$/.exec(v);
  const num = m?.[1] ?? '';
  const rest = (m ? (m[2] ?? '') : v).trim();
  const exact = ADDRESSES.filter((x) => {
    const l = x.line1.toLowerCase();
    if (zipOnly) return x.zip.startsWith(v);
    return l.includes(v) || (v.length > 2 && l.startsWith(v.split(' ')[0] ?? '') && levenshtein(l.slice(0, v.length), v) <= 3);
  }).map((x) => ({ line1: x.line1, city: x.city, state: x.state, zip: x.zip }));
  const near = zipOnly
    ? STREETS.filter(([, , , z]) => z.startsWith(v)).map(([st, c, s, z]) => ({ line1: `${100 + ((st.length * 37) % 900)} ${st}`, city: c, state: s, zip: z }))
    : STREETS.filter(([st]) => {
        const n = st.toLowerCase();
        return !rest || n.startsWith(rest) || n.includes(' ' + rest) || (rest.length > 3 && levenshtein(n.slice(0, rest.length), rest) <= 1);
      }).map(([st, c, s, z]) => ({ line1: `${num || String(100 + ((st.length * 37) % 900))} ${st}`, city: c, state: s, zip: z }));
  const seen = new Set<string>();
  return [...exact, ...near]
    .filter((x) => {
      const k = x.line1 + x.zip;
      if (seen.has(k)) return false;
      seen.add(k);
      return true;
    })
    .slice(0, limit)
    .map((x, i) => ({
      id: `fx-${i}-${x.zip}-${x.line1.replace(/\W+/g, '-').toLowerCase()}`,
      label: x.line1,
      secondary: `${x.city}, ${x.state} ${x.zip}`,
      address: { line1: x.line1, line2: '', city: x.city, state: x.state, zip: x.zip, country: 'US', lat: null, lon: null },
      source: 'fixture' as const
    }));
}
