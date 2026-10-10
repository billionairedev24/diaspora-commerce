/* Error tracking config (S178). EXPO_PUBLIC_* values are inlined per EAS build profile (local | dev | uat | prod).
   Tracking stays off unless EXPO_PUBLIC_SENTRY_DSN is set; without EXPO_PUBLIC_RELEASE the SDK reports the native
   release (<bundle id>@<version>+<build>), so errors are grouped by release either way. */
import { type TelemetryConfig } from '@afrotransact/telemetry';
import { nativeTelemetryConfig } from '@afrotransact/telemetry/native';

export const telemetryConfig: TelemetryConfig = nativeTelemetryConfig('driver-app', {
  dsn: process.env.EXPO_PUBLIC_SENTRY_DSN,
  environment: process.env.EXPO_PUBLIC_APP_ENV,
  release: process.env.EXPO_PUBLIC_RELEASE,
  tracesSampleRate: process.env.EXPO_PUBLIC_SENTRY_TRACES_SAMPLE_RATE
});
