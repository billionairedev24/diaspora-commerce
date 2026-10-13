/* Where the JavaScript runs: Expo Go (the store client), our own native build (development build, internal or store
   release) or the web preview. Expo Go only carries Expo's own native set-up: since SDK 53 it has no remote push on
   Android (expo-notifications throws when a push token is requested) and none of our config plugins. The camera
   (live photos, barcode scanning), foreground location, SQLite and secure storage all work in Expo Go; the app
   never asks for background location. docs/engineering/mobile-on-your-phone.md lists the differences. */
import Constants, { ExecutionEnvironment } from 'expo-constants';
import { Platform } from 'react-native';

export type Runtime = 'expo-go' | 'native-build' | 'web';

export interface Capabilities {
  runtime: Runtime;
  /** FCM/APNs device-token registration with identity. A token from Expo Go belongs to Expo's app, not ours. */
  remotePush: boolean;
  /** Local notification APIs (permission prompt, channels). */
  localNotifications: boolean;
}

/** Pure mapping of expo-constants' executionEnvironment (+ platform) to a runtime. */
export function runtimeOf(executionEnvironment: string | undefined, os: string): Runtime {
  if (os === 'web') return 'web';
  return executionEnvironment === ExecutionEnvironment.StoreClient ? 'expo-go' : 'native-build';
}

export function capabilitiesFor(runtime: Runtime, os: string = 'android'): Capabilities {
  // Expo Go on Android (SDK 53+) throws while expo-notifications loads, so even local notifications need our build.
  return { runtime, remotePush: runtime === 'native-build', localNotifications: runtime === 'native-build' || (runtime === 'expo-go' && os === 'ios') };
}

export const capabilities: Capabilities = capabilitiesFor(runtimeOf(Constants.executionEnvironment, Platform.OS), Platform.OS);

export function isExpoGo(): boolean {
  return capabilities.runtime === 'expo-go';
}
