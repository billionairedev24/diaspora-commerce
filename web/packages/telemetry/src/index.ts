/*
 * @afrotransact/telemetry — front-end and mobile error tracking behind one interface.
 *
 * Apps talk to `Telemetry` (getTelemetry().captureException(…)), never to the SDK. The web implementation wraps
 * @sentry/nextjs (./next/*), the mobile one @sentry/react-native (./native). Any Sentry-compatible back end works
 * (Sentry SaaS, self-hosted Sentry, GlitchTip): only the DSN changes.
 *
 * Tracking is OFF unless a DSN is configured for the environment. Every event, breadcrumb and span passes through
 * the PII scrubber (emails, phone numbers, bearer/JWT/API tokens, card numbers) before it leaves the device.
 */

export { scrubEvent, scrubString, scrubValue } from './scrub';

export type AppEnv = 'local' | 'dev' | 'uat' | 'prod';
export type Severity = 'fatal' | 'error' | 'warning' | 'info' | 'debug';

export interface TelemetryConfig {
  /** Off when undefined: no SDK is initialised and every call is a no-op. */
  dsn: string | undefined;
  environment: AppEnv;
  /** Release identifier (git SHA or app version+build). Errors are grouped by release. */
  release: string | undefined;
  /** Share of transactions traced (0–1). */
  tracesSampleRate: number;
  /** OTLP/HTTP collector base URL for browser traces (…/v1/traces is appended); off when undefined. */
  otlpEndpoint: string | undefined;
  /** Service name reported to the collector and as the Sentry `app` tag. */
  service: string;
}

export interface RawTelemetryEnv {
  dsn?: string;
  environment?: string;
  release?: string;
  tracesSampleRate?: string;
  otlpEndpoint?: string;
}

const DEFAULT_TRACES: Record<AppEnv, number> = { local: 0, dev: 1, uat: 0.25, prod: 0.05 };

export const isAppEnv = (v: string | undefined): v is AppEnv => v === 'local' || v === 'dev' || v === 'uat' || v === 'prod';

const clean = (v: string | undefined): string | undefined => (v && v.trim() ? v.trim() : undefined);

/** Pure resolver (unit-tested): environment-specific defaults overridden by any variable that is set. */
export function resolveTelemetryConfig(service: string, raw: RawTelemetryEnv): TelemetryConfig {
  const environment: AppEnv = isAppEnv(raw.environment) ? raw.environment : 'local';
  const rate = Number(raw.tracesSampleRate);
  return {
    dsn: clean(raw.dsn),
    environment,
    release: clean(raw.release),
    tracesSampleRate: Number.isFinite(rate) && raw.tracesSampleRate !== undefined && raw.tracesSampleRate !== '' ? Math.min(1, Math.max(0, rate)) : DEFAULT_TRACES[environment],
    otlpEndpoint: clean(raw.otlpEndpoint)?.replace(/\/+$/, ''),
    service
  };
}

export interface TelemetryUser {
  /** Opaque account id only — never an email, phone or name. */
  id: string;
}

export interface Breadcrumb {
  category: string;
  message: string;
  level?: Severity;
  data?: Record<string, unknown>;
}

export interface WebVital {
  /** CLS, FCP, INP, LCP, TTFB… */
  name: string;
  value: number;
  rating?: 'good' | 'needs-improvement' | 'poor';
  id?: string;
  navigationType?: string;
}

export interface CaptureContext {
  tags?: Record<string, string>;
  extra?: Record<string, unknown>;
  level?: Severity;
}

/** What apps call. Implementations must never throw. */
export interface Telemetry {
  readonly enabled: boolean;
  captureException(error: unknown, context?: CaptureContext): void;
  captureMessage(message: string, context?: CaptureContext): void;
  addBreadcrumb(crumb: Breadcrumb): void;
  setUser(user: TelemetryUser | null): void;
  setTag(key: string, value: string): void;
  reportWebVital(metric: WebVital): void;
  flush(timeoutMs?: number): Promise<boolean>;
}

class NoopTelemetry implements Telemetry {
  readonly enabled = false;
  captureException(): void {}
  captureMessage(): void {}
  addBreadcrumb(): void {}
  setUser(): void {}
  setTag(): void {}
  reportWebVital(): void {}
  flush(): Promise<boolean> {
    return Promise.resolve(true);
  }
}

export const noopTelemetry: Telemetry = new NoopTelemetry();

let current: Telemetry = noopTelemetry;

/** The process-wide implementation (no-op until an app's bootstrap installs one). */
export function getTelemetry(): Telemetry {
  return current;
}

/** Installs the implementation; called once by the app bootstrap (instrumentation-client.ts, root layout on mobile). */
export function setTelemetry(t: Telemetry): void {
  current = t;
}
