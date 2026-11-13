import { describe, expect, it } from 'vitest';
import { z } from 'zod';

import { CORRELATION_HEADER, forwardBffClientIp, forwardBffProxyRequestHeaders, withCorrelationHeaders } from '../src/correlation';
import { createRequester } from '../src/http';

describe('correlation', () => {
  it('adds X-Correlation-Id when missing', () => {
    const h = withCorrelationHeaders({ Accept: 'application/json' });
    expect(h[CORRELATION_HEADER]).toMatch(/^[0-9a-f-]{36}$/i);
  });

  it('preserves an existing correlation id', () => {
    const h = withCorrelationHeaders({ [CORRELATION_HEADER]: 'keep-me' });
    expect(h[CORRELATION_HEADER]).toBe('keep-me');
  });

  it('forwardBffProxyRequestHeaders mints id when absent', () => {
    const out = new Headers();
    forwardBffProxyRequestHeaders(new Headers(), out);
    expect(out.get('x-correlation-id')).toMatch(/^[0-9a-f-]{36}$/i);
  });

  it('forwardBffClientIp uses cf-connecting-ip and never the incoming x-forwarded-for', () => {
    const out = new Headers({ 'x-forwarded-for': 'preset' });
    forwardBffClientIp(new Headers({ 'x-forwarded-for': '6.6.6.6, 1.1.1.1', 'cf-connecting-ip': '203.0.113.7' }), out);
    expect(out.get('x-forwarded-for')).toBe('203.0.113.7');
    expect(out.has('x-at-client-ip')).toBe(false);
  });

  it('forwardBffClientIp sends nothing without cf-connecting-ip (local dev) even if x-forwarded-for is set', () => {
    const out = new Headers();
    forwardBffClientIp(new Headers({ 'x-forwarded-for': '6.6.6.6' }), out);
    expect(out.has('x-forwarded-for')).toBe(false);
  });

  it('forwardBffClientIp adds the token-gated header only when a proxy token is configured', () => {
    const out = new Headers();
    forwardBffClientIp(new Headers({ 'cf-connecting-ip': '203.0.113.7' }), out, 's3cret');
    expect(out.get('x-at-client-ip')).toBe('203.0.113.7');
    expect(out.get('x-at-proxy-token')).toBe('s3cret');
  });

  it('createRequester sends correlation on fetch', async () => {
    let seen = '';
    const request = createRequester({
      baseUrl: '/api/bff',
      fetch: async (req) => {
        seen = req.headers.get(CORRELATION_HEADER) ?? '';
        return new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } });
      }
    });
    await request('/v1/site-config', z.object({}), {});
    expect(seen).toMatch(/^[0-9a-f-]{36}$/i);
  });
});
