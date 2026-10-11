/* Face ID / Touch ID / Android biometrics via expo-local-authentication. In Expo Go on iOS Face ID is unavailable (our
   NSFaceIDUsageDescription only exists in our own build): biometrics then report unavailable and the app's own
   passcode is the unlock, exactly as on a phone without biometrics. */
import * as LocalAuthentication from 'expo-local-authentication';

import { capabilities } from '@/platform/runtime';

export async function biometricsAvailable(): Promise<boolean> {
  if (!capabilities.biometrics) return false;
  try {
    return (await LocalAuthentication.hasHardwareAsync()) && (await LocalAuthentication.isEnrolledAsync());
  } catch {
    return false;
  }
}

export async function biometricLabel(): Promise<string> {
  try {
    const types = await LocalAuthentication.supportedAuthenticationTypesAsync();
    if (types.includes(LocalAuthentication.AuthenticationType.FACIAL_RECOGNITION)) return 'Face ID';
    if (types.includes(LocalAuthentication.AuthenticationType.FINGERPRINT)) return 'Touch ID';
  } catch {
    /* fall through */
  }
  return 'Face ID';
}

/** Prompts for biometrics only (no device-passcode fallback: the app has its own passcode). */
export async function authenticateBiometric(promptMessage: string): Promise<boolean> {
  if (!capabilities.biometrics) return false;
  try {
    const r = await LocalAuthentication.authenticateAsync({ promptMessage, disableDeviceFallback: true, cancelLabel: 'Use passcode' });
    return r.success;
  } catch {
    return false;
  }
}
