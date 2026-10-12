import { describe, expect, it } from 'vitest';

import { CORRELATION_HEADER, forwardBffProxyRequestHeaders, withCorrelationHeaders } from '../src/correlation';
import { createRequester } from '../src/http';
import { z } from 'zod';

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
