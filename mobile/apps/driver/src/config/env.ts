/* Environment configuration (see README). EXPO_PUBLIC_* values are inlined at build time; unset ones fall back to the
   defaults of EXPO_PUBLIC_APP_ENV (local | dev | uat | prod). */

export type AppEnv = 'local' | 'dev' | 'uat' | 'prod';

export interface EnvConfig {
  appEnv: AppEnv;
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

type Urls = Pick<EnvConfig, 'fulfilmentUrl' | 'mediaUrl' | 'oidcIssuer' | 'identityApiUrl'>;

const DEFAULTS: Record<AppEnv, Urls> = {
  local: { fulfilmentUrl: 'http://localhost:8108', mediaUrl: 'http://localhost:8207', oidcIssuer: 'http://localhost:8100', identityApiUrl: 'http://localhost:8100' },
  dev: { fulfilmentUrl: 'https://api.dev.afrotransact.com/fulfilment', mediaUrl: 'https://api.dev.afrotransact.com/media', oidcIssuer: 'https://auth.dev.afrotransact.com', identityApiUrl: 'https://api.dev.afrotransact.com/identity' },
  uat: { fulfilmentUrl: 'https://api.uat.afrotransact.com/fulfilment', mediaUrl: 'https://api.uat.afrotransact.com/media', oidcIssuer: 'https://auth.uat.afrotransact.com', identityApiUrl: 'https://api.uat.afrotransact.com/identity' },
  prod: { fulfilmentUrl: 'https://api.afrotransact.com/fulfilment', mediaUrl: 'https://api.afrotransact.com/media', oidcIssuer: 'https://auth.afrotransact.com', identityApiUrl: 'https://api.afrotransact.com/identity' }
};

export interface RawEnv {
  appEnv?: string;
  fulfilmentUrl?: string;
  mediaUrl?: string;
  oidcIssuer?: string;
  identityApiUrl?: string;
  oidcClientId?: string;
  mocks?: string;
}

const isAppEnv = (v: string | undefined): v is AppEnv => v === 'local' || v === 'dev' || v === 'uat' || v === 'prod';

export function resolveEnv(raw: RawEnv): EnvConfig {
  const appEnv: AppEnv = isAppEnv(raw.appEnv) ? raw.appEnv : 'local';
  const d = DEFAULTS[appEnv];
  const pick = (v: string | undefined, f: string) => (v && v.trim() ? v.trim() : f);
  return {
    appEnv,
    fulfilmentUrl: pick(raw.fulfilmentUrl, d.fulfilmentUrl),
    mediaUrl: pick(raw.mediaUrl, d.mediaUrl),
    oidcIssuer: pick(raw.oidcIssuer, d.oidcIssuer),
    identityApiUrl: pick(raw.identityApiUrl, d.identityApiUrl),
    oidcClientId: pick(raw.oidcClientId, 'driver-app'),
    redirectScheme: 'com.afrotransact.driver',
    mocks: appEnv !== 'prod' && (raw.mocks === undefined ? appEnv === 'local' : raw.mocks === '1' || raw.mocks === 'true')
  };
}

export const env: EnvConfig = resolveEnv({
  appEnv: process.env.EXPO_PUBLIC_APP_ENV,
  fulfilmentUrl: process.env.EXPO_PUBLIC_FULFILMENT_URL,
  mediaUrl: process.env.EXPO_PUBLIC_MEDIA_URL,
  oidcIssuer: process.env.EXPO_PUBLIC_OIDC_ISSUER,
  identityApiUrl: process.env.EXPO_PUBLIC_IDENTITY_URL,
  oidcClientId: process.env.EXPO_PUBLIC_OIDC_CLIENT_ID,
  mocks: process.env.EXPO_PUBLIC_MOCKS
});
