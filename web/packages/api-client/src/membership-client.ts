import { type Requester } from './http';
import {
  type MembershipAccount,
  MembershipAccountSchema,
  type MembershipBenefitUsePage,
  MembershipBenefitUsePageSchema,
  type MembershipOffer,
  MembershipOfferSchema,
  type MembershipPlanRequest,
  type MembershipSavings,
  MembershipSavingsSchema
} from './schemas/membership';

/**
 * AfroTransact membership of the gateway BFF (S323), spread into createApiClient. Joining and changing plan are
 * idempotent per key (16 to 64 characters). Credit that a membership grants has no way out: nothing here pays it out.
 */
export function membershipMethods(request: Requester) {
  return {
    /** The tiers a shopper can join; works for visitors who are not signed in. */
    getMembershipOffer: (signal?: AbortSignal): Promise<MembershipOffer> => request('/v1/membership/tiers', MembershipOfferSchema, { signal }),

    /** My membership, what it gives, what I used and the refund rule. */
    getAccountMembership: (signal?: AbortSignal): Promise<MembershipAccount> => request('/v1/account/membership', MembershipAccountSchema, { signal }),

    /** Joins a tier on a saved card (a trial first when eligible). */
    joinAccountMembership: (body: MembershipPlanRequest, idempotencyKey: string): Promise<MembershipAccount> =>
      request('/v1/account/membership', MembershipAccountSchema, { method: 'POST', body, headers: { 'Idempotency-Key': idempotencyKey } }),

    /** Upgrades now (paying the difference) or schedules a lower tier or another interval for the end of the period. */
    changeAccountMembership: (body: MembershipPlanRequest, idempotencyKey: string): Promise<MembershipAccount> =>
      request('/v1/account/membership', MembershipAccountSchema, { method: 'PUT', body, headers: { 'Idempotency-Key': idempotencyKey } }),

    /** Stops renewing (benefits run to the end of the period); with `refund` the latest fee goes back to the card and it ends now. */
    cancelAccountMembership: (refund = false): Promise<MembershipAccount> =>
      request('/v1/account/membership/cancel', MembershipAccountSchema, { method: 'POST', body: refund ? { refund: true } : {} }),

    /** Takes back a cancellation or a scheduled change. */
    resumeAccountMembership: (): Promise<MembershipAccount> => request('/v1/account/membership/resume', MembershipAccountSchema, { method: 'POST' }),

    /** The benefits I used, newest first. */
    listAccountMembershipBenefits: (limit?: number, signal?: AbortSignal): Promise<MembershipBenefitUsePage> =>
      request('/v1/account/membership/benefits', MembershipBenefitUsePageSchema, { query: { limit: limit === undefined ? undefined : String(limit) }, signal }),

    /** What membership does for my cart; if I am not a member, "Join Plus to save $X on this order". */
    getCartMembershipSavings: (signal?: AbortSignal): Promise<MembershipSavings> => request('/v1/cart/membership', MembershipSavingsSchema, { signal })
  };
}
