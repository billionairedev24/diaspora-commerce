/* Expo app config. EXPO_PUBLIC_APP_ENV (local | dev | uat | prod) is set per EAS build profile (eas.json). */
import { type ExpoConfig } from 'expo/config';

const APP_ENV = process.env.EXPO_PUBLIC_APP_ENV ?? 'local';
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
    associatedDomains: ['applinks:www.afrotransact.com'],
    infoPlist: {
      NSFaceIDUsageDescription: 'Use Face ID to unlock AfroTransact and confirm payments over $100.',
      ITSAppUsesNonExemptEncryption: false
    }
  },
  android: {
    package: `com.afrotransact.customer${SUFFIX}`,
    permissions: ['USE_BIOMETRIC', 'USE_FINGERPRINT', 'POST_NOTIFICATIONS'],
    intentFilters: [
      { action: 'VIEW', autoVerify: true, data: [{ scheme: 'https', host: 'www.afrotransact.com', pathPrefix: '/app' }], category: ['BROWSABLE', 'DEFAULT'] }
    ]
  },
  web: { bundler: 'metro', output: 'single' },
  plugins: [
    'expo-router',
    'expo-secure-store',
    'expo-web-browser',
    ['expo-local-authentication', { faceIDPermission: 'Use Face ID to unlock AfroTransact and confirm payments over $100.' }],
    ['expo-notifications', { enableBackgroundRemoteNotifications: false }],
    ['@stripe/stripe-react-native', { merchantIdentifier: process.env.EXPO_PUBLIC_STRIPE_MERCHANT_ID ?? 'merchant.com.afrotransact', enableGooglePay: true }]
  ],
  experiments: { typedRoutes: false },
  extra: { appEnv: APP_ENV, eas: { projectId: process.env.EAS_PROJECT_ID } }
};

export default config;
