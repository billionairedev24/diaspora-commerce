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
  /** false: `address` holds the street line only (Google Places); `resolve()` fetches the rest when picked. */
  resolved?: boolean;
  /** Structured address of this suggestion (Place Details); called once, when the suggestion is picked. */
  resolve?: (options?: { signal?: AbortSignal }) => Promise<AddressParts>;
}

export interface SearchAddressesOptions {
  signal: AbortSignal;
  /**
   * One token per address entry, sent with every keystroke and with the resolve of the picked suggestion: Google
   * Places bills the entry as one session. The field starts a new one after a pick.
   */
  sessionToken?: string;
}

/** Pluggable address search. The geo service backs it with Google Places or OSM Nominatim. */
export type SearchAddresses = (query: string, options: SearchAddressesOptions) => Promise<AddressSuggestionItem[]>;
