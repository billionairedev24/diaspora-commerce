import * as Sentry from '@sentry/nextjs';

import { type Telemetry, type TelemetryConfig, type WebVital } from '../index';
import { baseInitOptions, sentryTelemetry } from '../sdk';

/** Loaded lazily by ./client when a DSN is configured. */
export function startSentryBrowser(
  config: TelemetryConfig,
  onWebVital: (m: WebVital) => void
): { telemetry: Telemetry; onRouterTransitionStart: (href: string, type: string) => void } {
  Sentry.init({
    ...baseInitOptions(config),
    integrations: config.tracesSampleRate > 0 ? [Sentry.browserTracingIntegration()] : [],
    // Only our own API carries trace headers (never third-party origins).
    tracePropagationTargets: [/^\/api\/bff\//],
    // Session replay stays off: it would record PII.
    replaysSessionSampleRate: 0,
    replaysOnErrorSampleRate: 0
  });
  return { telemetry: sentryTelemetry(Sentry, onWebVital), onRouterTransitionStart: Sentry.captureRouterTransitionStart };
}
