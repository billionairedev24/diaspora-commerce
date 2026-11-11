/* Mock BFF for "Ask for a delivery quote" (listing rules D20): the same routes, shapes and refusals as
   contracts/openapi/gateway-bff.yaml, tag Delivery quotes. State per demo account on the mock database (resetDb()
   restores it). Customer care is simulated by `decideMockQuote`: tests (and the demo) agree or decline a request the way
   the staff console does, then the shopper's pages and checkout follow. */
import { http } from 'msw';

import { NewQuoteRequestBodySchema,type QuoteRequest } from '../schemas/delivery-quotes';
import { type DeliverySelection } from '../schemas/shopper';
import { body, json, requireUser, route } from './http-kit';
import { MockProblem } from './service';
import { account, type AccountState } from './shopper-state';

/** How long an agreed quote can be used (orders' default). */
export const MOCK_QUOTE_VALIDITY_HOURS = 48;

const MESSAGES: Record<QuoteRequest['status'], string> = {
  REQUESTED: 'We have your request. A member of our team will price the delivery and let you know.',
  IN_PROGRESS: 'Our team is working out the delivery price for your address.',
  READY: 'Your delivery quote is ready. Go back to checkout to use it before it expires.',
  DECLINED: "We can't offer delivery to this address right now. You can choose pickup instead.",
  EXPIRED: 'This quote expired before it was used. Ask for a new one.',
  USED: 'This quote was used for an order.'
};
const LABELS: Record<QuoteRequest['status'], QuoteRequest['statusLabel']> = {
  REQUESTED: 'Requested',
  IN_PROGRESS: 'Being quoted',
  READY: 'Quote ready',
  DECLINED: 'Declined',
  EXPIRED: 'Expired',
  USED: 'Used'
};

interface MockQuote {
  quote: QuoteRequest;
  /** Same cart and address returns the same request. */
  key: string;
}

const states = new WeakMap<AccountState, MockQuote[]>();
const quotesOf = (a: AccountState): MockQuote[] => {
  let s = states.get(a);
  if (!s) {
    s = [];
    states.set(a, s);
  }
  return s;
};

const mine = (ctx: Parameters<typeof requireUser>[0]) => {
  const u = requireUser(ctx);
  return { u, a: account(u.id), quotes: quotesOf(account(u.id)) };
};

/** A ready quote that ran past its validity reads as expired at once (orders does the same). */
function shown(q: QuoteRequest, now: Date): QuoteRequest {
  if (q.status === 'READY' && q.expiresAt && Date.parse(q.expiresAt) <= now.getTime()) return { ...q, status: 'EXPIRED', statusLabel: 'Expired', usable: false, message: MESSAGES.EXPIRED };
  return q;
}

/** Customer care answers a request: agree an amount (READY, valid 48 hours), decline, or start working on it. */
export function decideMockQuote(userId: string, id: string, answer: { status: 'IN_PROGRESS' } | { status: 'DECLINED' } | { status: 'READY'; amountMinor: number; expiresAt?: string }): QuoteRequest {
  const found = quotesOf(account(userId)).find((x) => x.quote.id === id);
  if (!found) throw new Error(`No mock quote ${id}`);
  const now = new Date();
  const base = { ...found.quote, status: answer.status, statusLabel: LABELS[answer.status], message: MESSAGES[answer.status], updatedAt: now.toISOString() };
  found.quote =
    answer.status === 'READY'
      ? { ...base, amountMinor: answer.amountMinor, expiresAt: answer.expiresAt ?? new Date(now.getTime() + MOCK_QUOTE_VALIDITY_HOURS * 3_600_000).toISOString(), usable: Date.parse(answer.expiresAt ?? '9999') > now.getTime() }
      : { ...base, usable: false };
  return found.quote;
}

/**
 * Checkout with a quote: it must be the shopper's own, ready and not expired, for this address, and is used once. Returns the
 * agreed amount (it becomes the order's shipping), or undefined when no selection names a quote.
 */
export function redeemMockQuote(a: AccountState, selections: DeliverySelection[], addressId: string, now: Date): number | undefined {
  const named = selections.filter((s) => s.quoteId);
  if (!named.length) return undefined;
  const ids = new Set(named.map((s) => s.quoteId));
  if (ids.size > 1) throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'Use one delivery quote per order.', { errors: { deliverySelections: 'Use one delivery quote per order' } });
  const found = quotesOf(a).find((x) => x.quote.id === [...ids][0]);
  const void_ = (reason: string, detail: string) => new MockProblem(422, 'DELIVERY_QUOTE_VOID', 'Delivery quote can’t be used', detail, { reason });
  if (!found) throw void_('not_found', 'We can’t find that delivery quote. Ask for a new one.');
  const q = shown(found.quote, now);
  if (q.status === 'USED') throw void_('used', 'This delivery quote has already been used. Ask for a new one.');
  if (q.status === 'EXPIRED') throw void_('expired', 'This delivery quote has expired. Ask for a new one.');
  if (q.status !== 'READY') throw void_('not_ready', 'This delivery quote isn’t ready yet. We’ll let you know when it is.');
  if (found.key.split('|')[0] !== addressId) throw void_('changed', 'Your cart or delivery address changed since this quote was agreed, so it no longer applies. Ask for a new quote.');
  found.quote = { ...found.quote, status: 'USED', statusLabel: 'Used', usable: false, message: MESSAGES.USED, updatedAt: now.toISOString() };
  return found.quote.amountMinor ?? 0;
}

export const deliveryQuoteHandlers = [
  http.post('*/v1/me/delivery-quotes', route(async (ctx) => {
    const { a, quotes } = mine(ctx);
    const req = await body(ctx.request, NewQuoteRequestBodySchema);
    const addr = a.addresses.find((x) => x.id === req.addressId);
    if (!addr) throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { errors: { addressId: 'Choose a delivery address' } });
    const key = `${req.addressId}|${JSON.stringify(req.lines ?? [])}`;
    const same = quotes.find((x) => x.key === key && ['REQUESTED', 'IN_PROGRESS', 'READY'].includes(x.quote.status));
    if (same) return json(shown(same.quote, ctx.now));
    if (quotes.filter((x) => Date.parse(x.quote.createdAt) > ctx.now.getTime() - 3_600_000).length >= 5)
      throw new MockProblem(429, 'RATE_LIMITED', 'Too many requests', 'Please wait a moment and try again.', { retryAfter: 3600 });
    const now = ctx.now.toISOString();
    const quote: QuoteRequest = {
      id: crypto.randomUUID(), reference: `DQ-2026-${String(quotes.length + 1).padStart(6, '0')}`, status: 'REQUESTED', statusLabel: 'Requested', amountMinor: null, currency: 'USD',
      expiresAt: null, usable: false, place: `${addr.city}, ${addr.state} ${addr.zip}`, packages: [], lines: [], contact: req.contact ?? 'EMAIL', note: req.note?.trim() || null,
      createdAt: now, updatedAt: now, message: MESSAGES.REQUESTED
    };
    quotes.unshift({ quote, key });
    return json(quote);
  })),
  http.get('*/v1/me/delivery-quotes', route((ctx) => json({ items: mine(ctx).quotes.map((x) => shown(x.quote, ctx.now)) }))),
  http.get('*/v1/me/delivery-quotes/:id', route((ctx, p) => {
    const found = mine(ctx).quotes.find((x) => x.quote.id === p.id);
    if (!found) throw new MockProblem(404, 'NOT_FOUND', 'Not found', "We couldn't find that quote request.");
    return json(shown(found.quote, ctx.now));
  }))
];
