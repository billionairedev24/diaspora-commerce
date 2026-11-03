import { afterAll, beforeAll, beforeEach, describe, expect, expectTypeOf, it } from 'vitest';

import { ApiError, type CheckoutCredit, createApiClient, type CreditAccount, type GiftCard, type GiftCardConfig } from '../src';
import { type components } from '../src/generated/gateway-bff';
import { resetDb } from '../src/mocks';
import { MOCK_GIFT_AMOUNT_MINOR, MOCK_GIFT_CODE, MOCK_RATE_LIMITED_CODE } from '../src/mocks/credit';
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

describe('credit schemas stay aligned with the contract', () => {
  it('runtime types are assignable to the generated contract types', () => {
    expectTypeOf<CreditAccount>().toExtend<S['CreditAccount']>();
    expectTypeOf<CheckoutCredit>().toExtend<S['CheckoutCredit']>();
    expectTypeOf<GiftCard>().toExtend<S['GiftCard']>();
    expectTypeOf<GiftCardConfig>().toExtend<S['GiftCardConfig']>();
  });

  it('has no client method that moves credit out', () => {
    const names = Object.keys(createApiClient({ baseUrl: 'http://bff.test' }));
    expect(names.filter((n) => /withdraw|payout|cashout|transfer/i.test(n))).toEqual([]);
  });
});

describe('mock BFF — credit and gift cards', () => {
  it('shows the balance and a paged history, newest first', async () => {
    const api = await signedIn();
    const first = await api.getAccountCredit({ limit: 2 });
    expect(first.balanceMinor).toBe(2500);
    expect(first.entries.map((e) => e.kind)).toEqual(['SPEND', 'GRANT']);
    expect(first.nextBefore).toBeTruthy();
    const rest = await api.getAccountCredit({ limit: 2, before: first.nextBefore ?? '' });
    expect(rest.entries).toHaveLength(1);
    expect(rest.nextBefore).toBeNull();
    await expect(api.getAccountCredit({ before: 'yesterday' })).rejects.toSatisfy(refusal(422));
  });

  it('redeems a gift card once, counts wrong codes and answers 429 over the limit', async () => {
    const api = await signedIn();
    await expect(api.redeemGiftCard('AAAA-BBBB-CCCC-DDDD')).rejects.toSatisfy(refusal(404, 'GIFT_CARD_INVALID'));
    await expect(api.redeemGiftCard(MOCK_RATE_LIMITED_CODE)).rejects.toSatisfy(refusal(429, 'RATE_LIMITED'));
    const red = await api.redeemGiftCard(MOCK_GIFT_CODE.toLowerCase());
    expect(red).toEqual({ redeemedMinor: MOCK_GIFT_AMOUNT_MINOR, balanceMinor: 2500 + MOCK_GIFT_AMOUNT_MINOR, currency: 'USD' });
    await expect(api.redeemGiftCard(MOCK_GIFT_CODE)).rejects.toSatisfy(refusal(409, 'GIFT_CARD_REDEEMED'));
    expect((await api.getAccountCredit()).entries[0]).toMatchObject({ kind: 'GRANT', source: 'GIFT_CARD' });
  });

  it('buys a gift card idempotently, validates the limits and lists it once confirmed', async () => {
    const api = await signedIn();
    const cfg = await api.getGiftCardConfig();
    expect(cfg.minMinor).toBeLessThan(cfg.maxMinor);
    const req = { amountMinor: 5000, recipientEmail: 'ama@example.com', recipientName: 'Ama' };
    await expect(api.buyGiftCard({ ...req, amountMinor: cfg.minMinor - 1 }, 'gift-key-000000001')).rejects.toSatisfy(refusal(422));
    await expect(api.buyGiftCard({ ...req, recipientEmail: 'nope' }, 'gift-key-000000001')).rejects.toSatisfy(refusal(422));
    const bought = await api.buyGiftCard(req, 'gift-key-000000001');
    expect(bought.status).toBe('PENDING_PAYMENT');
    expect(bought.clientSecret).toContain('_secret_');
    expect((await api.buyGiftCard(req, 'gift-key-000000001')).id).toBe(bought.id);
    expect((await api.listMyGiftCards()).items).toHaveLength(0);
    const paid = await api.confirmGiftCard(bought.id);
    expect(paid.status).toBe('ACTIVE');
    expect((await api.listMyGiftCards()).items.map((c) => c.id)).toEqual([bought.id]);
    await expect(api.confirmGiftCard('0f0f0f0f-0000-4000-8000-0000000000ff')).rejects.toSatisfy(refusal(404));
  });

  it('spends credit first at checkout: part with a card, and no card when credit covers the order', async () => {
    const api = await signedIn();
    await api.addToCart({ offerId: (await api.getProduct('AT000000P2')).buyBox?.offerId ?? '', quantity: 12 });
    const sum = await api.getCheckoutSummary();
    expect(sum.credit?.availableMinor).toBe(2500);
    expect((sum.credit?.appliedMinor ?? 0) + (sum.credit?.cardDueMinor ?? 0)).toBe(sum.totalMinor);
    const selection = [{ packageId: sum.packages[0]?.packageId ?? '', option: 'carrier' as const }];
    await expect(api.placeOrder({ addressId: 'ad1', deliverySelections: selection }, 'checkout-credit-0001')).rejects.toSatisfy(refusal(422));

    const conf = await api.placeOrder({ addressId: 'ad1', useCredit: true, paymentMethodId: 'pm_visa4242', deliverySelections: selection }, 'checkout-credit-0002');
    expect(sum.totalMinor).toBeGreaterThan(2500);
    expect(conf.creditMinor).toBe(sum.credit?.appliedMinor);
    expect(conf.paymentClientSecret).toContain('_secret_');
    expect((await api.getAccountCredit()).balanceMinor).toBe(2500 - (conf.creditMinor ?? 0));
    const placed = await api.confirmCheckoutPayment(conf.orderNumber);
    expect((await api.getOrder(placed.orderNumber)).payment?.creditMinor).toBe(conf.creditMinor);
  });
});
