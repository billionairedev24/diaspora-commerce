/* MSW-style in-app mock backend for dev (EXPO_PUBLIC_MOCKS=1): a fetch replacement that answers BFF, identity and
   bookings routes from in-memory fixtures. It only runs when env.mocks is true (never in prod builds). */
import { bffProblemType, type Cart, type CartPackage, type DeliveryPromise, type ProductDetail, type ProductSummary, type SearchPage } from '@afrotransact/api-client';

import { type Booking, type CheckoutSummary, type OrderConfirmation, type OrderTracking } from '../types';
import { mockDb, type MockLine, type MockReview, type MockShipment, nextId, productByAtin } from './db';
import { type FixtureProduct, PRODUCTS, PROVIDERS, USER } from './fixtures';
import { atZoned } from './zoned';

type Params = Record<string, string>;
type Handler = (ctx: { req: Request; url: URL; params: Params; body: unknown }) => Response | Promise<Response>;

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const noContent = () => new Response(null, { status: 204 });
const problem = (status: number, title: string, code: string, detail?: string) =>
  new Response(JSON.stringify({ type: bffProblemType(code), title, status, code, detail: detail ?? title }), { status, headers: { 'Content-Type': 'application/problem+json' } });

/** The signed-in shopper's credit ledger, newest first: a gift card redeemed, then part of an order paid with it. */
function mockCreditEntries() {
  return [
    { id: 'cre_2', kind: 'SPEND', source: 'ORDER', amountMinor: -1250, currency: 'USD', balanceAfterMinor: 3750, createdAt: '2026-10-20T15:04:00Z' },
    { id: 'cre_1', kind: 'GRANT', source: 'GIFT_CARD', amountMinor: 5000, currency: 'USD', balanceAfterMinor: 5000, createdAt: '2026-10-12T09:30:00Z' }
  ];
}

const TAX_RATE = 0.0825;
const FREE_THRESHOLD = 3500;
const SELLER_SHIPPING = 599;

function promiseOf(p: FixtureProduct): DeliveryPromise {
  return p.sameDay
    ? { days: 0, label: 'Today by 8 pm', prefix: 'FREE delivery', free: true, feeMinor: 0, expressLabel: 'Today 6–8 pm', cutoff: 'Order within 3 hrs' }
    : { days: 1, label: 'Tomorrow', prefix: 'FREE delivery', free: true, feeMinor: 0, expressLabel: null, cutoff: null };
}

const stockStatus = (n: number) => (n === 0 ? 'OUT_OF_STOCK' : n <= 5 ? 'LOW_STOCK' : 'IN_STOCK') as 'OUT_OF_STOCK' | 'LOW_STOCK' | 'IN_STOCK';
const seller = (p: FixtureProduct) => ({ id: p.sellerId, name: p.sellerName, kind: p.sellerKind, city: p.sellerCity });

export function summaryOf(p: FixtureProduct): ProductSummary {
  return {
    atin: p.atin, title: p.title, brand: p.sellerName, community: p.community, department: p.department, unit: p.unit, rating: p.rating, reviewCount: p.reviewCount,
    imageUrl: null, offerId: `of_${p.atin}`, priceMinor: p.priceMinor, listPriceMinor: null, currency: 'USD', sellerName: p.sellerName, sellerKind: p.sellerKind,
    sellerCity: p.sellerCity, fulfilment: p.fulfilment, availableQty: p.stock, inStock: p.stock > 0, lowStock: p.stock > 0 && p.stock <= 5, stockStatus: stockStatus(p.stock),
    delivery: promiseOf(p), offerCount: 1, minOfferPriceMinor: p.priceMinor, sponsor: null
  };
}

function detailOf(p: FixtureProduct): ProductDetail {
  return {
    product: { atin: p.atin, title: p.title, brand: p.sellerName, community: p.community, department: p.department, unit: p.unit, origin: p.sellerCity, bullets: [p.description, p.bestBefore], rating: p.rating, reviewCount: p.reviewCount, images: [{ url: null, alt: p.title }], variants: [] },
    buyBox: { offerId: `of_${p.atin}`, seller: seller(p), priceMinor: p.priceMinor, listPriceMinor: null, currency: 'USD', fulfilment: p.fulfilment, availableQty: p.stock, stockStatus: stockStatus(p.stock), delivery: promiseOf(p) },
    otherOffers: [],
    shipsFrom: p.fulfilment === 'AFROTRANSACT' ? `Shipped by AfroTransact · ${p.shipsFrom}` : `Shipped by ${p.sellerName} · ${p.shipsFrom}`,
    soldBy: p.sellerName,
    returnsPolicy: 'Free 30-day returns',
    unitPriceLabel: p.unit
  };
}

function search(q: string, page: number, size: number): SearchPage {
  const words = q.toLowerCase().split(/\s+/).filter(Boolean);
  const hits = PRODUCTS.filter((p) => words.every((w) => `${p.title} ${p.sellerName} ${p.department}`.toLowerCase().includes(w)));
  const slice = hits.slice(page * size, page * size + size).map(summaryOf);
  const facet = { department: [], community: [], fulfilment: [], sellerKind: [], rating: [], price: [], delivery: [] };
  return { hits: slice, total: hits.length, page, size, pageCount: Math.ceil(hits.length / size), facets: facet, deliverToLabel: 'Houston 77032', tookMs: 3, services: [] };
}

function packageKey(p: FixtureProduct) {
  return `${p.sellerId}:${p.fulfilment}`;
}

function buildCart(): Cart {
  const db = mockDb();
  const groups = new Map<string, { p: FixtureProduct; lines: MockLine[] }>();
  for (const l of db.lines) {
    const p = productByAtin(l.atin);
    if (!p) continue;
    const k = packageKey(p);
    const g = groups.get(k) ?? { p, lines: [] };
    g.lines.push(l);
    groups.set(k, g);
  }
  const packages: CartPackage[] = [...groups.entries()].map(([k, g]) => {
    const lines = g.lines.map((l) => {
      const p = productByAtin(l.atin) as FixtureProduct;
      return {
        lineId: l.lineId, atin: p.atin, offerId: `of_${p.atin}`, title: p.title, unit: p.unit, department: p.department, imageUrl: null,
        priceMinor: p.priceMinor, lineTotalMinor: p.priceMinor * l.quantity, quantity: l.quantity, maxQuantity: Math.min(p.stock, 99),
        availableQty: p.stock, stockStatus: stockStatus(p.stock), selected: l.selected
      };
    });
    const subtotal = lines.filter((l) => l.selected).reduce((a, l) => a + l.lineTotalMinor, 0);
    const fba = g.p.fulfilment === 'AFROTRANSACT';
    return {
      packageId: `pkg_${k.replace(/[^a-z0-9]+/gi, '_')}`,
      seller: seller(g.p),
      fulfilment: g.p.fulfilment,
      heading: fba ? `Shipped by AfroTransact · ${g.p.shipsFrom}` : `Shipped by ${g.p.sellerName} · ${g.p.shipsFrom}`,
      shipsFrom: g.p.shipsFrom,
      delivery: promiseOf(g.p),
      deliveryOptions: deliveryOptions(g.p, subtotal),
      lines,
      itemCount: lines.reduce((a, l) => a + l.quantity, 0),
      subtotalMinor: subtotal,
      freeShippingNudge: !fba && subtotal < FREE_THRESHOLD ? { remainingMinor: FREE_THRESHOLD - subtotal, thresholdMinor: FREE_THRESHOLD } : null
    };
  });
  const all = packages.flatMap((p) => p.lines);
  return {
    cartId: 'cart_mock', currency: 'USD', packages,
    saved: db.saved.map((s) => { const p = productByAtin(s.atin) as FixtureProduct; return { savedId: s.savedId, atin: p.atin, offerId: `of_${p.atin}`, title: p.title, imageUrl: null, priceMinor: p.priceMinor, available: p.stock > 0 }; }),
    itemCount: all.reduce((a, l) => a + l.quantity, 0),
    selectedCount: all.filter((l) => l.selected).reduce((a, l) => a + l.quantity, 0),
    subtotalMinor: packages.reduce((a, p) => a + p.subtotalMinor, 0),
    allSelected: all.every((l) => l.selected),
    sellerCount: new Set(packages.map((p) => p.seller.id)).size
  };
}

function deliveryOptions(p: FixtureProduct, subtotal: number) {
  if (p.fulfilment === 'AFROTRANSACT') {
    return [
      ...(p.sameDay ? [{ key: 'afrotransact_delivery' as const, label: 'AfroTransact Delivery', eta: 'Today 6–8 pm', feeMinor: 0, free: true }] : []),
      { key: 'carrier' as const, label: 'UPS Ground', eta: p.sameDay ? 'Tomorrow' : 'In 2 days', feeMinor: 0, free: true },
      { key: 'express' as const, label: 'UPS Next Day Air', eta: 'Tomorrow by 10:30 am', feeMinor: 1299, free: false },
      { key: 'pickup' as const, label: 'Pickup', eta: 'Ready today after 4 pm', feeMinor: 0, free: true }
    ];
  }
  const free = subtotal >= FREE_THRESHOLD;
  return [
    { key: 'carrier' as const, label: 'USPS Ground Advantage', eta: 'In 3–4 days', feeMinor: free ? 0 : SELLER_SHIPPING, free },
    ...(p.sameDay ? [{ key: 'afrotransact_delivery' as const, label: 'AfroTransact Delivery', eta: 'Today 6–8 pm', feeMinor: 499, free: false }] : []),
    { key: 'pickup' as const, label: `Pickup at ${p.sellerName}`, eta: 'Ready tomorrow', feeMinor: 0, free: true }
  ];
}

function checkoutSummary(): CheckoutSummary | null {
  const cart = buildCart();
  const db = mockDb();
  const packages = cart.packages
    .map((p) => ({ ...p, lines: p.lines.filter((l) => l.selected) }))
    .filter((p) => p.lines.length)
    .map((p) => ({
      ...p,
      pickupPoints: p.fulfilment === 'AFROTRANSACT'
        ? [{ id: 'pp_hou_fc', name: 'Houston FC pickup counter', address: '1500 Aldine Bender Rd, Houston, TX 77032', hours: 'Mon–Sat 9 am–8 pm', ready: 'Today after 4 pm', setBy: 'platform' as const },
           { id: 'pp_hou_locker', name: 'AfroTransact Locker · Bellaire', address: '9600 Bellaire Blvd, Houston, TX 77036', hours: '24/7', ready: 'Tomorrow', setBy: 'platform' as const }]
        : [{ id: `pp_${p.seller.id}`, name: p.seller.name, address: p.shipsFrom, hours: 'Tue–Sun 10 am–6 pm', ready: 'Tomorrow', setBy: 'seller' as const }]
    }));
  if (!packages.length) return null;
  const subtotal = packages.reduce((a, p) => a + p.subtotalMinor, 0);
  const shipping = packages.reduce((a, p) => a + (p.deliveryOptions.find((o) => o.free) ?? p.deliveryOptions[0] ?? { feeMinor: 0 }).feeMinor, 0);
  const tax = Math.round(subtotal * TAX_RATE);
  return {
    packages, addresses: db.addresses, paymentMethods: db.paymentMethods, subtotalMinor: subtotal, shippingMinor: shipping, taxMinor: tax,
    taxRateLabel: '8.25% TX', totalMinor: subtotal + shipping + tax, currency: 'USD', sellerCount: new Set(packages.map((p) => p.seller.id)).size
  } as CheckoutSummary;
}

function placeOrder(body: { addressId?: string; paymentMethodId?: string; wallet?: string; deliverySelections?: Array<{ packageId: string; option: MockShipment['method']; preferences?: { handover?: 'hand' | 'door' | 'desk' } }> }): Response {
  const db = mockDb();
  const summary = checkoutSummary();
  if (!summary) return problem(409, 'Your cart is empty', 'CART_EMPTY');
  if (!body.addressId || !db.addresses.some((a) => a.id === body.addressId)) return problem(422, 'Choose an address', 'VALIDATION');
  if (!body.paymentMethodId && !body.wallet) return problem(422, 'Choose a payment method', 'VALIDATION');
  if (body.paymentMethodId === 'pm_card_declined') return problem(402, 'Your card was declined.', 'PAYMENT_DECLINED');
  const orderNumber = `AT-${1050 + (db.seq % 900)}-${1000 + ((db.seq * 7919) % 9000)}`;
  nextId('o');
  let total = summary.taxMinor + summary.subtotalMinor;
  const shipments: MockShipment[] = summary.packages.map((p, i) => {
    const sel = body.deliverySelections?.find((s) => s.packageId === p.packageId);
    const method = sel?.option ?? 'carrier';
    const opt = p.deliveryOptions.find((o) => o.key === method);
    total += opt?.feeMinor ?? 0;
    const perishable = p.lines.some((l) => productByAtin(l.atin)?.perishable);
    const handover = method === 'afrotransact_delivery' ? sel?.preferences?.handover ?? (perishable ? 'hand' : 'door') : null;
    return {
      shipmentId: `shp_${orderNumber}_${i + 1}`, label: opt?.label ?? 'Shipment', status: 'placed', eta: opt?.eta ?? null, method, handover, perishable,
      deliveryCode: method === 'afrotransact_delivery' ? String(1000 + ((db.seq * 31 + i * 97) % 9000)) : null,
      items: p.lines.map((l) => ({ atin: l.atin, quantity: l.quantity }))
    };
  });
  const addr = db.addresses.find((a) => a.id === body.addressId);
  db.orders.unshift({ orderNumber, placedAt: new Date().toISOString(), totalMinor: total, shipTo: `${addr?.name ?? ''}, ${addr?.city ?? ''} ${addr?.state ?? ''} ${addr?.zip ?? ''}`, status: 'placed', shipments });
  db.lines = db.lines.filter((l) => !l.selected);
  const confirmation: OrderConfirmation = {
    orderNumber, status: 'placed', paymentClientSecret: `pi_mock_${orderNumber}_secret_mock`, totalMinor: total, currency: 'USD',
    shipments: shipments.map((s) => ({ shipmentId: s.shipmentId, label: s.label, eta: s.eta ?? '', deliveryCode: s.deliveryCode }))
  };
  return json(confirmation, 201);
}

function tracking(orderNumber: string): OrderTracking | null {
  const o = mockDb().orders.find((x) => x.orderNumber === orderNumber);
  if (!o) return null;
  return {
    orderNumber: o.orderNumber, placedAt: o.placedAt, totalMinor: o.totalMinor, currency: 'USD',
    shipments: o.shipments.map((s) => ({
      shipmentId: s.shipmentId, label: s.label, status: s.status, eta: s.eta, method: s.method, handover: s.handover, perishable: s.perishable, deliveryCode: s.deliveryCode,
      driver: s.method === 'afrotransact_delivery' ? { name: 'Kwame B.', initials: 'KB' } : null,
      items: s.items.map((it) => { const p = productByAtin(it.atin); return { atin: it.atin, title: p?.title ?? it.atin, quantity: it.quantity, priceMinor: p?.priceMinor ?? 0, returnable: s.status === 'delivered' }; })
    }))
  };
}

/* ── Bookings ──────────────────────────────────────────────────────────────── */

const isoDate = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

function serviceOf(serviceId: string) {
  for (const pr of PROVIDERS) {
    const s = pr.services.find((x) => x.id === serviceId);
    if (s) return { pr, s };
  }
  return null;
}

function slotsFor(serviceId: string, date: string) {
  const found = serviceOf(serviceId);
  if (!found) return [];
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || new Date(`${date}T12:00:00Z`).getUTCDay() === 0) return [];
  const booked = mockDb().bookings.filter((b) => b.service?.serviceId === serviceId && ['CONFIRMED', 'REQUESTED', 'RESCHEDULE_PROPOSED', 'RESCHEDULE_REQUESTED'].includes(b.status ?? '')).map((b) => b.startAt);
  const out: Array<{ start: string; end: string; memberIds: string[] }> = [];
  const step = Math.max(60, Math.min(found.s.durationMinutes, 120));
  for (let m = 9 * 60; m + found.s.durationMinutes <= 19 * 60; m += step) {
    const start = atZoned(date, m, found.pr.timeZone);
    if (start.getTime() < Date.now() + 2 * 3600_000) continue;
    if (booked.includes(start.toISOString())) continue;
    out.push({ start: start.toISOString(), end: new Date(start.getTime() + found.s.durationMinutes * 60_000).toISOString(), memberIds: [] });
  }
  return out;
}

function quoteFor(b: Booking) {
  const deposit = b.service?.depositMinor ?? 0;
  const hours = (new Date(b.startAt ?? 0).getTime() - Date.now()) / 3600_000;
  const kind = b.policy?.kind ?? 'MODERATE';
  const share = kind === 'FLEXIBLE' ? (hours >= 24 ? 1 : 0) : kind === 'STRICT' ? (hours >= 168 ? 1 : hours >= 48 ? 0.5 : 0) : hours >= 48 ? 1 : hours >= 24 ? 0.5 : 0;
  const refund = Math.round(deposit * share);
  return { by: 'CUSTOMER', refundSharePercent: share * 100, depositMinor: deposit, refundMinor: refund, keptMinor: deposit - refund, releaseAuthorisation: true, policy: b.policy?.label ?? 'Moderate', explanation: b.policy?.text ?? '', currency: 'USD' };
}

function logBooking(id: string, action: string, by: string) {
  const db = mockDb();
  (db.bookingHistory[id] ??= []).push({ at: new Date().toISOString(), by, action });
}

function updateBooking(id: string, patch: Partial<Booking>): Booking | null {
  const db = mockDb();
  const i = db.bookings.findIndex((b) => b.id === id);
  const cur = db.bookings[i];
  if (!cur) return null;
  const next = { ...cur, ...patch, version: (cur.version ?? 0) + 1, updatedAt: new Date().toISOString() };
  db.bookings[i] = next;
  return next;
}

const LABEL: Record<string, string> = { REQUESTED: 'Awaiting provider', CONFIRMED: 'Confirmed', RESCHEDULE_REQUESTED: 'New time requested', RESCHEDULE_PROPOSED: 'Provider proposed a new time', COMPLETED: 'Completed', CANCELLED_CUSTOMER: 'Cancelled by customer', DECLINED: 'Declined by provider' };

/* ── Routes ────────────────────────────────────────────────────────────────── */

/** A product the shopper has received (a delivered shipment holds it): the backend's rule for who can review (S324). */
function receivedAtin(atin: string): boolean {
  return mockDb().orders.some((o) => o.shipments.some((s) => s.status === 'delivered' && s.items.some((it) => it.atin === atin)));
}

const EDIT_WINDOW_MS = 30 * 86_400_000;

function ownReview(r: MockReview) {
  const editableUntil = new Date(new Date(r.createdAt).getTime() + EDIT_WINDOW_MS);
  return { id: r.id, reviewerName: `${USER.firstName} ${USER.lastName.charAt(0)}.`, rating: r.rating, title: r.title, text: r.text, photos: [] as Array<{ url: string }>, status: r.status, createdAt: r.createdAt,
    updatedAt: r.updatedAt, editableUntil: editableUntil.toISOString(), editable: editableUntil.getTime() > Date.now(), moderation: null, reply: null };
}

/** The automatic checks of catalog (links, e-mail addresses, phone numbers, profanity): a flagged review waits for staff. */
const flagged = (...parts: Array<string | null | undefined>) => /https?:\/\/|www\.|\S+@\S+\.\S+|(?:\+?\d[\s().-]?){9,}|\b(?:damn|shit|fuck)\w*/i.test(parts.filter(Boolean).join(' '));

function readReview(body: unknown): { rating: number; title: string | null; text: string | null } | Response {
  const b = (body ?? {}) as { rating?: unknown; title?: unknown; text?: unknown };
  if (typeof b.rating !== 'number' || !Number.isInteger(b.rating) || b.rating < 1 || b.rating > 5) {
    return new Response(JSON.stringify({ type: bffProblemType('VALIDATION_FAILED'), title: 'Check your review', status: 422, code: 'VALIDATION_FAILED', errors: { rating: 'Choose 1 to 5 stars' } }), { status: 422, headers: { 'Content-Type': 'application/problem+json' } });
  }
  const str = (v: unknown) => (typeof v === 'string' && v.trim() ? v.trim() : null);
  return { rating: b.rating, title: str(b.title), text: str(b.text) };
}

const ROUTES: Array<[string, string, Handler]> = [
  ['GET', '/v1/products/:atin/reviews', ({ params }) => {
    const own = mockDb().reviews[params.atin ?? ''];
    const published = own?.status === 'PUBLISHED' ? [own] : [];
    const breakdown = [1, 2, 3, 4, 5].map((n) => published.filter((r) => r.rating === n).length);
    const count = published.length;
    return json({ rating: { average: count ? published.reduce((a, r) => a + r.rating, 0) / count : 0, count, breakdown },
      items: published.map((r) => ({ id: r.id, reviewerName: ownReview(r).reviewerName, rating: r.rating, title: r.title, text: r.text, photos: [], verifiedPurchase: true, createdAt: r.createdAt, reply: null })),
      total: count, page: 0, size: 10 });
  }],
  ['GET', '/v1/products/:atin/reviews/mine', ({ params }) => {
    const atin = params.atin ?? '';
    const own = mockDb().reviews[atin];
    if (own) return json({ eligible: false, reason: 'already-reviewed', review: ownReview(own) });
    const eligible = receivedAtin(atin);
    return json({ eligible, reason: eligible ? null : 'not-purchased', review: null });
  }],
  ['POST', '/v1/products/:atin/reviews', ({ params, body }) => {
    const atin = params.atin ?? '';
    if (!receivedAtin(atin)) return problem(403, 'Only buyers can review', 'REVIEW_NOT_VERIFIED_PURCHASE', 'Only customers who received this item can review it.');
    if (mockDb().reviews[atin]) return problem(409, 'Already reviewed', 'REVIEW_ALREADY_EXISTS', 'You have already reviewed this item.');
    const input = readReview(body);
    if (input instanceof Response) return input;
    const now = new Date().toISOString();
    const review: MockReview = { id: `00000000-0000-4000-8000-${String(mockDb().seq++).padStart(12, '0')}`, ...input, status: flagged(input.title, input.text) ? 'PENDING' : 'PUBLISHED', createdAt: now, updatedAt: now };
    mockDb().reviews[atin] = review;
    return json(ownReview(review), 201);
  }],
  ['PUT', '/v1/products/:atin/reviews/mine', ({ params, body }) => {
    const own = mockDb().reviews[params.atin ?? ''];
    if (!own) return problem(404, 'No review', 'NOT_FOUND', 'You have not reviewed this item.');
    if (!ownReview(own).editable) return problem(409, 'Editing is closed', 'REVIEW_EDIT_WINDOW_CLOSED', 'Reviews can be edited for 30 days after you wrote them.');
    const input = readReview(body);
    if (input instanceof Response) return input;
    Object.assign(own, input, { status: flagged(input.title, input.text) ? 'PENDING' : 'PUBLISHED', updatedAt: new Date().toISOString() });
    return json(ownReview(own));
  }],
  ['DELETE', '/v1/products/:atin/reviews/mine', ({ params }) => {
    const db = mockDb();
    if (!db.reviews[params.atin ?? '']) return problem(404, 'No review', 'NOT_FOUND', 'You have not reviewed this item.');
    delete db.reviews[params.atin ?? ''];
    return noContent();
  }],
  ['GET', '/v1/me', () => json({ authenticated: true, user: { id: USER.id, firstName: USER.firstName, lastName: USER.lastName, email: USER.email, initials: 'AO', roles: ['account.customer'] }, counts: { orders: mockDb().orders.length, addresses: mockDb().addresses.length, paymentMethods: mockDb().paymentMethods.length } })],
  ['GET', '/v1/search', ({ url }) => json(search(url.searchParams.get('q') ?? '', Number(url.searchParams.get('page') ?? 0), Number(url.searchParams.get('size') ?? 24)))],
  ['GET', '/v1/products/:atin', ({ params }) => { const p = productByAtin(params.atin ?? ''); return p ? json(detailOf(p)) : problem(404, 'Product not found', 'NOT_FOUND'); }],
  ['GET', '/v1/cart', () => json(buildCart())],
  ['POST', '/v1/cart/items', ({ body }) => {
    const b = body as { offerId: string; quantity: number };
    const atin = b.offerId.replace(/^of_/, '');
    const p = productByAtin(atin);
    if (!p) return problem(404, 'Offer not found', 'NOT_FOUND');
    const db = mockDb();
    const line = db.lines.find((l) => l.atin === atin);
    const qty = (line?.quantity ?? 0) + b.quantity;
    if (qty > p.stock) return problem(409, `Only ${p.stock} left`, 'STOCK_LIMIT');
    if (line) line.quantity = qty;
    else db.lines.push({ atin, quantity: b.quantity, selected: true, lineId: nextId('ln_') });
    return json(buildCart());
  }],
  ['PATCH', '/v1/cart/items/:lineId', ({ params, body }) => {
    const b = body as { quantity?: number; selected?: boolean };
    const line = mockDb().lines.find((l) => l.lineId === params.lineId);
    if (!line) return problem(404, 'Line not found', 'NOT_FOUND');
    const p = productByAtin(line.atin);
    if (b.quantity !== undefined) { if (p && b.quantity > p.stock) return problem(409, `Only ${p.stock} left`, 'STOCK_LIMIT'); line.quantity = b.quantity; }
    if (b.selected !== undefined) line.selected = b.selected;
    return json(buildCart());
  }],
  ['DELETE', '/v1/cart/items/:lineId', ({ params }) => { const db = mockDb(); db.lines = db.lines.filter((l) => l.lineId !== params.lineId); return json(buildCart()); }],
  ['GET', '/v1/account/addresses', () => json({ items: mockDb().addresses, nextCursor: null })],
  ['POST', '/v1/account/addresses', ({ body }) => {
    const db = mockDb();
    const b = (body ?? {}) as Partial<(typeof db.addresses)[number]> & { acceptUnverified?: boolean };
    // A street number of 0 stands in for an address the geo service can't verify (try it, then "Keep mine").
    if (!b.acceptUnverified && /^0\b/.test(b.line1 ?? '')) {
      return new Response(JSON.stringify({ type: bffProblemType('VALIDATION'), title: 'Check the address', status: 422, code: 'VALIDATION', errors: { line1: 'We couldn’t find that street number.' } }), { status: 422, headers: { 'Content-Type': 'application/problem+json' } });
    }
    const first = db.addresses.length === 0;
    if (b.isDefault || first) db.addresses.forEach((a) => { a.isDefault = false; });
    const a = { id: nextId('a'), name: b.name ?? '', phone: b.phone ?? '', line1: b.line1 ?? '', line2: b.line2 ?? '', city: b.city ?? '', state: b.state ?? '', zip: b.zip ?? '', isDefault: !!b.isDefault || first, verified: !b.acceptUnverified };
    db.addresses.push(a);
    return json(a, 201);
  }],
  ['PATCH', '/v1/account/addresses/:id', ({ params, body }) => {
    const db = mockDb();
    const a = db.addresses.find((x) => x.id === params.id);
    if (!a) return problem(404, 'Address not found', 'NOT_FOUND');
    const b = (body ?? {}) as Partial<typeof a>;
    if (b.isDefault) db.addresses.forEach((x) => { x.isDefault = false; });
    Object.assign(a, { name: b.name ?? a.name, phone: b.phone ?? a.phone, line1: b.line1 ?? a.line1, line2: b.line2 ?? '', city: b.city ?? a.city, state: b.state ?? a.state, zip: b.zip ?? a.zip, isDefault: b.isDefault ?? a.isDefault });
    return json(a);
  }],
  ['POST', '/v1/account/addresses/:id/default', ({ params }) => {
    const db = mockDb();
    const a = db.addresses.find((x) => x.id === params.id);
    if (!a) return problem(404, 'Address not found', 'NOT_FOUND');
    db.addresses.forEach((x) => { x.isDefault = x.id === a.id; });
    return json(a);
  }],
  ['DELETE', '/v1/account/addresses/:id', ({ params }) => {
    const db = mockDb();
    db.addresses = db.addresses.filter((x) => x.id !== params.id);
    if (db.addresses.length && !db.addresses.some((x) => x.isDefault)) db.addresses[0]!.isDefault = true;
    return noContent();
  }],
  ['GET', '/v1/account/credit', ({ url }) => {
    const entries = mockCreditEntries();
    const before = url.searchParams.get('before');
    const from = before ? entries.findIndex((e) => e.id === before) + 1 : 0;
    const page = entries.slice(from, from + 20);
    const next = from + 20 < entries.length ? page[page.length - 1]!.id : null;
    return json({ balanceMinor: entries[0]?.balanceAfterMinor ?? 0, currency: 'USD', entries: page, nextBefore: next });
  }],
  ['GET', '/v1/account/payment-methods', () => json({ items: mockDb().paymentMethods, nextCursor: null })],
  ['POST', '/v1/account/payment-methods/setup-intents', () => json({ clientSecret: `seti_mock_${nextId('')}_secret_mock`, publishableKey: 'pk_test_mock' }, 201)],
  ['DELETE', '/v1/account/payment-methods/:id', ({ params }) => { const db = mockDb(); db.paymentMethods = db.paymentMethods.filter((m) => m.id !== params.id); return noContent(); }],
  ['GET', '/v1/account/orders', () => json({ items: mockDb().orders.map((o) => ({ orderNumber: o.orderNumber, placedAt: o.placedAt, totalMinor: o.totalMinor, currency: 'USD', shipTo: o.shipTo, status: o.status, shipments: o.shipments.map((s) => ({ shipmentId: s.shipmentId, label: s.label, status: s.status, items: s.items.map((it) => `${productByAtin(it.atin)?.title ?? it.atin}${it.quantity > 1 ? ` ×${it.quantity}` : ''}`).join(', ') })) })), nextCursor: null })],
  ['GET', '/v1/account/orders/:orderNumber/tracking', ({ params }) => { const t = tracking(params.orderNumber ?? ''); return t ? json(t) : problem(404, 'Order not found', 'NOT_FOUND'); }],
  ['GET', '/v1/checkout/summary', () => { const s = checkoutSummary(); return s ? json(s) : problem(409, 'Your cart is empty', 'CART_EMPTY'); }],
  ['POST', '/v1/checkout/orders', ({ body }) => placeOrder(body as Parameters<typeof placeOrder>[0])],
  /* identity */
  ['POST', '/v1/me/devices', ({ body }) => {
    const b = body as { token: string; app: string; platform: string; provider?: string; deviceName?: string };
    const db = mockDb();
    db.devices = db.devices.filter((d) => d.token !== b.token);
    const d = { id: `00000000-0000-4000-8000-${String(db.seq).padStart(12, '0')}`, token: b.token, app: b.app, platform: b.platform, provider: b.provider ?? (b.platform === 'ios' ? 'apns' : 'fcm') };
    db.devices.push(d);
    nextId('d');
    return json({ id: d.id, app: d.app, platform: d.platform, provider: d.provider, tokenSuffix: b.token.slice(-6), deviceName: b.deviceName, createdAt: new Date().toISOString(), lastSeenAt: new Date().toISOString() }, 201);
  }],
  ['DELETE', '/v1/me/devices', ({ body }) => { const db = mockDb(); db.devices = db.devices.filter((d) => d.token !== (body as { token: string }).token); return noContent(); }],
  ['GET', '/v1/me/notification-preferences', () => json(mockDb().prefs)],
  ['PUT', '/v1/me/notification-preferences', ({ body }) => { const db = mockDb(); db.prefs = { ...db.prefs, ...(body as object), updatedAt: new Date().toISOString() }; return json(db.prefs); }],
  /* bookings */
  ['GET', '/v1/public/services/search', ({ url }) => {
    const q = (url.searchParams.get('q') ?? '').toLowerCase();
    const services = PROVIDERS.flatMap((pr) => pr.services.map((s) => ({ pr, s }))).filter(({ pr, s }) => !q || `${pr.displayName} ${pr.category} ${s.name}`.toLowerCase().includes(q));
    return json({ services: services.map(({ pr, s }) => {
      const next = [0, 1, 2, 3, 4, 5, 6].map((d) => { const x = new Date(); x.setDate(x.getDate() + d); return isoDate(x); }).find((d) => slotsFor(s.id, d).length);
      return { serviceId: s.id, orgId: pr.orgId, name: s.name, providerName: pr.displayName, description: s.description, category: pr.category, durationMinutes: s.durationMinutes, priceFromMinor: s.priceMinor, depositMinor: s.depositMinor, currency: 'USD', modes: ['IN_PERSON'], rating: pr.rating, ratingCount: pr.ratingCount, nextAvailable: next, location: pr.location };
    }) });
  }],
  ['GET', '/v1/public/providers/:orgId', ({ params }) => {
    const pr = PROVIDERS.find((x) => x.orgId === params.orgId);
    if (!pr) return problem(404, 'Provider not found', 'NOT_FOUND');
    return json({ orgId: pr.orgId, displayName: pr.displayName, timeZone: pr.timeZone, location: pr.location, policy: pr.policy, instantBooking: pr.instant, team: [], services: pr.services.map((s) => ({ id: s.id, orgId: pr.orgId, providerName: pr.displayName, name: s.name, description: s.description, category: pr.category, durationMinutes: s.durationMinutes, priceMinor: s.priceMinor, depositMinor: s.depositMinor, balanceMinor: s.priceMinor - s.depositMinor, currency: 'USD', modes: ['IN_PERSON'], policy: pr.policy, instantBooking: pr.instant, addOns: [], team: [] })) });
  }],
  ['GET', '/v1/public/services/:serviceId/days', ({ params }) => {
    const out = Array.from({ length: 21 }, (_, d) => { const x = new Date(); x.setDate(x.getDate() + d); const iso = isoDate(x); return { date: iso, slots: slotsFor(params.serviceId ?? '', iso).length }; });
    return json(out);
  }],
  ['GET', '/v1/public/services/:serviceId/slots', ({ params, url }) => json(slotsFor(params.serviceId ?? '', url.searchParams.get('date') ?? ''))],
  ['GET', '/v1/public/services/:serviceId/summary', ({ params }) => {
    const f = serviceOf(params.serviceId ?? '');
    if (!f) return problem(404, 'Service not found', 'NOT_FOUND');
    return json({ serviceId: f.s.id, serviceName: f.s.name, providerName: f.pr.displayName, durationMinutes: f.s.durationMinutes, priceMinor: f.s.priceMinor, depositNowMinor: f.s.depositMinor, balanceLaterMinor: f.s.priceMinor - f.s.depositMinor, currency: 'USD', mode: 'IN_PERSON', policy: f.pr.policy.label, policyText: f.pr.policy.text, instantBooking: f.pr.instant, providerAnswersWithinMinutes: 240 });
  }],
  ['POST', '/v1/bookings', ({ body }) => {
    const b = body as { serviceId: string; startAt: string; customer: { name: string; email?: string; phone?: string }; notes?: string };
    const f = serviceOf(b.serviceId);
    if (!f) return problem(404, 'Service not found', 'NOT_FOUND');
    const date = b.startAt.slice(0, 10);
    const local = isoDate(new Date(b.startAt));
    if (!slotsFor(b.serviceId, local).some((s) => s.start === b.startAt) && !slotsFor(b.serviceId, date).some((s) => s.start === b.startAt)) return problem(409, 'That time was just taken. Pick another.', 'bookings.slot-taken');
    const id = `7b1c2d3e-0000-4000-8000-${String(mockDb().seq).padStart(12, '0')}`;
    nextId('b');
    const status = f.pr.instant ? 'CONFIRMED' : 'REQUESTED';
    const booking: Booking = {
      id, reference: `BK-${mockDb().seq}`, orgId: f.pr.orgId, status, statusLabel: LABEL[status], startAt: b.startAt, endAt: new Date(new Date(b.startAt).getTime() + f.s.durationMinutes * 60_000).toISOString(),
      location: f.pr.location, notes: b.notes, channel: 'app', customer: b.customer,
      service: { serviceId: f.s.id, name: f.s.name, durationMinutes: f.s.durationMinutes, priceMinor: f.s.priceMinor, depositMinor: f.s.depositMinor, balanceMinor: f.s.priceMinor - f.s.depositMinor, currency: 'USD', providerName: f.pr.displayName, mode: 'IN_PERSON' },
      policy: f.pr.policy, payment: { state: f.s.depositMinor ? 'REQUIRES_PAYMENT_METHOD' : 'NOT_REQUIRED', clientSecret: f.s.depositMinor ? `pi_mock_${id}_secret_mock` : undefined, authorizedMinor: 0 },
      createdAt: new Date().toISOString(), version: 1
    };
    mockDb().bookings.unshift(booking);
    logBooking(id, f.pr.instant ? 'Booked · deposit charged' : 'Requested · deposit authorised', 'customer');
    return json({ booking, replayed: false });
  }],
  ['GET', '/v1/bookings/mine', () => json(mockDb().bookings)],
  ['GET', '/v1/bookings/:id', ({ params }) => { const b = mockDb().bookings.find((x) => x.id === params.id); return b ? json(b) : problem(404, 'Booking not found', 'NOT_FOUND'); }],
  ['GET', '/v1/bookings/:id/history', ({ params }) => json((mockDb().bookingHistory[params.id ?? ''] ?? [{ at: new Date(Date.now() - 2 * 86400_000).toISOString(), by: 'customer', action: 'Booked · deposit charged' }]).slice().reverse())],
  ['GET', '/v1/bookings/:id/cancellation-quote', ({ params }) => { const b = mockDb().bookings.find((x) => x.id === params.id); return b ? json(quoteFor(b)) : problem(404, 'Booking not found', 'NOT_FOUND'); }],
  ['POST', '/v1/bookings/:id/cancel', ({ params }) => {
    const b = mockDb().bookings.find((x) => x.id === params.id);
    if (!b) return problem(404, 'Booking not found', 'NOT_FOUND');
    const q = quoteFor(b);
    const next = updateBooking(b.id ?? '', { status: 'CANCELLED_CUSTOMER', statusLabel: LABEL.CANCELLED_CUSTOMER, payment: { ...b.payment, refundedMinor: q.refundMinor } });
    logBooking(b.id ?? '', `Cancelled · ${q.refundMinor ? `$${(q.refundMinor / 100).toFixed(2)} refunded` : 'no refund'}`, 'customer');
    return json({ booking: next, applied: q });
  }],
  ['POST', '/v1/bookings/:id/reschedule', ({ params, body }) => {
    const b = mockDb().bookings.find((x) => x.id === params.id);
    const startAt = (body as { startAt: string }).startAt;
    if (!b) return problem(404, 'Booking not found', 'NOT_FOUND');
    const pr = PROVIDERS.find((x) => x.orgId === b.orgId);
    const instant = pr?.instant ?? false;
    const dur = b.service?.durationMinutes ?? 60;
    const next = instant
      ? updateBooking(b.id ?? '', { startAt, endAt: new Date(new Date(startAt).getTime() + dur * 60_000).toISOString(), reschedules: (b.reschedules ?? 0) + 1 })
      : updateBooking(b.id ?? '', { status: 'RESCHEDULE_REQUESTED', statusLabel: LABEL.RESCHEDULE_REQUESTED, proposedStartAt: startAt });
    logBooking(b.id ?? '', instant ? 'Moved' : 'New time requested', 'customer');
    return json(next);
  }],
  ['POST', '/v1/bookings/:id/new-time/accept', ({ params }) => {
    const b = mockDb().bookings.find((x) => x.id === params.id);
    if (!b || !b.proposedStartAt) return problem(409, 'No new time to accept', 'bookings.no-proposal');
    const next = updateBooking(b.id ?? '', { status: 'CONFIRMED', statusLabel: LABEL.CONFIRMED, startAt: b.proposedStartAt, proposedStartAt: undefined });
    logBooking(b.id ?? '', 'New time accepted', 'customer');
    return json(next);
  }],
  ['POST', '/v1/bookings/:id/new-time/decline', ({ params }) => {
    const b = mockDb().bookings.find((x) => x.id === params.id);
    if (!b) return problem(404, 'Booking not found', 'NOT_FOUND');
    const next = updateBooking(b.id ?? '', { status: 'CANCELLED_CUSTOMER', statusLabel: 'Declined new time · full refund', payment: { ...b.payment, refundedMinor: b.service?.depositMinor ?? 0 } });
    logBooking(b.id ?? '', 'Declined new time · full refund', 'customer');
    return json(next);
  }],
  ['POST', '/v1/bookings/:id/payment/sync', ({ params }) => {
    const b = mockDb().bookings.find((x) => x.id === params.id);
    if (!b) return problem(404, 'Booking not found', 'NOT_FOUND');
    return json(updateBooking(b.id ?? '', { payment: { ...b.payment, state: b.status === 'CONFIRMED' ? 'DEPOSIT_CAPTURED' : 'AUTHORIZED', authorizedMinor: b.service?.priceMinor ?? 0, clientSecret: undefined } }));
  }]
];

function match(pattern: string, path: string): Params | null {
  const a = pattern.split('/');
  const b = path.split('/');
  if (a.length !== b.length) return null;
  const params: Params = {};
  for (let i = 0; i < a.length; i += 1) {
    const seg = a[i] ?? '';
    const val = b[i] ?? '';
    if (seg.startsWith(':')) params[seg.slice(1)] = decodeURIComponent(val);
    else if (seg !== val) return null;
  }
  return params;
}

const latency = () => new Promise((r) => setTimeout(r, 120));

/** fetch-compatible mock: routes by method + path (service prefixes such as /bff are ignored). */
export async function mockFetch(req: Request): Promise<Response> {
  const url = new URL(req.url);
  const path = url.pathname.replace(/^\/(bff|identity|bookings)(?=\/)/, '');
  const auth = req.headers.get('Authorization');
  await latency();
  let body: unknown;
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    const text = await req.text();
    body = text ? JSON.parse(text) : undefined;
  }
  for (const [method, pattern, handler] of ROUTES) {
    if (method !== req.method) continue;
    const params = match(pattern, path);
    if (!params) continue;
    if (!pattern.startsWith('/v1/public/') && !auth) return problem(401, 'Sign in required', 'UNAUTHENTICATED');
    return handler({ req, url, params, body });
  }
  return problem(404, 'Not found', 'NOT_FOUND', `No mock for ${req.method} ${path}`);
}
