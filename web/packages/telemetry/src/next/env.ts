import { resolveTelemetryConfig, type TelemetryConfig } from '../index';

/**
 * Browser-side telemetry config. NEXT_PUBLIC_* values are inlined at build time, so they are read with static
 * property access. The DSN, environment and release come from the deploy environment (local, dev, uat, prod).
 */
export function browserTelemetryConfig(service: string): TelemetryConfig {
  return resolveTelemetryConfig(service, {
    dsn: process.env.NEXT_PUBLIC_SENTRY_DSN,
    environment: process.env.NEXT_PUBLIC_APP_ENV,
    release: process.env.NEXT_PUBLIC_RELEASE,
    tracesSampleRate: process.env.NEXT_PUBLIC_SENTRY_TRACES_SAMPLE_RATE,
    otlpEndpoint: process.env.NEXT_PUBLIC_OTEL_EXPORTER_OTLP_ENDPOINT
  });
}

/** Server-side config: SENTRY_DSN / APP_ENV / RELEASE override the public values at runtime. */
export function serverTelemetryConfig(service: string): TelemetryConfig {
  return resolveTelemetryConfig(service, {
    dsn: process.env.SENTRY_DSN ?? process.env.NEXT_PUBLIC_SENTRY_DSN,
    environment: process.env.APP_ENV ?? process.env.NEXT_PUBLIC_APP_ENV,
    release: process.env.RELEASE ?? process.env.NEXT_PUBLIC_RELEASE,
    tracesSampleRate: process.env.SENTRY_TRACES_SAMPLE_RATE ?? process.env.NEXT_PUBLIC_SENTRY_TRACES_SAMPLE_RATE
  });
}
