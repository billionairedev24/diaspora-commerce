export interface AddressParts {
  line1: string;
  line2: string;
  city: string;
  state: string;
  zip: string;
  country: string;
  lat: number | null;
  lon: number | null;
}

export interface AddressSuggestionItem {
  id: string;
  /** Primary line, e.g. "9800 Westheimer Rd". */
  label: string;
  /** Secondary line, e.g. "Houston, TX 77042". */
  secondary: string;
  address: AddressParts;
  source: 'google' | 'osm' | 'fixture';
}

/** Pluggable address search. The geo service backs it with Google Places or OSM Nominatim. */
export type SearchAddresses = (query: string, options: { signal: AbortSignal }) => Promise<AddressSuggestionItem[]>;
