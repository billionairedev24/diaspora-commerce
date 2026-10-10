/* Runtime configuration. Server values use bracket access so they resolve per request, not at build time. */

const env = (name: string): string | undefined => {
  const v = process.env[name];
  return v === undefined || v === '' ? undefined : v;
};

/** True when the staff console should use the in-process MSW mock BFF (NEXT_PUBLIC_API_MOCKS=1 or API_MOCKS=1). */
export function mocksEnabled(): boolean {
  return env('NEXT_PUBLIC_API_MOCKS') === '1' || env('API_MOCKS') === '1';
}

/** Gateway BFF origin for the /api/bff proxy (e.g. http://localhost:8200). */
export function bffOrigin(): string {
  return env('BFF_ORIGIN') ?? 'http://localhost:8200';
}

/** Local default of the storefront origin (infra/local/PORTS.md). */
export const DEFAULT_STOREFRONT_URL = 'http://localhost:3000';

/** Storefront origin (staff sign in with the same AfroTransact sign-in page): the shared PUBLIC_WEB_URL of the environment (NEXT_PUBLIC_STOREFRONT_URL is still
    read), resolved on the server per request so one build serves every environment. Client components read it with
    useStorefrontUrl() (src/lib/runtime-config.tsx); the root layout passes this value down. */
export function storefrontUrl(): string {
  return (env('PUBLIC_WEB_URL') ?? env('NEXT_PUBLIC_STOREFRONT_URL') ?? DEFAULT_STOREFRONT_URL).replace(/\/+$/, '');
}

/** Path the browser uses for every BFF call (same origin; the route handler proxies or mocks). */
export const BFF_BASE_PATH = '/api/bff';
