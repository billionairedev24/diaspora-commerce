/* Runtime configuration. Server values are read with bracket access so they are resolved at request
   time (Node or Cloudflare Workers vars), not inlined at build time — one build serves every environment. */

const env = (name: string): string | undefined => {
  const v = process.env[name];
  return v === undefined || v === '' ? undefined : v;
};

/** True when the storefront should use the in-process MSW mock BFF (NEXT_PUBLIC_API_MOCKS=1 or API_MOCKS=1). */
export function mocksEnabled(): boolean {
  return env('NEXT_PUBLIC_API_MOCKS') === '1' || env('API_MOCKS') === '1';
}

/** Gateway BFF origin for the /api/bff proxy and server components (e.g. http://localhost:8200). */
export function bffOrigin(): string {
  return env('BFF_ORIGIN') ?? 'http://localhost:8200';
}

/** Seller Center & Provider Hub origin (Sell on AfroTransact / Offer your services): the shared SELLER_CENTER_URL of the
    environment (NEXT_PUBLIC_SELLER_CENTER_URL is still read), resolved per request on the server. */
export function sellerCenterUrl(): string {
  return (env('SELLER_CENTER_URL') ?? env('NEXT_PUBLIC_SELLER_CENTER_URL') ?? 'http://localhost:3001').replace(/\/+$/, '');
}

/** Path the browser uses for every BFF call (same origin; the route handler proxies or mocks). */
export const BFF_BASE_PATH = '/api/bff';
