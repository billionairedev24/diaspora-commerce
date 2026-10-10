/* Expo app config for AfroTransact Delivery. EXPO_PUBLIC_APP_ENV is set per EAS build profile (eas.json). */
import { type ExpoConfig } from 'expo/config';

const APP_ENV = process.env.EXPO_PUBLIC_APP_ENV ?? 'local';
const SUFFIX = APP_ENV === 'prod' ? '' : `.${APP_ENV}`;
/** Must match identity's driver-app client: com.afrotransact.driver:/oauth2redirect. */
const SCHEME = 'com.afrotransact.driver';
const CAMERA = 'Take live proof-of-delivery and vehicle photos and scan package labels. Gallery photos are not accepted.';
const LOCATION = 'Stamp proof-of-delivery photos with GPS and check you are at the address (within 150 m).';

const config: ExpoConfig = {
  name: APP_ENV === 'prod' ? 'AfroTransact Delivery' : `AT Delivery ${APP_ENV.toUpperCase()}`,
  slug: 'afrotransact-driver',
  owner: process.env.EAS_OWNER ?? 'afrotransact',
  version: '2.0.0',
  scheme: SCHEME,
  orientation: 'portrait',
  userInterfaceStyle: 'light',
  // Native window colour must be a literal at build time: --at-color-canvas.
  backgroundColor: '#FFFFFF',
  ios: {
    bundleIdentifier: `com.afrotransact.driver${SUFFIX}`,
    supportsTablet: false,
    infoPlist: { NSCameraUsageDescription: CAMERA, NSLocationWhenInUseUsageDescription: LOCATION, ITSAppUsesNonExemptEncryption: false }
  },
  android: {
    package: `com.afrotransact.driver${SUFFIX}`,
    permissions: ['CAMERA', 'ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION', 'POST_NOTIFICATIONS'],
    // Proof photos come only from the live camera: no media-library access.
    blockedPermissions: ['android.permission.READ_MEDIA_IMAGES', 'android.permission.READ_EXTERNAL_STORAGE', 'android.permission.RECORD_AUDIO']
  },
  web: { bundler: 'metro', output: 'single' },
  plugins: [
    'expo-router',
    'expo-secure-store',
    'expo-web-browser',
    'expo-sqlite',
    ['expo-camera', { cameraPermission: CAMERA, recordAudioAndroid: false, barcodeScannerEnabled: true }],
    ['expo-location', { locationWhenInUsePermission: LOCATION }],
    ['expo-notifications', { enableBackgroundRemoteNotifications: false }]
  ],
  experiments: { typedRoutes: false },
  extra: { appEnv: APP_ENV, eas: { projectId: process.env.EAS_PROJECT_ID } }
};

export default config;
