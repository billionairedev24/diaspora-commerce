/* Mock BFF for Refer a friend (S322): the same route, shape and sign-in refusal as contracts/openapi/gateway-bff.yaml,
   tag Referral. The demo account has a code, one friend who earned them credit, one waiting for the return window and
   one who has not ordered yet. Nothing here claims or moves credit. State per demo account (resetDb() restores it). */
import { http } from 'msw';

import { type AccountReferral, type ReferralFriend } from '../schemas/referral';
import { json, requireUser, route } from './http-kit';
import { account, type AccountState } from './shopper-state';

export const MOCK_REFERRAL_CODE = 'KDM7XR3Q';
export const MOCK_REFERRAL_DISPLAY_CODE = 'KDM7-XR3Q';
/** Where share links open in the mock (the storefront's own origin in the browser). */
export const MOCK_STOREFRONT_ORIGIN = 'https://shop.afrotransact.test';

const states = new WeakMap<AccountState, AccountReferral>();

function referralState(a: AccountState, userId: string): AccountReferral {
  let s = states.get(a);
  if (!s) {
    const day = (n: number) => new Date(Date.now() + n * 86_400_000).toISOString();
    const reward = (granted: boolean) => ({ terms: { type: 'CREDIT' as const, creditMinor: 1000, membershipDays: 0 }, granted, grantedAt: granted ? day(-10) : null });
    const friends: ReferralFriend[] = userId === 'u_adaeze'
      ? [
          { id: 'rf-1', friendName: 'Amara O.', status: 'REWARDED', signedUpAt: day(-60), expiresAt: day(30), eligibleAt: day(-10), reward: reward(true) },
          { id: 'rf-2', friendName: 'Kofi B.', status: 'WAITING', signedUpAt: day(-20), expiresAt: day(70), eligibleAt: day(12), reward: reward(false) },
          { id: 'rf-3', friendName: 'Zainab K.', status: 'PENDING', signedUpAt: day(-4), expiresAt: day(86), eligibleAt: null, reward: null }
        ]
      : [];
    s = {
      code: MOCK_REFERRAL_CODE,
      displayCode: MOCK_REFERRAL_DISPLAY_CODE,
      shareUrl: `${MOCK_STOREFRONT_ORIGIN}/r/${MOCK_REFERRAL_CODE}`,
      program: {
        enabled: true,
        referrerReward: { type: 'CREDIT', creditMinor: 1000, membershipDays: 0 },
        referredReward: { type: 'CREDIT', creditMinor: 1000, membershipDays: 0 },
        minOrderMinor: 3000,
        returnWindowDays: 30,
        validityDays: 90,
        currency: 'USD'
      },
      earnings: friends.length
        ? { invited: 3, open: 2, rewarded: 1, earnedMinor: 1000, membershipDays: 0, pendingMinor: 1000 }
        : { invited: 0, open: 0, rewarded: 0, earnedMinor: 0, membershipDays: 0, pendingMinor: 0 },
      friends,
      asFriend: null
    };
    states.set(a, s);
  }
  return s;
}

export const referralHandlers = [
  http.get('*/v1/account/referral', route((ctx) => {
    const u = requireUser(ctx);
    return json(referralState(account(u.id), u.id));
  }))
];
