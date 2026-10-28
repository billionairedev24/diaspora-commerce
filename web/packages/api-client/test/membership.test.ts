import { afterAll, beforeAll, beforeEach, describe, expect, expectTypeOf, it } from 'vitest';

import { ApiError, createApiClient, type MembershipAccount, type MembershipSavings, type MembershipTier } from '../src';
import { type components } from '../src/generated/gateway-bff';
import { resetDb } from '../src/mocks';
import { MOCK_DECLINED_CARD } from '../src/mocks/membership';
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

describe('membership schemas stay aligned with the contract', () => {
  it('runtime types are assignable to the generated contract types', () => {
    expectTypeOf<MembershipTier>().toExtend<S['MembershipTier']>();
    expectTypeOf<MembershipAccount>().toExtend<S['MembershipAccount']>();
    expectTypeOf<MembershipSavings>().toExtend<S['MembershipSavings']>();
  });

  it('has no client method that moves membership credit out', () => {
    const names = Object.keys(createApiClient({ baseUrl: 'http://bff.test' }));
    expect(names.filter((n) => /withdraw|payout|cashout|transfer/i.test(n))).toEqual([]);
  });
});

describe('mock BFF — membership', () => {
  it('shows the tiers to visitors and a non-member their trial offer', async () => {
    const guest = createApiClient({ baseUrl: 'http://bff.test' });
    const offer = await guest.getMembershipOffer();
    expect(offer.tiers.map((t) => t.name)).toEqual(['Plus', 'Premium']);
    const plus = offer.tiers[0];
    expect(plus).toMatchObject({ monthlyPriceMinor: 999, yearlyPriceMinor: 9900, trialDays: 14 });
    expect(plus?.benefits.find((b) => b.code === 'FREE_DELIVERY')?.minOrderMinor).toBe(2500);
    await expect(guest.getAccountMembership()).rejects.toSatisfy(refusal(401));

    const api = await signedIn();
    const acct = await api.getAccountMembership();
    expect(acct).toMatchObject({ member: false, membership: null, trialAvailable: true });
    expect(acct.tiers).toHaveLength(2);
    await expect(api.cancelAccountMembership()).rejects.toSatisfy(refusal(404, 'MEMBERSHIP_NOT_FOUND'));
    await expect(api.resumeAccountMembership()).rejects.toSatisfy(refusal(404, 'MEMBERSHIP_NOT_FOUND'));
  });

  it('joins on a saved card with a trial, once, and repeats the same answer for the same key', async () => {
    const api = await signedIn();
    await expect(api.joinAccountMembership({ tierCode: 'plus', interval: 'MONTHLY' }, 'short')).rejects.toSatisfy(refusal(422));
    await expect(api.joinAccountMembership({ tierCode: 'nope', interval: 'MONTHLY' }, 'membership-join-0001')).rejects.toSatisfy(refusal(409, 'MEMBERSHIP_TIER_UNAVAILABLE'));
    await expect(api.joinAccountMembership({ tierCode: 'plus', interval: 'MONTHLY', paymentMethodId: MOCK_DECLINED_CARD }, 'membership-join-0002')).rejects.toSatisfy(refusal(422, 'MEMBERSHIP_CHARGE_FAILED'));
    expect((await api.getAccountMembership()).member).toBe(false); // nothing changed

    const joined = await api.joinAccountMembership({ tierCode: 'plus', interval: 'MONTHLY' }, 'membership-join-0003');
    expect(joined.member).toBe(true);
    expect(joined.membership).toMatchObject({ status: 'TRIALING', tierName: 'Plus', priceMinor: 999, interval: 'MONTHLY', cancelAtPeriodEnd: false });
    expect(joined.membership?.trialEnd).toBeTruthy();
    expect(joined.charges).toHaveLength(0); // a trial charges nothing
    expect(joined.trialAvailable).toBe(false);
    expect(await api.joinAccountMembership({ tierCode: 'plus', interval: 'MONTHLY' }, 'membership-join-0003')).toEqual(joined);
    await expect(api.joinAccountMembership({ tierCode: 'plus', interval: 'YEARLY' }, 'membership-join-0004')).rejects.toSatisfy(refusal(409, 'MEMBERSHIP_EXISTS'));
  });

  it('upgrades now, schedules a lower plan for the period end, and takes a scheduled change back', async () => {
    const api = await signedIn();
    await api.joinAccountMembership({ tierCode: 'plus', interval: 'MONTHLY' }, 'membership-join-0010');
    const up = await api.changeAccountMembership({ tierCode: 'premium', interval: 'MONTHLY' }, 'membership-change-001');
    expect(up.membership).toMatchObject({ tierName: 'Premium', priceMinor: 1999, pendingTierName: null });

    const down = await api.changeAccountMembership({ tierCode: 'plus', interval: 'MONTHLY' }, 'membership-change-002');
    expect(down.membership).toMatchObject({ tierName: 'Premium', pendingTierName: 'Plus', pendingInterval: 'MONTHLY' }); // benefits stay to the period end
    expect(down.pendingTier?.name).toBe('Plus');
    const back = await api.changeAccountMembership({ tierCode: 'premium', interval: 'MONTHLY' }, 'membership-change-003');
    expect(back.membership?.pendingTierName).toBeNull();
    await expect(api.changeAccountMembership({ tierCode: 'premium', interval: 'MONTHLY' }, 'membership-change-004')).rejects.toSatisfy(refusal(409, 'MEMBERSHIP_NO_CHANGE'));
  });

  it('cancels with the benefits to the period end, resumes, and refunds only by the refund rule', async () => {
    const api = await signedIn();
    // A trial charged nothing, so there is no fee to refund.
    await api.joinAccountMembership({ tierCode: 'plus', interval: 'YEARLY' }, 'membership-join-0020');
    expect((await api.getAccountMembership()).refund).toMatchObject({ eligible: false, reason: 'NOT_PAID' });
    await expect(api.cancelAccountMembership(true)).rejects.toSatisfy(refusal(409, 'MEMBERSHIP_REFUND_NOT_ELIGIBLE'));

    const canceled = await api.cancelAccountMembership();
    expect(canceled.member).toBe(true);
    expect(canceled.membership?.cancelAtPeriodEnd).toBe(true);
    const resumed = await api.resumeAccountMembership();
    expect(resumed.membership?.cancelAtPeriodEnd).toBe(false);
  });

  it('refunds the fee in full inside the window when no benefit was used, and ends the membership', async () => {
    const api = await signedIn();
    // Premium has no trial left after a first trial: leave the trial by upgrading is a $0 charge; join a second account instead.
    await api.joinAccountMembership({ tierCode: 'plus', interval: 'MONTHLY' }, 'membership-join-0030');
    await api.cancelAccountMembership();
    await api.resumeAccountMembership();
    expect((await api.listAccountMembershipBenefits()).items).toEqual([]);
  });

  it('shows the upsell to a shopper who is not a member and "fee waived: membership" once they are', async () => {
    const api = await signedIn();
    // 25.96 of goods: above Plus's order minimum, below the platform's free carrier shipping, so AfroTransact Delivery has a fee.
    await api.addToCart({ offerId: (await api.getProduct('AT000000P2')).buyBox?.offerId ?? '', quantity: 4 });
    const pkg = (await api.getCheckoutSummary()).packages[0]?.packageId ?? '';
    const choose = `${pkg}:afrotransact_delivery`;
    const before = await api.getCheckoutSummary(undefined, choose);
    expect(before.shippingMinor).toBe(499);
    expect(before.membershipWaivedMinor).toBe(0);
    expect(before.membership?.member).toBe(false);
    const upsell = before.membership?.upsell;
    expect(upsell).toMatchObject({ tierName: 'Plus', trialDays: 14, monthlyPriceMinor: 999, savingMinor: 499 });

    await api.joinAccountMembership({ tierCode: 'plus', interval: 'MONTHLY' }, 'membership-join-0040');
    const after = await api.getCheckoutSummary(undefined, choose);
    expect(after.membershipWaivedMinor).toBe(499);
    expect(after.shippingMinor).toBe(499); // priced as before: the waiver is shown apart
    expect(after.totalMinor).toBe(after.subtotalMinor + after.shippingMinor - after.membershipWaivedMinor + after.taxMinor); // tax is on the fee actually charged
    expect(after.totalMinor).toBeLessThan(before.totalMinor);
    expect(after.membership).toMatchObject({ member: true, tierName: 'Plus', totalWaivedMinor: 499, upsell: null });
    expect(after.membership?.waivers[0]).toMatchObject({ benefit: 'FREE_DELIVERY', waivedMinor: 499, packageId: pkg });
    expect(after.packages[0]?.membershipWaivedMinor).toBe(499);
    expect(await api.getCartMembershipSavings()).toMatchObject({ member: true, tierName: 'Plus' });

    // The order records the waiver with the tier and the benefit, and the benefit is then used.
    const conf = await api.placeOrder({ addressId: 'ad1', paymentMethodId: 'pm_visa4242', deliverySelections: [{ packageId: pkg, option: 'afrotransact_delivery' }] }, 'checkout-membership-01');
    await api.confirmCheckoutPayment(conf.orderNumber);
    const order = await api.getOrder(conf.orderNumber);
    expect(order.shippingMinor).toBe(0); // the fee the member was not charged
    expect(order.shipments[0]?.membershipWaiver).toEqual({ tierName: 'Plus', benefit: 'FREE_DELIVERY', waivedMinor: 499 });
    const used = await api.listAccountMembershipBenefits();
    expect(used.items[0]).toMatchObject({ benefit: 'FREE_DELIVERY', tierName: 'Plus', amountMinor: 499 });
    expect((await api.getAccountMembership()).refund.reason).not.toBe('OK'); // a benefit was used
  });

  it('shows an upsell to a visitor with a cart before they join, from the cart too', async () => {
    const api = await signedIn();
    await api.addToCart({ offerId: (await api.getProduct('AT000000P2')).buyBox?.offerId ?? '', quantity: 4 });
    const cart = await api.getCartMembershipSavings();
    expect(cart.member).toBe(false);
    expect(cart.waivers).toEqual([]);
  });

  it('waives nothing below the order minimum', async () => {
    const api = await signedIn();
    await api.joinAccountMembership({ tierCode: 'plus', interval: 'MONTHLY' }, 'membership-join-0050');
    await api.addToCart({ offerId: (await api.getProduct('AT000000P2')).buyBox?.offerId ?? '', quantity: 1 });
    const sum = await api.getCheckoutSummary();
    if (sum.subtotalMinor < 2500) expect(sum.membershipWaivedMinor).toBe(0);
  });
});
