/* Where the JavaScript runs: Expo Go (the store client), our own native build (development build, internal or store
   release) or the web preview. Expo Go only carries Expo's own native set-up: since SDK 53 it has no remote push on
   Android (expo-notifications throws when a push token is requested), it registers no Face ID usage string, and it
   has no Apple Pay / Google Pay merchant entitlements or any of our config plugins. The app checks these
   capabilities instead of the environment, so Expo Go keeps working with plain fallbacks (passcode instead of Face
   ID, card entry instead of wallets, no push registration). docs/engineering/mobile-on-your-phone.md lists them. */
import Constants, { ExecutionEnvironment } from 'expo-constants';
import { Platform } from 'react-native';

export type Runtime = 'expo-go' | 'native-build' | 'web';

export interface Capabilities {
  runtime: Runtime;
  /** FCM/APNs device-token registration with identity (S168). A token from Expo Go belongs to Expo's app, not ours. */
  remotePush: boolean;
  /** Local notification APIs (presented notifications, channels, permission prompt). */
  localNotifications: boolean;
  /** Biometric unlock. iOS Face ID needs NSFaceIDUsageDescription from our build; Android biometrics work in Expo Go. */
  biometrics: boolean;
  /** Apple Pay / Google Pay in Stripe's PaymentSheet (merchant ids and entitlements come from our build). */
  walletPay: boolean;
}

/** Pure mapping of expo-constants' executionEnvironment (+ platform) to a runtime. */
export function runtimeOf(executionEnvironment: string | undefined, os: string): Runtime {
  if (os === 'web') return 'web';
  return executionEnvironment === ExecutionEnvironment.StoreClient ? 'expo-go' : 'native-build';
}

export function capabilitiesFor(runtime: Runtime, os: string): Capabilities {
  const native = runtime === 'native-build';
  return {
    runtime,
    remotePush: native,
    // Expo Go on Android (SDK 53+) throws while expo-notifications loads, so even local notifications need our build.
    localNotifications: native || (runtime === 'expo-go' && os === 'ios'),
    biometrics: native || (runtime === 'expo-go' && os === 'android'),
    walletPay: native
  };
}

export const capabilities: Capabilities = capabilitiesFor(runtimeOf(Constants.executionEnvironment, Platform.OS), Platform.OS);

export function isExpoGo(): boolean {
  return capabilities.runtime === 'expo-go';
}
