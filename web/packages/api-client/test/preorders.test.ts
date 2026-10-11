import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import {
  ApiError,
  cartSplit,
  countdownBlocks,
  countdownParts,
  createApiClient,
  formatCountdownShort,
  preorderItemState,
  preorderKeys,
  preorderOptions,
  type PreorderResolution
} from '../src';
import { resetDb } from '../src/mocks';
import { mockServer } from '../src/mocks/node';

function browserLikeClient() {
  let cookie = '';
  const fetchLike = async (req: Request) => {
    if (cookie) req.headers.set('cookie', cookie);
    const res = await fetch(req);
    const set = res.headers.get('set-cookie');
    if (set) cookie = set.split(';')[0] ?? '';
    return res;
  };
  const api = createApiClient({ baseUrl: 'http://bff.test', fetch: fetchLike });
  const preview = async (body: Record<string, unknown>) => {
    const res = await fetchLike(new Request('http://bff.test/v1/mock/preorder-preview', { method: 'PUT', body: JSON.stringify(body), headers: { 'Content-Type': 'application/json' } }));
    expect(res.ok).toBe(true);
  };
  return { api, preview };
}

const JOLLOF = 'AT000000P2'; // Groceries: pre-order eligible
const SHEA = 'AT00000P15'; // Beauty: ships now

beforeAll(() => mockServer.listen({ onUnhandledRequest: 'error' }));
afterAll(() => mockServer.close());
beforeEach(() => { resetDb(); });

describe('pre-order helpers', () => {
  it('splits a countdown and formats the ribbon chip and hero blocks', () => {
    const now = Date.UTC(2026, 9, 19, 19, 46, 51);
    const c = countdownParts(now + 3 * 86_400_000 + 4 * 3_600_000 + 12 * 60_000 + 9_000, now);
    expect(formatCountdownShort(c)).toBe('3d 04h 12m 09s');
    expect(countdownBlocks(c)).toEqual(['3', '04', '12', '09']);
    expect(countdownParts(now - 5000, now).remainingMs).toBe(0);
  });

  it('cart split: door fee from the window, pickup free, other packages at their quoted rate; locked when not open', () => {
    const terms = { doorFeeMinor: 799, pickupFeeMinor: 0 };
    const pre = (fulfilment: 'DOOR' | 'PICKUP', stage: 'OPEN' | 'UPCOMING' | 'CLOSED' = 'OPEN') => ({
      preorder: { ...terms, fulfilment, stage },
      lines: [{ selected: true, quantity: 2, lineTotalMinor: 2598 }, { selected: true, quantity: 1, lineTotalMinor: 1449 }],
      deliveryOptions: [{ feeMinor: 799 }]
    });
    const now = { preorder: null, lines: [{ selected: true, quantity: 1, lineTotalMinor: 1599 }, { selected: false, quantity: 1, lineTotalMinor: 999 }], deliveryOptions: [{ feeMinor: 1499 }] };
    const door = cartSplit([pre('DOOR'), now]);
    expect(door).toMatchObject({ itemCount: 4, itemsMinor: 5646, preorderDeliveryMinor: 799, otherShippingMinor: 1499, totalMinor: 7944, hasPreorder: true, hasOther: true, locked: false });
    expect(cartSplit([pre('PICKUP'), now]).preorderDeliveryMinor).toBe(0);
    expect(cartSplit([pre('PICKUP'), now]).totalMinor).toBe(7145);
    expect(cartSplit([pre('DOOR', 'UPCOMING')]).locked).toBe(true);
    expect(cartSplit([pre('DOOR', 'CLOSED')]).locked).toBe(true);
    expect(preorderOptions({ door: true, pickup: false, doorFeeMinor: 999, pickupFeeMinor: 0, currency: 'CAD', pickupWindow: '10am–2pm', allowedMethods: ['CARD'] })).toEqual([{ fulfilment: 'DOOR', feeMinor: 999 }]);
  });

  it('badge logic: eligible items are pre-order only where the pre-order is on; otherwise ships now, metro or waitlist', () => {
    const base: PreorderResolution = {
      place: { country: 'US', region: 'TX', city: 'Lubbock' }, metro: false, covered: true, on: true, stage: 'OPEN', countdownTo: null, window: null, fallback: 'SHIP',
      placements: { ribbon: true, hero: true, badge: true, pdp: true, cart: true, family: true }, supportPhone: ''
    };
    expect(preorderItemState(true, base)).toEqual({ kind: 'preorder', stage: 'OPEN', locked: false, showBadge: true });
    expect(preorderItemState(true, { ...base, stage: 'UPCOMING' })).toMatchObject({ kind: 'preorder', locked: true });
    expect(preorderItemState(true, { ...base, placements: { ...base.placements, badge: false } })).toMatchObject({ showBadge: false });
    expect(preorderItemState(false, base)).toEqual({ kind: 'ships-now' });
    expect(preorderItemState(true, { ...base, metro: true, covered: false, on: false, stage: null })).toEqual({ kind: 'metro' });
    expect(preorderItemState(true, { ...base, on: false, fallback: 'WAITLIST' })).toEqual({ kind: 'waitlist' });
    expect(preorderItemState(true, { ...base, on: false })).toEqual({ kind: 'ships-now' });
    expect(preorderItemState(true, undefined)).toEqual({ kind: 'none' });
  });

  it('query keys are hierarchical', () => {
    expect(preorderKeys.resolution()).toEqual(['preorders', 'resolution', 'delivery-location']);
    expect(preorderKeys.resolution({ country: 'CA', region: 'ON', city: 'Toronto' })).toEqual(['preorders', 'resolution', 'CA/ON/Toronto']);
    expect(preorderKeys.open().slice(0, 1)).toEqual(preorderKeys.all);
  });
});

describe('pre-orders (mock BFF) — every storefront state', () => {
  it('resolves Houston as a metro, Lubbock open, Amarillo closed, El Paso upcoming, Toronto upcoming in CAD', async () => {
    const { api } = browserLikeClient();
    expect(await api.resolvePreorder()).toMatchObject({ metro: true, on: false, window: null });
    await api.setDeliveryLocation({ zip: '79401' });
    const lbk = await api.resolvePreorder();
    expect(lbk).toMatchObject({ on: true, stage: 'OPEN', place: { city: 'Lubbock', region: 'TX' }, window: { code: 'LBK-7', doorFeeMinor: 799, pickupFeeMinor: 0, currency: 'USD', pickupWindow: '10am–2pm', allowedMethods: ['CARD', 'WALLET', 'ACH'] } });
    expect(lbk.window?.count).toBe(136);
    expect(new Date(lbk.countdownTo ?? '').getTime()).toBe(new Date(lbk.window?.closesAt ?? '').getTime());
    await api.setDeliveryLocation({ zip: '79101' });
    expect(await api.resolvePreorder()).toMatchObject({ on: true, stage: 'CLOSED', window: { code: 'AMA-4', pickup: false } });
    await api.setDeliveryLocation({ zip: '79901' });
    expect(await api.resolvePreorder()).toMatchObject({ on: true, stage: 'UPCOMING', window: { code: 'ELP-3', count: null } });
    // Midland–Odessa is still a draft: shoppers there see no pre-order.
    await api.setDeliveryLocation({ zip: '79701' });
    expect(await api.resolvePreorder()).toMatchObject({ covered: false, on: false });
    expect(await api.resolvePreorder({ country: 'CA', region: 'ON', city: 'Toronto' })).toMatchObject({ on: true, stage: 'UPCOMING', window: { code: 'TOR-1', currency: 'CAD', doorFeeMinor: 999 } });
  });

  it('lists open and upcoming windows (no drafts, no delivered) for the /pre-orders page', async () => {
    const { api } = browserLikeClient();
    const list = await api.getOpenPreorders();
    expect(list.windows.map((w) => [w.code, w.stage])).toEqual([['LBK-7', 'OPEN'], ['ELP-3', 'UPCOMING'], ['TOR-1', 'UPCOMING']]);
    expect(list.windows[0]).toMatchObject({ zip: '79401', place: { city: 'Lubbock' } });
    expect(list.windows[2]).toMatchObject({ zip: null, where: 'Toronto, ON · Canada · Mississauga, ON · Canada · Brampton, ON · Canada' });
  });

  it('splits the cart into a pre-order package with door/pickup and a ships-now package', async () => {
    const { api } = browserLikeClient();
    await api.setDeliveryLocation({ zip: '79401' });
    const jollof = await api.getProduct(JOLLOF);
    expect(jollof.product.preorderEligible).toBe(true);
    const shea = await api.getProduct(SHEA);
    expect(shea.product.preorderEligible).toBe(false);
    await api.addToCart({ offerId: jollof.buyBox?.offerId ?? '', quantity: 2 });
    let cart = await api.addToCart({ offerId: shea.buyBox?.offerId ?? '', quantity: 1 });
    const pre = cart.packages.find((p) => p.preorder);
    expect(pre?.preorder).toMatchObject({ windowId: 'lbk-7', stage: 'OPEN', fulfilment: 'DOOR', options: [{ fulfilment: 'DOOR', feeMinor: 799 }, { fulfilment: 'PICKUP', feeMinor: 0 }] });
    expect(pre?.lines.map((l) => l.atin)).toEqual([JOLLOF]);
    expect(cart.packages.filter((p) => !p.preorder).flatMap((p) => p.lines.map((l) => l.atin))).toEqual([SHEA]);
    cart = await api.setPreorderFulfilment({ windowId: 'lbk-7', fulfilment: 'PICKUP' });
    expect(cart.packages.find((p) => p.preorder)?.preorder?.fulfilment).toBe('PICKUP');
    expect(cart.packages.find((p) => p.preorder)?.deliveryOptions[0]).toMatchObject({ key: 'pickup', feeMinor: 0 });
    // Back in a metro the same lines are ordinary packages again.
    await api.setDeliveryLocation({ zip: '77042' });
    expect((await api.getCart()).packages.some((p) => p.preorder)).toBe(false);
  });

  it('blocks checkout while the window is not open and confirms a pre-order with its number and attribution', async () => {
    const { api, preview } = browserLikeClient();
    const r = await api.signIn({ email: 'adaeze@example.com', password: 'shop2026' });
    if (r.status === 'mfa_required') await api.verifyMfa({ challengeId: r.challengeId, code: '246810' });
    await api.setDeliveryLocation({ zip: '79401' });
    const jollof = await api.getProduct(JOLLOF);
    await api.addToCart({ offerId: jollof.buyBox?.offerId ?? '', quantity: 1 });
    await api.selectAllCartLines(true);
    const summary = await api.getCheckoutSummary();
    const pkg = summary.packages.find((p) => p.preorder);
    const place = (key: string) =>
      api.placeOrder({ addressId: summary.addresses[0]?.id ?? '', paymentMethodId: summary.paymentMethods[0]?.id, deliverySelections: summary.packages.map((p) => ({ packageId: p.packageId, option: p.deliveryOptions[0]?.key ?? 'carrier' })), attribution: { src: 'ribbon', po: 'LBK-7' } }, key);

    await preview({ place: { country: 'US', region: 'TX', city: 'Lubbock' }, stage: 'UPCOMING' });
    const err = await place('k-locked-0000000001').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).code).toBe('PREORDER_NOT_OPEN');

    await preview({});
    expect(pkg?.deliveryOptions[0]).toMatchObject({ key: 'afrotransact_delivery', feeMinor: 799 });
    const conf = await place('k-open-00000000000001');
    expect(conf.preorder).toMatchObject({ number: 'LBK-2001', windowName: 'Lubbock Pre-order #7', otherItemCount: 0 });
    expect((await api.getCheckoutStatus(conf.orderNumber)).preorder?.number).toBe('LBK-2001');
  });

  it('waitlist and the off states (master switch off, waitlist fallback) via the dev preview', async () => {
    const { api, preview } = browserLikeClient();
    await api.setDeliveryLocation({ zip: '79401' });
    await preview({ enabled: false, fallback: 'WAITLIST' });
    expect(await api.resolvePreorder()).toMatchObject({ covered: true, metro: false, on: false, fallback: 'WAITLIST' });
    expect((await api.getOpenPreorders()).windows).toEqual([]);
    expect(await api.joinPreorderWaitlist({ email: 'kwame@example.com' })).toEqual({ status: 'subscribed', city: 'Lubbock' });
    const bad = await api.joinPreorderWaitlist({ email: 'nope' } as never).catch((e: unknown) => e);
    expect(bad).toBeInstanceOf(ApiError);
  });

  it('leaves a waitlist from the email link; unknown tokens are refused on token', async () => {
    const { api } = browserLikeClient();
    expect(await api.leavePreorderWaitlist('wl-3f9c2a7d')).toEqual({ status: 'unsubscribed' });
    const bad = await api.leavePreorderWaitlist('invalid-token').catch((e: unknown) => e);
    expect(bad).toBeInstanceOf(ApiError);
    expect((bad as ApiError).status).toBe(422);
    expect((bad as ApiError).fieldErrors.token).toBeTruthy();
  });
});
