/*
 * Minimal browser → OpenTelemetry Collector exporter (OTLP/HTTP JSON, no SDK dependency). It sends one
 * `page.load` span per navigation, with Navigation Timing and the page's web vitals as attributes, to
 * `<otlpEndpoint>/v1/traces`. The collector must allow the site's origin (CORS). Off when no endpoint is set.
 */
import { scrubString } from './scrub';

export interface OtlpWebOptions {
  endpoint: string;
  service: string;
  environment: string;
  release: string | undefined;
  /** Injected for tests. */
  fetchImpl?: typeof fetch;
  now?: () => number;
}

type AttrValue = { stringValue: string } | { doubleValue: number } | { intValue: number };
interface Attr {
  key: string;
  value: AttrValue;
}

const attr = (key: string, v: string | number): Attr =>
  ({ key, value: typeof v === 'number' ? (Number.isInteger(v) ? { intValue: v } : { doubleValue: v }) : { stringValue: v } });

function hex(bytes: number): string {
  const a = new Uint8Array(bytes);
  globalThis.crypto.getRandomValues(a);
  return Array.from(a, (b) => b.toString(16).padStart(2, '0')).join('');
}

const nanos = (ms: number) => String(Math.round(ms * 1e6));

export interface OtlpWebExporter {
  /** Adds a web vital to the current page's span. */
  recordWebVital(name: string, value: number, rating?: string): void;
  /** Sends the page span (called when the page is hidden; safe to call more than once). */
  flush(): Promise<void>;
  /** OTLP JSON body of the current span (exposed for tests). */
  payload(): unknown;
}

export function createOtlpWebExporter(o: OtlpWebOptions): OtlpWebExporter {
  const send = o.fetchImpl ?? (typeof fetch === 'function' ? fetch.bind(globalThis) : undefined);
  const now = o.now ?? (() => Date.now());
  const traceId = hex(16);
  const spanId = hex(8);
  const vitals: Attr[] = [];
  let sent = false;
  const started = typeof performance !== 'undefined' && performance.timeOrigin ? performance.timeOrigin : now();

  const timing = (): Attr[] => {
    if (typeof performance === 'undefined' || typeof performance.getEntriesByType !== 'function') return [];
    const nav = performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming | undefined;
    if (!nav) return [];
    return [
      attr('navigation.type', nav.type),
      attr('timing.ttfb_ms', Math.round(nav.responseStart)),
      attr('timing.dom_content_loaded_ms', Math.round(nav.domContentLoadedEventEnd)),
      attr('timing.load_ms', Math.round(nav.loadEventEnd)),
      attr('http.response.transfer_size', nav.transferSize)
    ];
  };

  const payload = () => {
    const path = typeof location === 'undefined' ? '' : scrubString(location.pathname);
    return {
      resourceSpans: [
        {
          resource: {
            attributes: [
              attr('service.name', o.service),
              attr('deployment.environment.name', o.environment),
              ...(o.release ? [attr('service.version', o.release)] : []),
              attr('telemetry.sdk.language', 'webjs')
            ]
          },
          scopeSpans: [
            {
              scope: { name: '@afrotransact/telemetry' },
              spans: [
                {
                  traceId,
                  spanId,
                  name: 'page.load',
                  kind: 1,
                  startTimeUnixNano: nanos(started),
                  endTimeUnixNano: nanos(now()),
                  attributes: [attr('url.path', path), ...timing(), ...vitals],
                  status: { code: 0 }
                }
              ]
            }
          ]
        }
      ]
    };
  };

  return {
    recordWebVital(name, value, rating) {
      const key = `web_vital.${name.toLowerCase()}`;
      const i = vitals.findIndex((a) => a.key === key);
      const a = attr(key, Math.round(value * 1000) / 1000);
      if (i >= 0) vitals[i] = a;
      else vitals.push(a);
      if (rating) vitals.push(attr(`${key}.rating`, rating));
    },
    async flush() {
      if (sent || !send) return;
      sent = true;
      try {
        await send(`${o.endpoint}/v1/traces`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload()),
          keepalive: true
        });
      } catch {
        // Telemetry must never break the page.
      }
    },
    payload
  };
}
