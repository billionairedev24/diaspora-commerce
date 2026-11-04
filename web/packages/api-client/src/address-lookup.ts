import { type AddressSuggestion, type AddressSuggestions, type PostalAddress } from './schemas/location';

/** Options of one autocomplete keystroke. */
export interface AddressSearchOptions {
  /** One token per address entry: every keystroke and the resolve of the picked suggestion (Google Places billing). */
  sessionToken?: string;
  /** ISO 3166-1 alpha-2 country the suggestions are restricted to; default: the market's (BFF). */
  country?: string;
}

/** The BFF calls an address field needs (storefront, Seller Center, staff console and customer app clients). */
export interface AddressLookupClient {
  searchAddresses: (q: string, signal?: AbortSignal, options?: AddressSearchOptions) => Promise<AddressSuggestions>;
  resolveAddressSuggestion: (id: string, sessionToken?: string, signal?: AbortSignal) => Promise<PostalAddress>;
}

/** A suggestion with `resolve()`: the structured address of a picked suggestion (a Place Details call for Google). */
export type ResolvableAddressSuggestion = AddressSuggestion & { resolve?: (options?: { signal?: AbortSignal }) => Promise<PostalAddress> };

/** The search function the standard address fields take (`@afrotransact/ui` AddressAutocomplete / AddressFields). */
export type AddressLookup = (query: string, options: { signal: AbortSignal; sessionToken?: string }) => Promise<ResolvableAddressSuggestion[]>;

/**
 * Address search over the BFF geo endpoints. The field passes its entry's session token; suggestions Google returns
 * unresolved (`resolved: false`) carry a `resolve()` that asks the BFF for the Place Details with the same token.
 */
export function addressLookup(client: AddressLookupClient, defaults: Omit<AddressSearchOptions, 'sessionToken'> = {}): AddressLookup {
  return async (query, { signal, sessionToken }) => {
    const res = await client.searchAddresses(query, signal, { ...defaults, sessionToken });
    const token = sessionToken ?? (res.sessionToken || undefined);
    return res.suggestions.map((s) =>
      s.resolved ? s : { ...s, resolve: (o?: { signal?: AbortSignal }) => client.resolveAddressSuggestion(s.id, token, o?.signal) }
    );
  };
}

/** A new autocomplete session token: 32 URL-safe characters (the BFF and Google accept 8–36). */
export function newAddressSessionToken(): string {
  const c = globalThis.crypto;
  if (c && typeof c.randomUUID === 'function') return c.randomUUID().replace(/-/g, '');
  let out = '';
  for (let i = 0; i < 32; i += 1) out += Math.floor(Math.random() * 16).toString(16);
  return out;
}
