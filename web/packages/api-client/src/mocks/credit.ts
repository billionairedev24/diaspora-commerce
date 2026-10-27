/* Mock BFF for AfroTransact credit and gift cards (S320, S321): the same routes, shapes and refusals as
   contracts/openapi/gateway-bff.yaml, tag Credit. Credit has no way out here either: no cash-out, payout, card refund
   or transfer. State per demo account on the mock database (resetDb() restores it). */
import { http } from 'msw';
import { z } from 'zod';

import {
  type CheckoutCredit,
  type CreditEntry,
  type GiftCard,
  GiftCardPurchaseRequestSchema,
  GiftCardRedeemRequestSchema
} from '../schemas/credit';
import { body, json, requireUser, route } from './http-kit';
import { MockProblem } from './service';
import { account, type AccountState } from './shopper-state';

/** The demo account's gift card: redeem it once for $50.00. */
export const MOCK_GIFT_CODE = 'ABCD-EFGH-JKLM-2345';
export const MOCK_GIFT_AMOUNT_MINOR = 5000;
/** A code that answers 429, so browser tests can see the "too many wrong codes" state. */
export const MOCK_RATE_LIMITED_CODE = 'ZZZZ-ZZZZ-ZZZZ-2222';
/** Stripe cannot charge less than 0.50: credit leaves at least this on the card, or pays everything. */
const MIN_CARD_MINOR = 50;
const MAX_WRONG_CODES = 5;

export interface MockCreditState {
  balanceMinor: number;
  /** Newest first. */
  entries: CreditEntry[];
  giftCards: GiftCard[];
  redeemedCodes: Set<string>;
  wrongCodes: number;
  purchases: Map<string, GiftCard & { clientSecret: string }>;
}

const states = new WeakMap<AccountState, MockCreditState>();

export function creditState(a: AccountState, userId: string): MockCreditState {
  let s = states.get(a);
  if (!s) {
    const day = (n: number) => new Date(Date.now() - n * 86_400_000).toISOString();
    s = {
      balanceMinor: userId === 'u_adaeze' ? 2500 : 0,
      entries: userId === 'u_adaeze'
        ? [
            { id: 'cr-3', kind: 'SPEND', source: 'ORDER', amountMinor: -5000, currency: 'USD', balanceAfterMinor: 2500, createdAt: day(3) },
            { id: 'cr-2', kind: 'GRANT', source: 'GIFT_CARD', amountMinor: 5000, currency: 'USD', balanceAfterMinor: 7500, createdAt: day(9) },
            { id: 'cr-1', kind: 'GRANT', source: 'REFERRAL', amountMinor: 2500, currency: 'USD', balanceAfterMinor: 2500, createdAt: day(30) }
          ]
        : [],
      giftCards: [],
      redeemedCodes: new Set(),
      wrongCodes: 0,
      purchases: new Map()
    };
    states.set(a, s);
  }
  return s;
}

/** How an order total is paid when credit is used: credit first, never more than the balance, at least 0.50 for the card. */
export function creditSplit(balanceMinor: number, totalMinor: number): { appliedMinor: number; cardDueMinor: number } {
  let applied = Math.min(balanceMinor, totalMinor);
  const rest = totalMinor - applied;
  if (rest > 0 && rest < MIN_CARD_MINOR) applied = Math.max(0, totalMinor - MIN_CARD_MINOR);
  return { appliedMinor: applied, cardDueMinor: totalMinor - applied };
}

export function checkoutCredit(s: MockCreditState, totalMinor: number): CheckoutCredit | undefined {
  if (s.balanceMinor <= 0 || totalMinor <= 0) return undefined;
  const { appliedMinor, cardDueMinor } = creditSplit(s.balanceMinor, totalMinor);
  return { availableMinor: s.balanceMinor, appliedMinor, cardDueMinor };
}

/** Spends credit on an order (the real one happens in payments, once per order). */
export function spendCredit(s: MockCreditState, totalMinor: number, now: Date): number {
  const { appliedMinor } = creditSplit(s.balanceMinor, totalMinor);
  if (appliedMinor <= 0) return 0;
  s.balanceMinor -= appliedMinor;
  s.entries.unshift({ id: `cr-${s.entries.length + 1}-${now.getTime()}`, kind: 'SPEND', source: 'ORDER', amountMinor: -appliedMinor, currency: 'USD', balanceAfterMinor: s.balanceMinor, createdAt: now.toISOString() });
  return appliedMinor;
}

const normalise = (code: string) => code.toUpperCase().replace(/[\s-]/g, '');
const GIFT_CONFIG = {
  minMinor: 1000,
  maxMinor: 50_000,
  currency: 'USD',
  presetsMinor: [2500, 5000, 10_000, 20_000],
  terms: {} as Record<string, string>
};

const mine = (ctx: Parameters<typeof requireUser>[0]) => {
  const u = requireUser(ctx);
  return creditState(account(u.id), u.id);
};

export const creditHandlers = [
  http.get('*/v1/account/credit', route((ctx) => {
    const s = mine(ctx);
    const q = new URL(ctx.request.url).searchParams;
    const limit = Math.min(100, Math.max(1, Number(q.get('limit') ?? 20) || 20));
    const before = q.get('before');
    if (before && Number.isNaN(Date.parse(before))) throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { errors: { before: 'Use the nextBefore of the previous page' } });
    const rows = s.entries.filter((e) => !before || e.createdAt < before);
    const page = rows.slice(0, limit);
    return json({ balanceMinor: s.balanceMinor, currency: 'USD', entries: page, nextBefore: rows.length > limit ? (page[page.length - 1]?.createdAt ?? null) : null });
  })),

  http.get('*/v1/gift-cards/config', route((ctx) => {
    requireUser(ctx);
    return json(GIFT_CONFIG);
  })),
  http.get('*/v1/account/gift-cards', route((ctx) => json({ items: mine(ctx).giftCards, nextCursor: null }))),
  http.post('*/v1/account/gift-cards', route(async (ctx) => {
    const s = mine(ctx);
    const key = ctx.request.headers.get('idempotency-key') ?? '';
    if (key.length < 16 || key.length > 64) throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'Send a 16–64 character Idempotency-Key header.', { errors: { 'Idempotency-Key': 'Required' } });
    const replay = s.purchases.get(key);
    if (replay) return json({ ...replay, publishableKey: 'pk_test_mock' }, 201);
    const req = await body(ctx.request, GiftCardPurchaseRequestSchema);
    const errors: Record<string, string> = {};
    if (req.amountMinor < GIFT_CONFIG.minMinor || req.amountMinor > GIFT_CONFIG.maxMinor) errors.amountMinor = 'Between $10.00 and $500.00';
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]{2,}$/.test(req.recipientEmail)) errors.recipientEmail = "Enter the recipient's email address";
    if (Object.keys(errors).length) throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { errors });
    const now = ctx.now.toISOString();
    const card = {
      id: crypto.randomUUID(), status: 'PENDING_PAYMENT' as const, amountMinor: req.amountMinor, currency: 'USD', recipientEmail: req.recipientEmail,
      recipientName: req.recipientName ?? null, senderName: req.senderName ?? null, message: req.message ?? null, sendAt: req.sendAt ?? now,
      deliveryStatus: 'SCHEDULED' as const, sentAt: null, redeemed: false, createdAt: now, clientSecret: `pi_gift_${s.purchases.size + 1}_secret_mock`
    };
    s.purchases.set(key, card);
    return json({ ...card, publishableKey: 'pk_test_mock' }, 201);
  })),
  http.post('*/v1/account/gift-cards/:id/confirm', route((ctx, p) => {
    const s = mine(ctx);
    const bought = [...s.purchases.values()].find((c) => c.id === p.id);
    if (!bought) throw new MockProblem(404, 'NOT_FOUND', 'Not found', "We couldn't find that.");
    const sentNow = Date.parse(bought.sendAt) <= ctx.now.getTime();
    const paid: GiftCard = { ...bought, status: 'ACTIVE', deliveryStatus: sentNow ? 'SENT' : 'SCHEDULED', sentAt: sentNow ? ctx.now.toISOString() : null };
    if (!s.giftCards.some((c) => c.id === paid.id)) s.giftCards.unshift(paid);
    return json(paid);
  })),
  http.post('*/v1/account/gift-cards/redeem', route(async (ctx) => {
    const s = mine(ctx);
    const { code } = await body(ctx.request, GiftCardRedeemRequestSchema);
    if (s.wrongCodes >= MAX_WRONG_CODES || normalise(code) === normalise(MOCK_RATE_LIMITED_CODE)) {
      throw new MockProblem(429, 'RATE_LIMITED', 'Too many requests', 'Please wait a moment and try again.', { retryAfter: 600 });
    }
    if (normalise(code) !== normalise(MOCK_GIFT_CODE)) {
      s.wrongCodes += 1;
      throw new MockProblem(404, 'GIFT_CARD_INVALID', 'Gift card not found', 'That code is not valid. Check it and try again.');
    }
    if (s.redeemedCodes.has(MOCK_GIFT_CODE)) throw new MockProblem(409, 'GIFT_CARD_REDEEMED', 'Already redeemed', 'This gift card was already redeemed.');
    s.redeemedCodes.add(MOCK_GIFT_CODE);
    s.balanceMinor += MOCK_GIFT_AMOUNT_MINOR;
    s.entries.unshift({ id: `cr-gc-${ctx.now.getTime()}`, kind: 'GRANT', source: 'GIFT_CARD', amountMinor: MOCK_GIFT_AMOUNT_MINOR, currency: 'USD', balanceAfterMinor: s.balanceMinor, createdAt: ctx.now.toISOString() });
    return json({ redeemedMinor: MOCK_GIFT_AMOUNT_MINOR, balanceMinor: s.balanceMinor, currency: 'USD' });
  })),

  http.post('*/v1/account/orders/:orderNumber/credit-refunds', route((ctx, p) => {
    const a = account(requireUser(ctx).id);
    const order = a.orders.find((o) => o.orderNumber === p.orderNumber);
    if (!order) throw new MockProblem(404, 'ORDER_NOT_FOUND', 'Order not found', "We couldn't find that order.");
    const credit = order.creditMinor ?? 0;
    const cardPart = order.totalMinor - credit;
    if (cardPart <= 0) throw new MockProblem(409, 'NO_CARD_PORTION', 'Nothing to change', 'This order was paid with credit only, so its refunds already come back as credit.');
    order.cardRefundsAsCredit = true;
    return json({ orderNumber: order.orderNumber, cardRefundsAsCredit: true, cardPartMinor: cardPart, currency: 'USD' });
  }))
];

export const CreditMockSchemas = { GiftCardRedeem: z.object({ code: z.string() }) };
