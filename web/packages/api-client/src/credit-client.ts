import { type Requester } from './http';
import {
  type CreditAccount,
  CreditAccountSchema,
  type GiftCard,
  type GiftCardConfig,
  GiftCardConfigSchema,
  type GiftCardPage,
  GiftCardPageSchema,
  type GiftCardPurchase,
  type GiftCardPurchaseRequest,
  GiftCardPurchaseSchema,
  type GiftCardRedemption,
  GiftCardRedemptionSchema,
  GiftCardSchema
} from './schemas/credit';

const enc = encodeURIComponent;

export interface CreditHistoryQuery {
  /** Entries older than this (the previous page's `nextBefore`). */
  before?: string;
  limit?: number;
}

/**
 * AfroTransact credit and gift cards of the gateway BFF (S320, S321), spread into createApiClient.
 * There is deliberately no method that moves credit out: no cash-out, payout, card refund or transfer.
 */
export function creditMethods(request: Requester) {
  return {
    /** My credit balance and history, newest first. */
    getAccountCredit: (q: CreditHistoryQuery = {}, signal?: AbortSignal): Promise<CreditAccount> =>
      request('/v1/account/credit', CreditAccountSchema, { query: { before: q.before, limit: q.limit === undefined ? undefined : String(q.limit) }, signal }),

    /** Limits, preset amounts and terms for the buy page (staff settings). */
    getGiftCardConfig: (signal?: AbortSignal): Promise<GiftCardConfig> => request('/v1/gift-cards/config', GiftCardConfigSchema, { signal }),

    /** The cards I bought (never with the code). */
    listMyGiftCards: (signal?: AbortSignal): Promise<GiftCardPage> => request('/v1/account/gift-cards', GiftCardPageSchema, { signal }),

    /** Starts a purchase: confirm the payment with Stripe Elements using `clientSecret`, then `confirmGiftCard`. Idempotent per key. */
    buyGiftCard: (body: GiftCardPurchaseRequest, idempotencyKey: string): Promise<GiftCardPurchase> =>
      request('/v1/account/gift-cards', GiftCardPurchaseSchema, { method: 'POST', body, headers: { 'Idempotency-Key': idempotencyKey } }),

    /** After the payment was confirmed: capture it and issue the card. */
    confirmGiftCard: (giftCardId: string): Promise<GiftCard> => request(`/v1/account/gift-cards/${enc(giftCardId)}/confirm`, GiftCardSchema, { method: 'POST' }),

    /** Redeems a code into my credit, once. Wrong codes are counted; over the limit the answer is 429 with Retry-After. */
    redeemGiftCard: (code: string): Promise<GiftCardRedemption> =>
      request('/v1/account/gift-cards/redeem', GiftCardRedemptionSchema, { method: 'POST', body: { code } })
  };
}
