import { CSP_MATCHER } from '@afrotransact/ui/csp';
import { NextRequest } from 'next/server';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { POST } from '@/app/api/csp-report/route';
import { config, middleware } from '@/middleware';

// The app must ship the CSP middleware and the report endpoint: removing either fails this file (and so CI).
describe('seller-center CSP middleware', () => {
  afterEach(() => vi.unstubAllEnvs());

  const get = () => middleware(new NextRequest('https://app.test/some/page'));

  it('sets a Report-Only policy with a fresh nonce per request by default', () => {
    const a = get();
    const b = get();
    const policy = a.headers.get('Content-Security-Policy-Report-Only') ?? '';
    expect(a.headers.get('Content-Security-Policy')).toBeNull();
    expect(policy).toContain("script-src 'self' 'nonce-");
    expect(policy).toContain("'strict-dynamic'");
    expect(policy).not.toMatch(/script-src[^;]*'unsafe-inline'/);
    expect(policy).toContain("frame-ancestors 'none'");
    expect(a.headers.get('Reporting-Endpoints')).toBe('csp-endpoint="/api/csp-report"');
    expect(b.headers.get('Content-Security-Policy-Report-Only')).not.toBe(policy);
  });

  it('hands the same nonce to Next.js through the request headers', () => {
    const response = get();
    const forwarded = response.headers.get('x-middleware-request-x-nonce') ?? '';
    expect(forwarded).not.toBe('');
    expect(response.headers.get('Content-Security-Policy-Report-Only')).toContain(`'nonce-${forwarded}'`);
    expect(response.headers.get('x-middleware-request-content-security-policy-report-only')).toContain(`'nonce-${forwarded}'`);
  });

  it('enforces with CSP_MODE=enforce and sends nothing with CSP_MODE=off', () => {
    vi.stubEnv('CSP_MODE', 'enforce');
    const enforced = get();
    expect(enforced.headers.get('Content-Security-Policy')).toContain("default-src 'self'");
    expect(enforced.headers.get('Content-Security-Policy-Report-Only')).toBeNull();
    vi.stubEnv('CSP_MODE', 'off');
    const off = get();
    expect(off.headers.get('Content-Security-Policy')).toBeNull();
    expect(off.headers.get('Content-Security-Policy-Report-Only')).toBeNull();
  });

  it("allows 'unsafe-eval' only in development", () => {
    vi.stubEnv('NODE_ENV', 'production');
    expect(get().headers.get('Content-Security-Policy-Report-Only')).not.toContain('unsafe-eval');
    vi.stubEnv('NODE_ENV', 'development');
    expect(get().headers.get('Content-Security-Policy-Report-Only')).toContain("'unsafe-eval'");
  });

  it('has a matcher equal to the shared one (skips build assets, the report endpoint and prefetches)', () => {
    expect(config.matcher).toEqual(CSP_MATCHER);
  });

  it('serves the report endpoint', async () => {
    const body = JSON.stringify({
      'csp-report': { 'effective-directive': 'img-src', 'blocked-uri': 'https://cdn.test/a.png?x=1', 'document-uri': 'https://app.test/p?email=a@b.c' }
    });
    const log = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const response = await POST(new Request('https://app.test/api/csp-report', { method: 'POST', body }));
    expect(response.status).toBe(204);
    expect(log).toHaveBeenCalledWith(expect.stringContaining('"blocked":"https://cdn.test"'));
    expect(log).toHaveBeenCalledWith(expect.not.stringContaining('email'));
    log.mockRestore();
  });
});
