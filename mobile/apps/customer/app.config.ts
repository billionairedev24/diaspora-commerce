/* Expo app config. EXPO_PUBLIC_APP_ENV (local | dev | uat | prod) and the EXPO_PUBLIC_* URLs are set per EAS build
   profile (eas.json); hosts per environment: docs/engineering/environments.md. */
import { type ExpoConfig } from 'expo/config';

const APP_ENV = process.env.EXPO_PUBLIC_APP_ENV ?? 'local';
const DEPLOYED = APP_ENV === 'dev' || APP_ENV === 'uat' || APP_ENV === 'prod';

/* A dev/uat/prod build must name its own hosts: src/config/env.ts would otherwise fall back to the local stack.
   Keep in sync with REQUIRED_URL_VARS there. */
const REQUIRED_URL_VARS = ['EXPO_PUBLIC_WEB_URL', 'EXPO_PUBLIC_BFF_URL', 'EXPO_PUBLIC_OIDC_ISSUER', 'EXPO_PUBLIC_IDENTITY_URL', 'EXPO_PUBLIC_BOOKINGS_URL'];
if (DEPLOYED) {
  const missing = REQUIRED_URL_VARS.filter((name) => !process.env[name]?.trim());
  if (missing.length) throw new Error(`app.config: EXPO_PUBLIC_APP_ENV=${APP_ENV} needs ${missing.join(', ')} (set them in the eas.json build profile)`);
}

/* Universal links (iOS) / verified app links (Android) on the environment's storefront: https://<web host>/app/….
   The storefront serves apple-app-site-association and assetlinks.json for it. Local builds have none. */
const WEB_HOST = DEPLOYED ? new URL(process.env.EXPO_PUBLIC_WEB_URL ?? '').host : undefined;
const SUFFIX = APP_ENV === 'prod' ? '' : `.${APP_ENV}`;
/** Must match identity's customer-app client: com.afrotransact.customer:/oauth2redirect. */
const SCHEME = 'com.afrotransact.customer';

const config: ExpoConfig = {
  name: APP_ENV === 'prod' ? 'AfroTransact' : `AfroTransact ${APP_ENV.toUpperCase()}`,
  slug: 'afrotransact-customer',
  owner: process.env.EAS_OWNER ?? 'afrotransact',
  version: '2.0.0',
  scheme: SCHEME,
  orientation: 'portrait',
  userInterfaceStyle: 'light',
  // Native window colour must be a literal at build time: --at-color-canvas.
  backgroundColor: '#FFFFFF',
  ios: {
    bundleIdentifier: `com.afrotransact.customer${SUFFIX}`,
    supportsTablet: false,
    associatedDomains: WEB_HOST ? [`applinks:${WEB_HOST}`] : [],
    infoPlist: {
      NSFaceIDUsageDescription: 'Use Face ID to unlock AfroTransact and confirm payments over $100.',
      ITSAppUsesNonExemptEncryption: false
    }
  },
  android: {
    package: `com.afrotransact.customer${SUFFIX}`,
    permissions: ['USE_BIOMETRIC', 'USE_FINGERPRINT', 'POST_NOTIFICATIONS'],
    intentFilters: WEB_HOST
      ? [{ action: 'VIEW', autoVerify: true, data: [{ scheme: 'https', host: WEB_HOST, pathPrefix: '/app' }], category: ['BROWSABLE', 'DEFAULT'] }]
      : []
  },
  web: { bundler: 'metro', output: 'single' },
  plugins: [
    'expo-router',
    'expo-secure-store',
    'expo-web-browser',
    ['expo-local-authentication', { faceIDPermission: 'Use Face ID to unlock AfroTransact and confirm payments over $100.' }],
    ['expo-notifications', { enableBackgroundRemoteNotifications: false }],
    // Source maps + native debug symbols upload during EAS builds only when SENTRY_AUTH_TOKEN is set (CI secret);
    // SENTRY_ORG / SENTRY_PROJECT / SENTRY_URL name the (Sentry-compatible) project. Without a token nothing is uploaded.
    ['@sentry/react-native/expo', { organization: process.env.SENTRY_ORG, project: process.env.SENTRY_PROJECT, url: process.env.SENTRY_URL }],
    ['@stripe/stripe-react-native', { merchantIdentifier: process.env.EXPO_PUBLIC_STRIPE_MERCHANT_ID ?? 'merchant.com.afrotransact', enableGooglePay: true }]
  ],
  experiments: { typedRoutes: false },
  extra: { appEnv: APP_ENV, eas: { projectId: process.env.EAS_PROJECT_ID } }
};

export default config;
