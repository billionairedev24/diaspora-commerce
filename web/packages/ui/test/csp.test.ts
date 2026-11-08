import { describe, expect, it } from 'vitest';

import {
  buildCsp,
  buildCspHeaders,
  createCspReportHandler,
  createReportLimiter,
  generateNonce,
  originOf,
  resolveCspMode,
  violationLines
} from '../src/security/csp';

const directive = (policy: string, name: string): string[] =>
  (policy.split('; ').find((d) => d.startsWith(`${name} `)) ?? '').split(' ').slice(1);

describe('nonce', () => {
  it('is unique per call and base64', () => {
    const nonces = new Set(Array.from({ length: 200 }, () => generateNonce()));
    expect(nonces.size).toBe(200);
    for (const n of nonces) expect(n).toMatch(/^[A-Za-z0-9+/]{22}==$/);
  });

  it('differs between two requests built through buildCspHeaders', () => {
    const a = buildCspHeaders('storefront', {});
    const b = buildCspHeaders('storefront', {});
    expect(a?.nonce).not.toBe(b?.nonce);
    expect(a?.value).toContain(`'nonce-${a?.nonce}'`);
  });
});

describe('mode', () => {
  it('defaults to report-only, also for an unknown value', () => {
    expect(resolveCspMode({})).toBe('report-only');
    expect(resolveCspMode({ CSP_MODE: 'nonsense' })).toBe('report-only');
    expect(resolveCspMode({ CSP_MODE: ' Enforce ' })).toBe('enforce');
    expect(resolveCspMode({ CSP_MODE: 'off' })).toBe('off');
  });

  it('picks the header name from the mode and sends nothing when off', () => {
    expect(buildCspHeaders('staff-console', {})?.name).toBe('Content-Security-Policy-Report-Only');
    expect(buildCspHeaders('staff-console', { CSP_MODE: 'enforce' })?.name).toBe('Content-Security-Policy');
    expect(buildCspHeaders('staff-console', { CSP_MODE: 'off' })).toBeUndefined();
  });
});

describe('directives', () => {
  const prod = { NODE_ENV: 'production', APP_ENV: 'prod', MEDIA_PUBLIC_URL: 'https://media.afrotransact.com/media' };

  it('is nonce + strict-dynamic for scripts, never unsafe-inline, in every app', () => {
    for (const app of ['storefront', 'seller-center', 'staff-console'] as const) {
      const scripts = directive(buildCsp(app, 'abc', prod), 'script-src');
      expect(scripts).toContain("'nonce-abc'");
      expect(scripts).toContain("'strict-dynamic'");
      expect(scripts).not.toContain("'unsafe-inline'");
      expect(scripts).not.toContain("'unsafe-eval'");
    }
  });

  it('keeps unsafe-inline for styles only, without a nonce (a nonce would switch it off)', () => {
    const styles = directive(buildCsp('storefront', 'abc', prod), 'style-src');
    expect(styles).toEqual(["'self'", "'unsafe-inline'"]);
  });

  it('has the fixed hardening directives', () => {
    const policy = buildCsp('storefront', 'abc', prod);
    expect(directive(policy, 'frame-ancestors')).toEqual(["'none'"]);
    expect(directive(policy, 'object-src')).toEqual(["'none'"]);
    expect(directive(policy, 'base-uri')).toEqual(["'self'"]);
    expect(directive(policy, 'form-action')).toEqual(["'self'"]);
    expect(policy).toContain('report-uri /api/csp-report');
    expect(policy).toContain('report-to csp-endpoint');
  });

  it('allows Stripe on the storefront and seller center only', () => {
    const storefront = buildCsp('storefront', 'n', prod);
    expect(directive(storefront, 'script-src')).toContain('https://js.stripe.com');
    expect(directive(storefront, 'connect-src')).toContain('https://api.stripe.com');
    expect(directive(storefront, 'frame-src')).toEqual(expect.arrayContaining(['https://js.stripe.com', 'https://hooks.stripe.com']));
    expect(directive(storefront, 'script-src')).not.toContain('https://connect-js.stripe.com');

    const seller = buildCsp('seller-center', 'n', prod);
    expect(directive(seller, 'script-src')).toContain('https://connect-js.stripe.com');
    expect(directive(seller, 'frame-src')).toContain('https://connect-js.stripe.com');

    const staff = buildCsp('staff-console', 'n', prod);
    expect(staff).not.toContain('stripe');
    expect(directive(staff, 'frame-src')).toEqual(["'none'"]);
  });

  it('allows images from the media origin (not its path) plus data: and blob:', () => {
    const images = directive(buildCsp('storefront', 'n', prod), 'img-src');
    expect(images).toEqual(expect.arrayContaining(["'self'", 'data:', 'blob:', 'https://media.afrotransact.com']));
    expect(images).not.toContain('http://localhost:9000');
  });

  it('falls back to the local MinIO origin only outside deployed environments', () => {
    expect(directive(buildCsp('storefront', 'n', {}), 'img-src')).toContain('http://localhost:9000');
    expect(directive(buildCsp('storefront', 'n', { APP_ENV: 'prod' }), 'img-src')).not.toContain('http://localhost:9000');
  });

  it('allows the OTLP endpoint and the Sentry ingest host as connect targets when configured', () => {
    const env = {
      ...prod,
      NEXT_PUBLIC_OTEL_EXPORTER_OTLP_ENDPOINT: 'https://otel.afrotransact.com/v1/traces',
      NEXT_PUBLIC_SENTRY_DSN: 'https://key@o1.ingest.sentry.io/42'
    };
    const connect = directive(buildCsp('seller-center', 'n', env), 'connect-src');
    expect(connect).toEqual(expect.arrayContaining(["'self'", 'https://otel.afrotransact.com', 'https://o1.ingest.sentry.io']));
    expect(directive(buildCsp('seller-center', 'n', prod), 'connect-src')).toEqual(["'self'", 'https://api.stripe.com']);
  });

  it("adds 'unsafe-eval' and websockets only for next dev", () => {
    const dev = buildCsp('storefront', 'n', { NODE_ENV: 'development' });
    expect(directive(dev, 'script-src')).toContain("'unsafe-eval'");
    expect(directive(dev, 'connect-src')).toEqual(expect.arrayContaining(['ws:', 'wss:']));
    const production = buildCsp('storefront', 'n', { NODE_ENV: 'production' });
    expect(production).not.toContain('unsafe-eval');
    expect(production).not.toContain('ws:');
  });

  it('ignores media/telemetry values that are not http(s) URLs', () => {
    expect(originOf('javascript:alert(1)')).toBeUndefined();
    expect(originOf('/relative')).toBeUndefined();
    expect(originOf('')).toBeUndefined();
    expect(originOf('https://media.test:8443/media/')).toBe('https://media.test:8443');
  });
});

describe('violation reports', () => {
  it('reduces a legacy report to a compact line without query strings or samples', () => {
    const lines = violationLines('storefront', {
      'csp-report': {
        'document-uri': 'https://www.afrotransact.com/s?q=secret&email=a@b.c#frag',
        'effective-directive': 'script-src-elem',
        'blocked-uri': 'https://evil.test/x.js?token=1',
        'source-file': 'https://www.afrotransact.com/_next/static/a.js?v=2',
        'line-number': 10,
        'column-number': 4,
        'script-sample': 'alert("pii")',
        referrer: 'https://x.test/?a=b',
        disposition: 'report'
      }
    });
    expect(lines).toEqual([
      {
        event: 'csp.violation',
        app: 'storefront',
        directive: 'script-src-elem',
        disposition: 'report',
        blocked: 'https://evil.test',
        page: '/s',
        source: 'https://www.afrotransact.com/_next/static/a.js',
        line: 10,
        column: 4
      }
    ]);
    expect(JSON.stringify(lines)).not.toMatch(/secret|token|pii|email/);
  });

  it('reads Reporting API batches and keywords like inline / eval / data', () => {
    const lines = violationLines('staff-console', [
      { type: 'csp-violation', body: { effectiveDirective: 'style-src-attr', blockedURL: 'inline', documentURL: 'https://admin.test/orders', disposition: 'enforce' } },
      { type: 'deprecation', body: {} },
      { type: 'csp-violation', body: { effectiveDirective: 'img-src', blockedURL: 'data:image/png;base64,AAAA', documentURL: 'https://admin.test/' } }
    ]);
    expect(lines.map((l) => [l.directive, l.blocked, l.disposition, l.page])).toEqual([
      ['style-src-attr', 'inline', 'enforce', '/orders'],
      ['img-src', 'data', 'report', '/']
    ]);
  });

  it('ignores junk', () => {
    expect(violationLines('storefront', 'nope')).toEqual([]);
    expect(violationLines('storefront', { 'csp-report': {} })).toEqual([]);
    expect(violationLines('storefront', null)).toEqual([]);
  });

  it('limits the burst of lines taken from one request', () => {
    const batch = Array.from({ length: 50 }, () => ({ type: 'csp-violation', body: { effectiveDirective: 'img-src', blockedURL: 'inline' } }));
    expect(violationLines('storefront', batch)).toHaveLength(10);
  });

  it('rate limits per window', () => {
    let t = 0;
    const allow = createReportLimiter(2, 1000, () => t);
    expect([allow(), allow(), allow()]).toEqual([true, true, false]);
    t = 1000;
    expect(allow()).toBe(true);
  });

  it('handler logs one JSON line, answers 204, rejects big and over-limit bodies', async () => {
    const logged: string[] = [];
    const handler = createCspReportHandler('seller-center', { limiter: createReportLimiter(2, 60_000, () => 0), log: (l) => logged.push(l) });
    const body = JSON.stringify({ 'csp-report': { 'effective-directive': 'img-src', 'blocked-uri': 'https://x.test/a.png', 'document-uri': 'https://s.test/p' } });
    const ok = await handler(new Request('https://s.test/api/csp-report', { method: 'POST', body }));
    expect(ok.status).toBe(204);
    expect(JSON.parse(logged[0] ?? '{}')).toMatchObject({ event: 'csp.violation', app: 'seller-center', directive: 'img-src' });
    const garbage = await handler(new Request('https://s.test/api/csp-report', { method: 'POST', body: '{not json' }));
    expect(garbage.status).toBe(204);
    expect(logged).toHaveLength(1);
    const limited = await handler(new Request('https://s.test/api/csp-report', { method: 'POST', body }));
    expect(limited.status).toBe(429);

    const big = createCspReportHandler('seller-center', { limiter: () => true, log: (l) => logged.push(l) });
    const tooBig = await big(new Request('https://s.test/api/csp-report', { method: 'POST', body: 'x'.repeat(20_000) }));
    expect(tooBig.status).toBe(413);
  });
});
