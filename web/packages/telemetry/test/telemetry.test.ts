import { describe, expect, it, vi } from 'vitest';

import { getTelemetry, noopTelemetry, resolveTelemetryConfig, scrubEvent, scrubString, scrubValue, setTelemetry } from '../src';
import { initNativeTelemetry, type NativeSentryLike } from '../src/native';
import { sourceMapUploadEnabled } from '../src/next/config';
import { createOtlpWebExporter } from '../src/otlp';
import { sentryTelemetry } from '../src/sdk';

describe('resolveTelemetryConfig', () => {
  it('is off without a DSN and samples per environment', () => {
    expect(resolveTelemetryConfig('storefront', {})).toMatchObject({ dsn: undefined, environment: 'local', tracesSampleRate: 0 });
    expect(resolveTelemetryConfig('storefront', { environment: 'prod', dsn: ' https://k@o1.ingest.example/1 ', release: 'abc123' })).toMatchObject({
      dsn: 'https://k@o1.ingest.example/1',
      environment: 'prod',
      release: 'abc123',
      tracesSampleRate: 0.05
    });
    expect(resolveTelemetryConfig('s', { environment: 'uat', tracesSampleRate: '2' }).tracesSampleRate).toBe(1);
    expect(resolveTelemetryConfig('s', { environment: 'bogus' }).environment).toBe('local');
    expect(resolveTelemetryConfig('s', { otlpEndpoint: 'https://otel.example/' }).otlpEndpoint).toBe('https://otel.example');
  });
});

describe('PII scrubbing', () => {
  it('removes emails, phones, tokens and card numbers from text', () => {
    const s = scrubString(
      'ada@example.com called +1 (713) 555-0142 / 0803 123 4567 with Bearer abc.def-ghi_12345 eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl card 4242 4242 4242 4242 order 123456 ?token=s3cr3t&x=1 sk_live_abcdefgh1234'
    );
    expect(s).not.toMatch(/ada@example\.com|555-0142|123 4567|abc\.def|eyJhbGci|4242 4242|s3cr3t|sk_live/);
    expect(s).toContain('[email]');
    expect(s).toContain('[phone]');
    expect(s).toContain('[card]');
    expect(s).toContain('Bearer [token]');
    expect(s).toContain('order 123456'); // short numbers stay
    expect(s).toContain('token=[redacted]&x=1');
  });

  it('keeps non-Luhn long numbers (order ids) and blanks sensitive keys', () => {
    expect(scrubString('ATIN 1234567890123')).toBe('ATIN 1234567890123');
    expect(scrubValue({ cardNumber: '4111', user_email: 'x', sessionId: 's', span_id: 'abc', nested: { password: 'p', ok: 'fine' }, phoneVerified: true })).toEqual({
      cardNumber: '[redacted]',
      user_email: '[redacted]',
      sessionId: '[redacted]',
      span_id: 'abc',
      nested: { password: '[redacted]', ok: 'fine' },
      phoneVerified: true
    });
  });

  it('scrubs whole events and reduces the user to an id', () => {
    const e = scrubEvent({
      message: 'failed for ada@example.com',
      user: { id: 'u_1', email: 'ada@example.com', ip_address: '1.2.3.4' },
      request: { url: 'https://x/cb?code=abc&state=1', headers: { Authorization: 'Bearer zzzzzzzzzz', 'User-Agent': 'UA' }, cookies: { a: 'b' }, data: { password: 'x' } },
      exception: { values: [{ value: 'card 4111 1111 1111 1111 declined' }] },
      breadcrumbs: [{ message: 'tel 713-555-0142' }]
    });
    expect(e.user).toEqual({ id: 'u_1' });
    expect(e.message).toBe('failed for [email]');
    expect(e.request.url).toBe('https://x/cb?code=[redacted]&state=1');
    expect(e.request.headers).toEqual({ Authorization: '[redacted]', 'User-Agent': 'UA' });
    expect(e.request.cookies).toBe('[redacted]');
    expect(e.request.data).toBe('[redacted]');
    expect(e.exception.values[0]?.value).toBe('card [card] declined');
    expect(JSON.stringify(e.breadcrumbs)).not.toContain('555');
  });
});

describe('telemetry holder and adapters', () => {
  it('is a no-op by default', () => {
    expect(getTelemetry()).toBe(noopTelemetry);
    expect(getTelemetry().enabled).toBe(false);
    expect(() => getTelemetry().captureException(new Error('x'))).not.toThrow();
  });

  const fakeSdk = () => ({
    init: vi.fn(),
    captureException: vi.fn(() => 'id'),
    captureMessage: vi.fn(() => 'id'),
    addBreadcrumb: vi.fn(),
    setUser: vi.fn(),
    setTag: vi.fn(),
    flush: vi.fn(() => Promise.resolve(true))
  });

  it('native init stays off without a DSN and installs the SDK adapter with one', () => {
    const sdk = fakeSdk();
    expect(initNativeTelemetry(sdk as NativeSentryLike, resolveTelemetryConfig('customer-app', {}))).toBeUndefined();
    expect(sdk.init).not.toHaveBeenCalled();
    const t = initNativeTelemetry(sdk as NativeSentryLike, resolveTelemetryConfig('customer-app', { dsn: 'https://k@h/1', environment: 'dev', release: '2.0.0+42' }));
    expect(t?.enabled).toBe(true);
    const opts = sdk.init.mock.calls[0]?.[0] as Record<string, unknown>;
    expect(opts).toMatchObject({ environment: 'dev', release: '2.0.0+42', sendDefaultPii: false, attachScreenshot: false });
    expect(typeof opts.beforeSend).toBe('function');
    expect(getTelemetry()).toBe(t);
    setTelemetry(noopTelemetry);
  });

  it('never throws from the SDK and reports only the user id', () => {
    const sdk = fakeSdk();
    sdk.captureException.mockImplementation(() => {
      throw new Error('sdk down');
    });
    const t = sentryTelemetry(sdk);
    expect(() => t.captureException(new Error('x'))).not.toThrow();
    t.setUser({ id: 'u_1' });
    expect(sdk.setUser).toHaveBeenCalledWith({ id: 'u_1' });
    t.reportWebVital({ name: 'LCP', value: 1234.5678, rating: 'good' });
    expect(sdk.addBreadcrumb).toHaveBeenCalledWith(expect.objectContaining({ category: 'web-vital', message: 'LCP 1234.568' }));
  });
});

describe('source map upload', () => {
  it('needs the opt-in switch and all credentials', () => {
    expect(sourceMapUploadEnabled({})).toBe(false);
    expect(sourceMapUploadEnabled({ SENTRY_AUTH_TOKEN: 't', SENTRY_ORG: 'o', SENTRY_PROJECT: 'p' })).toBe(false);
    expect(sourceMapUploadEnabled({ SENTRY_UPLOAD_SOURCEMAPS: '1', SENTRY_AUTH_TOKEN: 't', SENTRY_ORG: 'o', SENTRY_PROJECT: 'p' })).toBe(true);
  });
});

describe('OTLP web exporter', () => {
  it('posts one page.load span with web vitals to <endpoint>/v1/traces', async () => {
    const fetchImpl = vi.fn((_url: string, _init?: RequestInit) => Promise.resolve(new Response(null, { status: 200 })));
    const ex = createOtlpWebExporter({ endpoint: 'https://otel.example', service: 'storefront', environment: 'dev', release: 'r1', fetchImpl: fetchImpl as unknown as typeof fetch });
    ex.recordWebVital('LCP', 1200.4, 'good');
    ex.recordWebVital('LCP', 1300, 'good');
    await ex.flush();
    await ex.flush();
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    const [url, init] = fetchImpl.mock.calls[0] ?? [];
    expect(url).toBe('https://otel.example/v1/traces');
    const body = JSON.parse(String(init?.body)) as { resourceSpans: Array<{ resource: { attributes: Array<{ key: string }> }; scopeSpans: Array<{ spans: Array<{ name: string; traceId: string; attributes: Array<{ key: string; value: Record<string, unknown> }> }> }> }> };
    const span = body.resourceSpans[0]?.scopeSpans[0]?.spans[0];
    expect(span?.name).toBe('page.load');
    expect(span?.traceId).toMatch(/^[0-9a-f]{32}$/);
    expect(span?.attributes.find((a) => a.key === 'web_vital.lcp')?.value).toEqual({ intValue: 1300 });
    expect(body.resourceSpans[0]?.resource.attributes.map((a) => a.key)).toContain('service.version');
  });
});
