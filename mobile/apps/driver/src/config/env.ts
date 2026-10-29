/* Environment configuration (see README). Every deployed URL comes from EXPO_PUBLIC_* variables that each EAS build
   profile sets in eas.json (hosts per environment: docs/engineering/environments.md); the code names no deployed host.
   Unset URLs fall back to the local stack on EXPO_PUBLIC_DEV_HOST (default localhost; the dev machine's LAN IP for a
   physical device, 10.0.2.2 for the Android emulator), ports from infra/local/PORTS.md. app.config.ts fails a
   dev/uat/prod build that leaves a URL unset (REQUIRED_URL_VARS). EXPO_PUBLIC_* values are inlined at build time, so
   they must be read with static property access. */

import { metroHost } from './devHost';

export type AppEnv = 'local' | 'dev' | 'uat' | 'prod';

export interface EnvConfig {
  appEnv: AppEnv;
  /** gateway-bff: the in-app sign-in (/v1/auth/native/*). */
  bffUrl: string;
  /** fulfilment-service (driver API: /v1/driver/**). */
  fulfilmentUrl: string;
  /** media-service (proof photos, signatures: POST /v1/uploads). */
  mediaUrl: string;
  /** identity: OIDC issuer and /v1/me/devices. */
  oidcIssuer: string;
  identityApiUrl: string;
  oidcClientId: string;
  redirectScheme: string;
  mocks: boolean;
}

type Urls = Pick<EnvConfig, 'bffUrl' | 'fulfilmentUrl' | 'mediaUrl' | 'oidcIssuer' | 'identityApiUrl'>;

/** The local stack on the dev machine (infra/local/PORTS.md): gateway-bff 8200, fulfilment 8108, media 8207, identity 8100. */
export function localUrls(devHost = 'localhost'): Urls {
  const h = devHost.trim() || 'localhost';
  return { bffUrl: `http://${h}:8200`, fulfilmentUrl: `http://${h}:8108`, mediaUrl: `http://${h}:8207`, oidcIssuer: `http://${h}:8100`, identityApiUrl: `http://${h}:8100` };
}

/** URL variables a dev/uat/prod build must set (checked by app.config.ts). */
export const REQUIRED_URL_VARS = ['EXPO_PUBLIC_BFF_URL', 'EXPO_PUBLIC_FULFILMENT_URL', 'EXPO_PUBLIC_MEDIA_URL', 'EXPO_PUBLIC_OIDC_ISSUER', 'EXPO_PUBLIC_IDENTITY_URL'] as const;

export interface RawEnv {
  appEnv?: string;
  bffUrl?: string;
  fulfilmentUrl?: string;
  mediaUrl?: string;
  oidcIssuer?: string;
  identityApiUrl?: string;
  oidcClientId?: string;
  mocks?: string;
  /** Host of the local stack (EXPO_PUBLIC_DEV_HOST): localhost, a LAN IP or 10.0.2.2. */
  devHost?: string;
}

const isAppEnv = (v: string | undefined): v is AppEnv => v === 'local' || v === 'dev' || v === 'uat' || v === 'prod';

export function resolveEnv(raw: RawEnv): EnvConfig {
  const appEnv: AppEnv = isAppEnv(raw.appEnv) ? raw.appEnv : 'local';
  const d = localUrls(raw.devHost);
  const pick = (v: string | undefined, f: string) => (v && v.trim() ? v.trim() : f);
  const url = (v: string | undefined, f: string) => pick(v, f).replace(/\/+$/, '');
  return {
    appEnv,
    bffUrl: url(raw.bffUrl, d.bffUrl),
    fulfilmentUrl: url(raw.fulfilmentUrl, d.fulfilmentUrl),
    mediaUrl: url(raw.mediaUrl, d.mediaUrl),
    oidcIssuer: url(raw.oidcIssuer, d.oidcIssuer),
    identityApiUrl: url(raw.identityApiUrl, d.identityApiUrl),
    oidcClientId: pick(raw.oidcClientId, 'driver-app'),
    redirectScheme: 'com.afrotransact.driver',
    mocks: appEnv !== 'prod' && (raw.mocks === undefined ? appEnv === 'local' : raw.mocks === '1' || raw.mocks === 'true')
  };
}

export const env: EnvConfig = resolveEnv({
  appEnv: process.env.EXPO_PUBLIC_APP_ENV,
  bffUrl: process.env.EXPO_PUBLIC_BFF_URL,
  fulfilmentUrl: process.env.EXPO_PUBLIC_FULFILMENT_URL,
  mediaUrl: process.env.EXPO_PUBLIC_MEDIA_URL,
  oidcIssuer: process.env.EXPO_PUBLIC_OIDC_ISSUER,
  identityApiUrl: process.env.EXPO_PUBLIC_IDENTITY_URL,
  oidcClientId: process.env.EXPO_PUBLIC_OIDC_CLIENT_ID,
  mocks: process.env.EXPO_PUBLIC_MOCKS,
  // An explicit EXPO_PUBLIC_DEV_HOST wins; otherwise a development phone uses the machine that serves its JS.
  devHost: process.env.EXPO_PUBLIC_DEV_HOST || metroHost()
});
