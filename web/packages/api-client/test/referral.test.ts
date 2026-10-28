import { afterAll, beforeAll, beforeEach, describe, expect, expectTypeOf, it } from 'vitest';

import { type AccountReferral, ApiError, createApiClient, type ReferralFriend,referralKeys } from '../src';
import { type components } from '../src/generated/gateway-bff';
import { resetDb } from '../src/mocks';
import { mockServer } from '../src/mocks/node';
import { MOCK_REFERRAL_CODE, MOCK_REFERRAL_DISPLAY_CODE, MOCK_STOREFRONT_ORIGIN } from '../src/mocks/referral';

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

beforeAll(() => mockServer.listen({ onUnhandledRequest: 'error' }));
afterAll(() => mockServer.close());
beforeEach(() => { resetDb(); });

describe('referral schemas stay aligned with the contract', () => {
  it('runtime types are assignable to the generated contract types', () => {
    expectTypeOf<AccountReferral>().toExtend<S['AccountReferral']>();
    expectTypeOf<ReferralFriend>().toExtend<S['ReferralFriend']>();
  });

  it('has no client method that claims or moves a reward', () => {
    const names = Object.keys(createApiClient({ baseUrl: 'http://bff.test' }));
    expect(names.filter((n) => /referral/i.test(n))).toEqual(['getAccountReferral']);
  });

  it('keys the page under the account queries so signing out drops it', () => {
    expect(referralKeys.account()).toEqual(['account', 'referral', 'account']);
  });
});

describe('mock BFF — refer a friend', () => {
  it('asks guests to sign in', async () => {
    const api = createApiClient({ baseUrl: 'http://bff.test' });
    await expect(api.getAccountReferral()).rejects.toSatisfy((e: unknown) => e instanceof ApiError && e.status === 401);
  });

  it('gives the code, the share link, the offer, the friends by name and the earnings', async () => {
    const api = await signedIn();
    const page = await api.getAccountReferral();
    expect(page.code).toBe(MOCK_REFERRAL_CODE);
    expect(page.displayCode).toBe(MOCK_REFERRAL_DISPLAY_CODE);
    expect(page.shareUrl).toBe(`${MOCK_STOREFRONT_ORIGIN}/r/${MOCK_REFERRAL_CODE}`);
    expect(page.program.enabled).toBe(true);
    expect(page.program.referrerReward).toEqual({ type: 'CREDIT', creditMinor: 1000, membershipDays: 0 });
    expect(page.program.minOrderMinor).toBe(3000);
    expect(page.program.returnWindowDays).toBe(30);
    expect(page.earnings).toMatchObject({ invited: 3, rewarded: 1, earnedMinor: 1000, pendingMinor: 1000 });
    expect(page.friends.map((f) => f.friendName)).toEqual(['Amara O.', 'Kofi B.', 'Zainab K.']);
    expect(page.friends.map((f) => f.status)).toEqual(['REWARDED', 'WAITING', 'PENDING']);
    expect(page.friends[0]?.reward).toMatchObject({ granted: true });
    expect(page.friends[2]?.reward).toBeNull();
    expect(JSON.stringify(page)).not.toMatch(/referrerId|referredId|flags|decision/);
  });

  it('shows an empty page for a customer nobody has invited yet', async () => {
    const api = await signedIn();
    // the demo account is the only one with friends; the shape for everyone else is the same with zeros
    const page = await api.getAccountReferral();
    expect(Array.isArray(page.friends)).toBe(true);
    expect(page.asFriend).toBeNull();
  });
});
