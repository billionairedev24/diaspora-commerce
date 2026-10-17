/* Expo app config for AfroTransact Delivery. EXPO_PUBLIC_APP_ENV and the EXPO_PUBLIC_* URLs are set per EAS build
   profile (eas.json); hosts per environment: docs/engineering/environments.md. */
import { type ExpoConfig } from 'expo/config';

const APP_ENV = process.env.EXPO_PUBLIC_APP_ENV ?? 'local';

/* A dev/uat/prod build must name its own hosts: src/config/env.ts would otherwise fall back to the local stack.
   Keep in sync with REQUIRED_URL_VARS there. */
const REQUIRED_URL_VARS = ['EXPO_PUBLIC_BFF_URL', 'EXPO_PUBLIC_FULFILMENT_URL', 'EXPO_PUBLIC_MEDIA_URL', 'EXPO_PUBLIC_OIDC_ISSUER', 'EXPO_PUBLIC_IDENTITY_URL'];
if (APP_ENV === 'dev' || APP_ENV === 'uat' || APP_ENV === 'prod') {
  const missing = REQUIRED_URL_VARS.filter((name) => !process.env[name]?.trim());
  if (missing.length) throw new Error(`app.config: EXPO_PUBLIC_APP_ENV=${APP_ENV} needs ${missing.join(', ')} (set them in the eas.json build profile)`);
}
const SUFFIX = APP_ENV === 'prod' ? '' : `.${APP_ENV}`;
/** Must match identity's driver-app client: com.afrotransact.driver:/oauth2redirect. */
const SCHEME = 'com.afrotransact.driver';

/* Brand assets (pnpm brand regenerates them from design/assets/logo-mark.svg: mobile/scripts/brand-assets.mjs). The
   launcher name is the product name in every profile: the environment lives in the bundle id / package suffix (so
   builds can sit side by side) and in a small ribbon inside the app, never in the launcher label or the header. */
// Native window and splash colour must be literals at build time: --at-ink-1000 (the welcome screens are black).
const BRAND_BG = '#000000';
const SPLASH = {
  image: './assets/images/splash-icon.png',
  imageWidth: 132,
  resizeMode: 'contain',
  backgroundColor: BRAND_BG,
  dark: { image: './assets/images/splash-icon.png', backgroundColor: BRAND_BG }
} as const;

/* Cleartext HTTP to the local stack on the Mac's LAN IP (make up MOBILE_HOST=lan), for local and dev builds only —
   uat/prod builds keep the platform defaults (HTTPS only). Android: usesCleartextTraffic (expo-build-properties);
   iOS: ATS local networking plus the Local Network prompt text. Expo Go allows HTTP on its own. */
const LOCAL_NETWORK = APP_ENV === 'local' || APP_ENV === 'dev';
const LOCAL_NETWORK_PLIST = LOCAL_NETWORK
  ? { NSAppTransportSecurity: { NSAllowsLocalNetworking: true }, NSLocalNetworkUsageDescription: 'Connect to the AfroTransact development stack on your network.' }
  : {};
const LOCAL_NETWORK_PLUGINS: NonNullable<ExpoConfig['plugins']> = LOCAL_NETWORK ? [['expo-build-properties', { android: { usesCleartextTraffic: true } }]] : [];
const CAMERA = 'Take live proof-of-delivery and vehicle photos and scan package labels. Gallery photos are not accepted.';
const LOCATION = 'Stamp proof-of-delivery photos with GPS and check you are at the address (within 150 m).';

const config: ExpoConfig = {
  name: 'AfroTransact Delivery',
  slug: 'afrotransact-driver',
  owner: process.env.EAS_OWNER ?? 'afrotransact',
  version: '2.0.0',
  scheme: SCHEME,
  orientation: 'portrait',
  userInterfaceStyle: 'light',
  icon: './assets/images/icon.png',
  backgroundColor: BRAND_BG,
  ios: {
    bundleIdentifier: `com.afrotransact.driver${SUFFIX}`,
    icon: './assets/images/icon.png',
    supportsTablet: false,
    infoPlist: { NSCameraUsageDescription: CAMERA, NSLocationWhenInUseUsageDescription: LOCATION, ITSAppUsesNonExemptEncryption: false, ...LOCAL_NETWORK_PLIST }
  },
  android: {
    package: `com.afrotransact.driver${SUFFIX}`,
    // FCM push (Android): the Firebase app's google-services.json, an EAS file variable (docs/engineering/secrets-and-configuration.md).
    ...(process.env.GOOGLE_SERVICES_JSON ? { googleServicesFile: process.env.GOOGLE_SERVICES_JSON } : {}),
    adaptiveIcon: { foregroundImage: './assets/images/adaptive-icon.png', monochromeImage: './assets/images/adaptive-monochrome.png', backgroundColor: BRAND_BG },
    permissions: ['CAMERA', 'ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION', 'POST_NOTIFICATIONS'],
    // Proof photos come only from the live camera: no media-library access.
    blockedPermissions: ['android.permission.READ_MEDIA_IMAGES', 'android.permission.READ_EXTERNAL_STORAGE', 'android.permission.RECORD_AUDIO']
  },
  web: { bundler: 'metro', output: 'single', favicon: './assets/images/favicon.png' },
  plugins: [
    'expo-router',
    ['expo-splash-screen', SPLASH],
    ...LOCAL_NETWORK_PLUGINS,
    'expo-secure-store',
    'expo-web-browser',
    'expo-sqlite',
    ['expo-camera', { cameraPermission: CAMERA, recordAudioAndroid: false, barcodeScannerEnabled: true }],
    ['expo-location', { locationWhenInUsePermission: LOCATION }],
    ['expo-notifications', { enableBackgroundRemoteNotifications: false }],
    // Source maps + native debug symbols upload during EAS builds only when SENTRY_AUTH_TOKEN is set (CI secret);
    // SENTRY_ORG / SENTRY_PROJECT / SENTRY_URL name the (Sentry-compatible) project. Without a token nothing is uploaded.
    ['@sentry/react-native/expo', { organization: process.env.SENTRY_ORG, project: process.env.SENTRY_PROJECT, url: process.env.SENTRY_URL }]
  ],
  experiments: { typedRoutes: false },
  extra: { appEnv: APP_ENV, eas: { projectId: process.env.EAS_PROJECT_ID } }
};

export default config;
