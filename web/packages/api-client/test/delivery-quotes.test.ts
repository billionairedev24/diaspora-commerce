import { afterAll, beforeAll, beforeEach, describe, expect, expectTypeOf, it } from 'vitest';

import { ApiError, createApiClient, type NewQuoteRequestBody,type QuoteRequest } from '../src';
import { type components } from '../src/generated/gateway-bff';
import { decideMockQuote, MOCK_QUOTE_VALIDITY_HOURS, resetDb } from '../src/mocks';
import { mockServer } from '../src/mocks/node';

type S = components['schemas'];

async function signedIn() {
  let cookie = '';
  const opts = {
    baseUrl: 'http://bff.test',
    fetch: async (req: Request) => {
      if (cookie) req.headers.set('cookie', cookie);
      const res = await fetch(req);
      const set = res.headers.get('set-cookie');
      if (set) cookie = set.split(';')[0] ?? '';
      return res;
    }
  };
  const api = createApiClient(opts);
  const res = await opts.fetch(new Request(api.loginUrl('/account'), { redirect: 'manual' }));
  if (res.status !== 302) throw new Error('expected the hosted sign-in redirect');
  return api;
}

const refusal = (status: number, code?: string) => (e: unknown) => e instanceof ApiError && e.status === status && (code === undefined || e.code === code);

beforeAll(() => mockServer.listen({ onUnhandledRequest: 'error' }));
afterAll(() => mockServer.close());
beforeEach(() => { resetDb(); });

describe('delivery quote schemas stay aligned with the contract', () => {
  it('runtime types are assignable to the generated contract types', () => {
    expectTypeOf<QuoteRequest>().toExtend<S['DeliveryQuoteRequest']>();
    expectTypeOf<NewQuoteRequestBody>().toExtend<S['DeliveryQuoteRequestBody']>();
  });
});

describe('mock BFF: ask for a delivery quote', () => {
  it('is for signed-in shoppers, idempotent for the same cart and address, and shows plain statuses', async () => {
    const guest = createApiClient({ baseUrl: 'http://bff.test' });
    await expect(guest.requestDeliveryQuote({ addressId: 'ad1' })).rejects.toSatisfy(refusal(401));
    const api = await signedIn();

    const first = await api.requestDeliveryQuote({ addressId: 'ad1', note: 'Gate code 4821' });
    expect(first).toMatchObject({ status: 'REQUESTED', statusLabel: 'Requested', amountMinor: null, place: 'Houston, TX 77042', usable: false });
    expect((await api.requestDeliveryQuote({ addressId: 'ad1', note: 'again' })).id).toBe(first.id);
    await expect(api.requestDeliveryQuote({ addressId: 'nope' })).rejects.toSatisfy(refusal(422));

    expect((await api.listMyDeliveryQuotes()).items).toHaveLength(1);
    await expect(api.getMyDeliveryQuote('someone-elses')).rejects.toSatisfy(refusal(404));

    decideMockQuote('u_adaeze', first.id, { status: 'IN_PROGRESS' });
    expect((await api.getMyDeliveryQuote(first.id)).statusLabel).toBe('Being quoted');
    const ready = decideMockQuote('u_adaeze', first.id, { status: 'READY', amountMinor: 2250 });
    expect(ready).toMatchObject({ status: 'READY', statusLabel: 'Quote ready', amountMinor: 2250, usable: true });
    const hours = (Date.parse(ready.expiresAt ?? '') - Date.now()) / 3_600_000;
    expect(hours).toBeGreaterThan(MOCK_QUOTE_VALIDITY_HOURS - 0.1);

    // Past its validity a ready quote reads as expired at once.
    decideMockQuote('u_adaeze', first.id, { status: 'READY', amountMinor: 2250, expiresAt: new Date(Date.now() - 60_000).toISOString() });
    expect(await api.getMyDeliveryQuote(first.id)).toMatchObject({ statusLabel: 'Expired', usable: false });
  });

  it('declined reads plainly', async () => {
    const api = await signedIn();
    const q = await api.requestDeliveryQuote({ addressId: 'ad2' });
    decideMockQuote('u_adaeze', q.id, { status: 'DECLINED' });
    expect(await api.getMyDeliveryQuote(q.id)).toMatchObject({ status: 'DECLINED', statusLabel: 'Declined', amountMinor: null });
  });
});
