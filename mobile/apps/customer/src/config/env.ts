/* Environment configuration. EAS build profiles (eas.json) and `.env.<env>` files set EXPO_PUBLIC_* variables;
   anything unset falls back to the defaults of the selected EXPO_PUBLIC_APP_ENV (local | dev | uat | prod).
   EXPO_PUBLIC_* values are inlined at build time, so they must be read with static property access. */

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

const DEFAULTS: Record<AppEnv, Omit<EnvConfig, 'appEnv' | 'mocks' | 'oidcClientId' | 'redirectScheme' | 'stripePublishableKey' | 'stripeMerchantId'>> = {
  local: { webUrl: 'http://localhost:3000', bffUrl: 'http://localhost:8200', oidcIssuer: 'http://localhost:8100', identityApiUrl: 'http://localhost:8100', bookingsUrl: 'http://localhost:8109' },
  dev: { webUrl: 'https://www.dev.afrotransact.com', bffUrl: 'https://api.dev.afrotransact.com/bff', oidcIssuer: 'https://auth.dev.afrotransact.com', identityApiUrl: 'https://api.dev.afrotransact.com/identity', bookingsUrl: 'https://api.dev.afrotransact.com/bookings' },
  uat: { webUrl: 'https://www.uat.afrotransact.com', bffUrl: 'https://api.uat.afrotransact.com/bff', oidcIssuer: 'https://auth.uat.afrotransact.com', identityApiUrl: 'https://api.uat.afrotransact.com/identity', bookingsUrl: 'https://api.uat.afrotransact.com/bookings' },
  prod: { webUrl: 'https://www.afrotransact.com', bffUrl: 'https://api.afrotransact.com/bff', oidcIssuer: 'https://auth.afrotransact.com', identityApiUrl: 'https://api.afrotransact.com/identity', bookingsUrl: 'https://api.afrotransact.com/bookings' }
};

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
}

/** Pure resolver (unit-tested): defaults per environment, overridden by any variable that is set. */
export function resolveEnv(raw: RawEnv): EnvConfig {
  const appEnv: AppEnv = isAppEnv(raw.appEnv) ? raw.appEnv : 'local';
  const d = DEFAULTS[appEnv];
  const pick = (v: string | undefined, fallback: string) => (v && v.trim() ? v.trim() : fallback);
  return {
    appEnv,
    bffUrl: pick(raw.bffUrl, d.bffUrl),
    oidcIssuer: pick(raw.oidcIssuer, d.oidcIssuer),
    identityApiUrl: pick(raw.identityApiUrl, d.identityApiUrl),
    bookingsUrl: pick(raw.bookingsUrl, d.bookingsUrl),
    webUrl: pick(raw.webUrl, d.webUrl),
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
  mocks: process.env.EXPO_PUBLIC_MOCKS
});
