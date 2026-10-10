/*
 * Browser bootstrap, imported from each app's src/instrumentation-client.ts:
 *
 *   import { initBrowserTelemetry, onRouterTransitionStart } from '@afrotransact/telemetry/next/client';
 *   initBrowserTelemetry('storefront');
 *   export { onRouterTransitionStart };
 *
 * Without NEXT_PUBLIC_SENTRY_DSN nothing is loaded: the Sentry SDK is a lazy chunk fetched only when a DSN is set.
 */
import { getTelemetry, noopTelemetry, setTelemetry, type Telemetry, type WebVital } from '../index';
import { createOtlpWebExporter, type OtlpWebExporter } from '../otlp';
import { browserTelemetryConfig } from './env';

let routerHook: ((href: string, navigationType: string) => void) | undefined;
let exporter: OtlpWebExporter | undefined;
const pendingVitals: WebVital[] = [];

/** Buffers calls made before the SDK chunk has loaded and replays them once it has. */
function buffering(): Telemetry {
  const queue: Array<(t: Telemetry) => void> = [];
  const later = (fn: (t: Telemetry) => void) => {
    if (queue.length < 50) queue.push(fn);
  };
  const t: Telemetry & { drain(real: Telemetry): void } = {
    enabled: true,
    captureException: (e, c) => later((r) => r.captureException(e, c)),
    captureMessage: (m, c) => later((r) => r.captureMessage(m, c)),
    addBreadcrumb: (b) => later((r) => r.addBreadcrumb(b)),
    setUser: (u) => later((r) => r.setUser(u)),
    setTag: (k, v) => later((r) => r.setTag(k, v)),
    reportWebVital: (m) => {
      exporter?.recordWebVital(m.name, m.value, m.rating);
      pendingVitals.push(m);
    },
    flush: () => Promise.resolve(true),
    drain(real) {
      for (const fn of queue.splice(0)) fn(real);
    }
  };
  return t;
}

export function initBrowserTelemetry(service: string): void {
  if (typeof window === 'undefined') return;
  const config = browserTelemetryConfig(service);
  if (config.otlpEndpoint) {
    const ex = createOtlpWebExporter({ endpoint: config.otlpEndpoint, service, environment: config.environment, release: config.release });
    exporter = ex;
    addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'hidden') void ex.flush();
    });
    addEventListener('pagehide', () => void ex.flush());
  }
  if (!config.dsn) {
    if (exporter) {
      const ex = exporter;
      setTelemetry({ ...getTelemetry(), reportWebVital: (m) => ex.recordWebVital(m.name, m.value, m.rating) });
    }
    return;
  }
  const buffer = buffering();
  setTelemetry(buffer);
  void import('./sentry-browser')
    .then(({ startSentryBrowser }) => {
      const real = startSentryBrowser(config, (m) => exporter?.recordWebVital(m.name, m.value, m.rating));
      routerHook = real.onRouterTransitionStart;
      setTelemetry(real.telemetry);
      (buffer as Telemetry & { drain(r: Telemetry): void }).drain(real.telemetry);
      for (const m of pendingVitals.splice(0)) real.telemetry.addBreadcrumb({ category: 'web-vital', message: `${m.name} ${m.value}` });
    })
    .catch(() => setTelemetry(noopTelemetry));
}

/** Re-exported by instrumentation-client.ts so navigations are traced (no-op while tracking is off). */
export function onRouterTransitionStart(href: string, navigationType: string): void {
  routerHook?.(href, navigationType);
}
