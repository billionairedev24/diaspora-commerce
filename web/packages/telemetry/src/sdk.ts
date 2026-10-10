/*
 * Adapter from a Sentry(-compatible) SDK to the Telemetry interface. The SDK is passed in structurally, so the same
 * adapter serves @sentry/nextjs (browser, Node, edge) and @sentry/react-native without importing either here.
 */
import { type Breadcrumb, type CaptureContext, type Severity, type Telemetry, type TelemetryConfig, type TelemetryUser, type WebVital } from './index';
import { scrubEvent, scrubString, scrubValue } from './scrub';

/** The subset of the Sentry SDK API the adapter uses (shared by @sentry/nextjs and @sentry/react-native). */
export interface SentryLike {
  captureException(exception: unknown, hint?: { tags?: Record<string, string>; extra?: Record<string, unknown>; level?: Severity }): string;
  captureMessage(message: string, hint?: { tags?: Record<string, string>; extra?: Record<string, unknown>; level?: Severity }): string;
  addBreadcrumb(crumb: { category?: string; message?: string; level?: Severity; data?: Record<string, unknown> }): void;
  setUser(user: { id: string } | null): void;
  setTag(key: string, value: string): void;
  flush(timeout?: number): PromiseLike<boolean>;
}

/** beforeSend / beforeSendTransaction / beforeBreadcrumb hooks that scrub PII. */
export const scrubHooks = {
  beforeSend: <E>(event: E): E => scrubEvent(event),
  beforeSendTransaction: <E>(event: E): E => scrubEvent(event),
  beforeBreadcrumb: <B extends { message?: string; data?: Record<string, unknown> }>(crumb: B): B => {
    if (crumb.message) crumb.message = scrubString(crumb.message);
    if (crumb.data) crumb.data = scrubValue(crumb.data);
    return crumb;
  }
};

/** Options every SDK init shares: environment, release, sampling, no default PII, scrubbing. */
export function baseInitOptions(config: TelemetryConfig) {
  return {
    dsn: config.dsn,
    environment: config.environment,
    release: config.release,
    tracesSampleRate: config.tracesSampleRate,
    sendDefaultPii: false,
    initialScope: { tags: { app: config.service } },
    ...scrubHooks
  };
}

export function sentryTelemetry(sdk: SentryLike, onWebVital?: (m: WebVital) => void): Telemetry {
  const safe = (fn: () => void) => {
    try {
      fn();
    } catch {
      // Telemetry must never break the app.
    }
  };
  return {
    enabled: true,
    captureException: (error: unknown, ctx?: CaptureContext) => safe(() => sdk.captureException(error, ctx)),
    captureMessage: (message: string, ctx?: CaptureContext) => safe(() => sdk.captureMessage(message, ctx)),
    addBreadcrumb: (c: Breadcrumb) => safe(() => sdk.addBreadcrumb(c)),
    setUser: (u: TelemetryUser | null) => safe(() => sdk.setUser(u ? { id: u.id } : null)),
    setTag: (k: string, v: string) => safe(() => sdk.setTag(k, v)),
    reportWebVital: (m: WebVital) =>
      safe(() => {
        sdk.addBreadcrumb({ category: 'web-vital', message: `${m.name} ${Math.round(m.value * 1000) / 1000}`, level: m.rating === 'poor' ? 'warning' : 'info', data: { ...m } });
        onWebVital?.(m);
      }),
    flush: async (timeoutMs = 2000) => {
      try {
        return await sdk.flush(timeoutMs);
      } catch {
        return false;
      }
    }
  };
}
