/* Runtime configuration. Server values are read with bracket access so they are resolved at request
   time (Node or Cloudflare Workers vars), not inlined at build time — one build serves every environment. */

const env = (name: string): string | undefined => {
  const v = process.env[name];
  return v === undefined || v === '' ? undefined : v;
};

/** True when the storefront should use the in-process MSW mock BFF (NEXT_PUBLIC_API_MOCKS=1 or API_MOCKS=1). */
export function mocksEnabled(): boolean {
  // Demo data never reaches a production or UAT deployment, whatever the flags say.
  if (['prod', 'production', 'uat'].includes(env('APP_ENV') ?? '')) return false;
  return env('NEXT_PUBLIC_API_MOCKS') === '1' || env('API_MOCKS') === '1';
}

/** Gateway BFF origin for the /api/bff proxy and server components (e.g. http://localhost:8200). */
export function bffOrigin(): string {
  return env('BFF_ORIGIN') ?? 'http://localhost:8200';
}

/** Seller Center & Provider Hub origin (Sell on AfroTransact / Offer your services): the shared SELLER_CENTER_URL of the
    environment (NEXT_PUBLIC_SELLER_CENTER_URL is still read), resolved per request on the server. */
export function sellerCenterUrl(): string {
  return (env('SELLER_CENTER_URL') ?? env('NEXT_PUBLIC_SELLER_CENTER_URL') ?? 'http://localhost:3010').replace(/\/+$/, '');
}

/** Staff console origin (account menu link for staff): the shared STAFF_CONSOLE_URL of the environment, resolved per
    request on the server. */
export function staffConsoleUrl(): string {
  return (env('STAFF_CONSOLE_URL') ?? 'http://localhost:3002').replace(/\/+$/, '');
}

/** The storefront's own canonical origin (PUBLIC_WEB_URL: the base host of the environment (the bare zone in prod,
    <suffix>.<zone> elsewhere, never www: infra/cloudflare/hosts.json, docs/engineering/environments.md). Base of canonical URLs and Open Graph URLs; undefined when
    unset or not an absolute URL (local runs), so relative metadata then resolves against the request. */
export function publicWebUrl(): URL | undefined {
  const raw = env('PUBLIC_WEB_URL');
  if (raw === undefined) return undefined;
  try {
    return new URL(raw);
  } catch {
    return undefined;
  }
}

/** Path the browser uses for every BFF call (same origin; the route handler proxies or mocks). */
export const BFF_BASE_PATH = '/api/bff';
