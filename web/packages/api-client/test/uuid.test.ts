import { afterEach, describe, expect, it, vi } from 'vitest';

import { newCorrelationId } from '../src/correlation';
import { randomUuid } from '../src/uuid';

const V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

describe('randomUuid', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('works where crypto.randomUUID does not exist (http on a LAN address)', () => {
    vi.stubGlobal('crypto', { getRandomValues: (a: Uint8Array) => a.map((_, i) => (i * 37 + 11) & 0xff) });
    expect(randomUuid()).toMatch(V4);
    // the API client's correlation id is what threw before any request was sent
    expect(newCorrelationId()).toMatch(V4);
  });

  it('falls back to Math.random when there is no crypto at all', () => {
    vi.stubGlobal('crypto', undefined);
    expect(randomUuid()).toMatch(V4);
  });

  it('uses the platform implementation when present', () => {
    vi.stubGlobal('crypto', { randomUUID: () => '00000000-0000-4000-8000-000000000000' });
    expect(randomUuid()).toBe('00000000-0000-4000-8000-000000000000');
  });
});
