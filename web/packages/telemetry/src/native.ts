/*
 * Mobile (Expo / React Native) bootstrap. The app passes its own @sentry/react-native module so the native SDK is
 * linked by the app (Expo autolinking) and this package carries no React Native dependency:
 *
 *   import * as Sentry from '@sentry/react-native';
 *   initNativeTelemetry(Sentry, nativeTelemetryConfig('customer-app', {...}));
 *
 * Off unless EXPO_PUBLIC_SENTRY_DSN is set for the build profile.
 */
import { type RawTelemetryEnv, resolveTelemetryConfig, setTelemetry, type Telemetry, type TelemetryConfig } from './index';
import { baseInitOptions, type SentryLike, sentryTelemetry } from './sdk';

export interface NativeSentryLike extends SentryLike {
  init(options: Record<string, unknown>): unknown;
}

export function nativeTelemetryConfig(service: string, raw: RawTelemetryEnv): TelemetryConfig {
  return resolveTelemetryConfig(service, raw);
}

/** Initialises the SDK when a DSN is set and installs the implementation; returns whether tracking is on. */
export function initNativeTelemetry(sdk: NativeSentryLike, config: TelemetryConfig, extra: Record<string, unknown> = {}): Telemetry | undefined {
  if (!config.dsn) return undefined;
  try {
    sdk.init({
      ...baseInitOptions(config),
      // Screenshots and view hierarchy can contain addresses, names and phone numbers.
      attachScreenshot: false,
      attachViewHierarchy: false,
      enableAutoSessionTracking: true,
      ...extra
    });
    const t = sentryTelemetry(sdk);
    setTelemetry(t);
    return t;
  } catch {
    return undefined;
  }
}
