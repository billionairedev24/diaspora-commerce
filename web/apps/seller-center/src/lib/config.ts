/* Runtime configuration. Server values use bracket access so they resolve per request, not at build time. */

const env = (name: string): string | undefined => {
  const v = process.env[name];
  return v === undefined || v === '' ? undefined : v;
};

/** True when the Seller Center should use the in-process MSW mock BFF (NEXT_PUBLIC_API_MOCKS=1 or API_MOCKS=1). */
export function mocksEnabled(): boolean {
  return env('NEXT_PUBLIC_API_MOCKS') === '1' || env('API_MOCKS') === '1';
}

/** Gateway BFF origin for the /api/bff proxy (e.g. http://localhost:8200). */
export function bffOrigin(): string {
  return env('BFF_ORIGIN') ?? 'http://localhost:8200';
}

/** Storefront origin (sign-in, public pages). */
export function storefrontUrl(): string {
  return process.env.NEXT_PUBLIC_STOREFRONT_URL ?? 'http://localhost:3000';
}

/** Path the browser uses for every BFF call (same origin; the route handler proxies or mocks). */
export const BFF_BASE_PATH = '/api/bff';
