import { type Requester } from './http';
import { type AccountReferral, AccountReferralSchema } from './schemas/referral';

/**
 * Refer a friend of the gateway BFF (S322), spread into createApiClient. Rewards are granted by AfroTransact after the
 * friend's first delivered order and the return window: there is deliberately no method to claim or move anything.
 */
export function referralMethods(request: Requester) {
  return {
    /** My referral code and share link, the offer, my friends (by name) and what I earned. */
    getAccountReferral: (signal?: AbortSignal): Promise<AccountReferral> => request('/v1/account/referral', AccountReferralSchema, { signal })
  };
}
