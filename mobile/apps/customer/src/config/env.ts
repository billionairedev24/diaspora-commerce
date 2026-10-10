/* Environment configuration. Every deployed URL comes from EXPO_PUBLIC_* variables that each EAS build profile sets in
   eas.json (hosts per environment: docs/engineering/environments.md); the code names no deployed host. Unset URLs fall
   back to the local stack on EXPO_PUBLIC_DEV_HOST (default localhost; set it to the dev machine's LAN IP for a physical
   device, 10.0.2.2 for the Android emulator), ports from infra/local/PORTS.md. app.config.ts fails a dev/uat/prod
   build that leaves a URL unset (REQUIRED_URL_VARS). EXPO_PUBLIC_* values are inlined at build time, so they must be
   read with static property access. */

export type AppEnv = 'local' | 'dev' | 'uat' | 'prod';

export interface EnvConfig {
  appEnv: AppEnv;
  /** Gateway BFF (screen-shaped API, bearer tokens verified via identity JWKS). */
  bffUrl: string;
  /** Identity (Spring Authorization Server): OIDC issuer and the /v1/me/* account APIs (devices, notification preferences). */
  oidcIssuer: string;
  identityApiUrl: string;
  /** Bookings service (customer endpoints are not proxied by the BFF yet). */
  bookingsUrl: string;
  /** Storefront (legal pages, password reset, account creation until identity hosts them). */
  webUrl: string;
  oidcClientId: string;
  /** Native redirect: <scheme>:/oauth2redirect (registered on identity's customer-app client). */
  redirectScheme: string;
  stripePublishableKey: string;
  stripeMerchantId: string;
  /** In-app mock BFF/identity/bookings and a mock payment sheet — local development without a backend. */
  mocks: boolean;
}

type Urls = Pick<EnvConfig, 'webUrl' | 'bffUrl' | 'oidcIssuer' | 'identityApiUrl' | 'bookingsUrl'>;

/** The local stack on the dev machine (infra/local/PORTS.md): storefront 3000, gateway-bff 8200, identity 8100, bookings 8109. */
export function localUrls(devHost = 'localhost'): Urls {
  const h = devHost.trim() || 'localhost';
  return { webUrl: `http://${h}:3000`, bffUrl: `http://${h}:8200`, oidcIssuer: `http://${h}:8100`, identityApiUrl: `http://${h}:8100`, bookingsUrl: `http://${h}:8109` };
}

/** URL variables a dev/uat/prod build must set (checked by app.config.ts). */
export const REQUIRED_URL_VARS = ['EXPO_PUBLIC_WEB_URL', 'EXPO_PUBLIC_BFF_URL', 'EXPO_PUBLIC_OIDC_ISSUER', 'EXPO_PUBLIC_IDENTITY_URL', 'EXPO_PUBLIC_BOOKINGS_URL'] as const;

export function isAppEnv(v: string | undefined): v is AppEnv {
  return v === 'local' || v === 'dev' || v === 'uat' || v === 'prod';
}

export interface RawEnv {
  appEnv?: string;
  bffUrl?: string;
  oidcIssuer?: string;
  identityApiUrl?: string;
  bookingsUrl?: string;
  webUrl?: string;
  oidcClientId?: string;
  stripePublishableKey?: string;
  stripeMerchantId?: string;
  mocks?: string;
  /** Host of the local stack (EXPO_PUBLIC_DEV_HOST): localhost, a LAN IP or 10.0.2.2. */
  devHost?: string;
}

/** Pure resolver (unit-tested): the variables that are set, else the local stack on devHost. */
export function resolveEnv(raw: RawEnv): EnvConfig {
  const appEnv: AppEnv = isAppEnv(raw.appEnv) ? raw.appEnv : 'local';
  const d = localUrls(raw.devHost);
  const pick = (v: string | undefined, fallback: string) => (v && v.trim() ? v.trim() : fallback);
  const url = (v: string | undefined, fallback: string) => pick(v, fallback).replace(/\/+$/, '');
  return {
    appEnv,
    bffUrl: url(raw.bffUrl, d.bffUrl),
    oidcIssuer: url(raw.oidcIssuer, d.oidcIssuer),
    identityApiUrl: url(raw.identityApiUrl, d.identityApiUrl),
    bookingsUrl: url(raw.bookingsUrl, d.bookingsUrl),
    webUrl: url(raw.webUrl, d.webUrl),
    oidcClientId: pick(raw.oidcClientId, 'customer-app'),
    redirectScheme: 'com.afrotransact.customer',
    stripePublishableKey: pick(raw.stripePublishableKey, 'pk_test_mock'),
    stripeMerchantId: pick(raw.stripeMerchantId, 'merchant.com.afrotransact'),
    // Mocks default on for local only; prod can never run on mocks.
    mocks: appEnv !== 'prod' && (raw.mocks === undefined ? appEnv === 'local' : raw.mocks === '1' || raw.mocks === 'true')
  };
}

export const env: EnvConfig = resolveEnv({
  appEnv: process.env.EXPO_PUBLIC_APP_ENV,
  bffUrl: process.env.EXPO_PUBLIC_BFF_URL,
  oidcIssuer: process.env.EXPO_PUBLIC_OIDC_ISSUER,
  identityApiUrl: process.env.EXPO_PUBLIC_IDENTITY_URL,
  bookingsUrl: process.env.EXPO_PUBLIC_BOOKINGS_URL,
  webUrl: process.env.EXPO_PUBLIC_WEB_URL,
  oidcClientId: process.env.EXPO_PUBLIC_OIDC_CLIENT_ID,
  stripePublishableKey: process.env.EXPO_PUBLIC_STRIPE_PUBLISHABLE_KEY,
  stripeMerchantId: process.env.EXPO_PUBLIC_STRIPE_MERCHANT_ID,
  mocks: process.env.EXPO_PUBLIC_MOCKS,
  devHost: process.env.EXPO_PUBLIC_DEV_HOST
});
