import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import { ApiError, createApiClient, isPlainEmail } from '../src';
import { getDb, resetDb } from '../src/mocks';
import { mockServer } from '../src/mocks/node';

beforeAll(() => mockServer.listen({ onUnhandledRequest: 'error' }));
afterAll(() => mockServer.close());
beforeEach(() => { resetDb(); });

const guest = () => createApiClient({ baseUrl: 'http://bff.test' });

describe('"Email me when available" against the mock BFF, as a guest', () => {
  it('subscribes an address without signing in, with the same answer when repeated', async () => {
    const api = guest();
    const first = await api.createStockAlert('AT00000P18', '  Ada@Example.COM ');
    expect(first).toEqual({ atin: 'AT00000P18', status: 'subscribed' });
    expect(await api.createStockAlert('AT00000P18', 'ada@example.com')).toEqual(first);
    expect([...getDb().stockAlerts]).toEqual(['AT00000P18:ada@example.com']);
  });

  it('answers a bad address with a 422 field error, never a 401', async () => {
    for (const bad of ['nope', 'a@b', 'Ada <a@example.com>', 'a b@example.com', 'a@example.com,b@example.com']) {
      const err = await guest().createStockAlert('AT00000P18', bad).catch((e: unknown) => e);
      expect(err).toBeInstanceOf(ApiError);
      expect((err as ApiError).status).toBe(422);
      expect((err as ApiError).fieldErrors?.email).toMatch(/valid email address/);
    }
    const none = await guest().createStockAlert('AT00000P18').catch((e: unknown) => e);
    expect((none as ApiError).status).toBe(422);
  });

  it('keeps unknown products a 404', async () => {
    const err = await guest().createStockAlert('ATNOPE0000', 'a@example.com').catch((e: unknown) => e);
    expect((err as ApiError).status).toBe(404);
  });
});

describe('isPlainEmail', () => {
  it('accepts plain addresses and rejects what the BFF rejects', () => {
    for (const ok of ['a@example.com', 'first.last+tag@sub.example.co.uk', " o'brien@example.ie "]) expect(isPlainEmail(ok)).toBe(true);
    for (const bad of ['', 'a', 'a@b', '.a@example.com', 'a..b@example.com', 'a@example.c', 'é@example.com', `${'a'.repeat(65)}@example.com`, 'a@1.2.3.4']) {
      expect(isPlainEmail(bad)).toBe(false);
    }
  });
});
