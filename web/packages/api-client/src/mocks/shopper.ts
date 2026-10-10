/* Mock BFF for the storefront's account pages, checkout, returns, login & security, services & bookings and Lola.
   Same routes, shapes and refusals as contracts/openapi/gateway-bff.yaml; state per demo account (shopper-state). */
import { http, HttpResponse } from 'msw';
import { z } from 'zod';

import { type PaymentMethod } from '../schemas/account';
import { type CartPackage } from '../schemas/cart';
import { type DeliveryOption } from '../schemas/cart';
import { SearchClickRequestSchema } from '../schemas/search';
import {
  AddressInputSchema,
  BookingRequestSchema,
  type CheckoutLocalDelivery,
  type CheckoutPackage,
  type CheckoutSummary,
  LolaChatRequestSchema,
  NotificationPreferencesSchema,
  type OrderConfirmation,
  PasswordChangeSchema,
  type PickupPoint,
  PlaceOrderRequestSchema,
  type ReturnableLine,
  type ReturnDetail,
  ReturnReasonSchema,
  SELLER_FAULT_REASONS,
  type ServiceDetail,
  type ShipmentDetail,
  StartReturnRequestSchema
} from '../schemas/shopper';
import { getDb, type MockSession } from './db';
import { addBusinessDays, LOCAL_DELIVERY, localDeliveryQuote, locationForZip, metroOf } from './delivery';
import { PICKUP_POINTS } from './fixtures/geo';
import { body, type Ctx, json, problem, requireUser, route } from './http-kit';
import { lolaStream } from './lola';
import { buildCart, MockProblem, sessionLocation } from './service';
import { account, type AccountState, bookingFor, MOCK_REFUND_QUOTE, type MockOrder, PROVIDERS, seq, SERVICES } from './shopper-state';

const DEMO_CODE = '246810';
const MOCK_PUBLISHABLE_KEY = 'pk_test_mock';
const iso = (d: Date) => d.toISOString();
const noContent = () => new HttpResponse(null, { status: 204 });
const me = (ctx: Ctx) => account(requireUser(ctx).id);
const notFound = (what: string) => new MockProblem(404, 'NOT_FOUND', 'Not found', `We couldn't find that ${what}.`);
const idemKey = (ctx: Ctx) => ctx.request.headers.get('idempotency-key') ?? '';

/* ── Addresses ─────────────────────────────────────────────────── */

const UNVERIFIABLE = /nowhere|^0 /i;

function saveAddress(a: AccountState, id: string | null, input: z.infer<typeof AddressInputSchema>) {
  const verified = !UNVERIFIABLE.test(input.line1);
  if (!verified && !input.acceptUnverified) {
    throw new MockProblem(422, 'VALIDATION_FAILED', 'Check the address', 'We couldn’t verify this address.', {
      errors: { line1: 'We couldn’t verify this address. Check it or keep it as entered.' }
    });
  }
  const isDefault = input.isDefault || a.addresses.length === 0;
  if (isDefault) a.addresses.forEach((x) => { x.isDefault = false; });
  const value = { id: id ?? `ad${seq()}`, name: input.name.trim(), phone: input.phone, line1: input.line1, line2: input.line2 ?? '', city: input.city,
    state: input.state, zip: input.zip, isDefault: isDefault || (id !== null && (a.addresses.find((x) => x.id === id)?.isDefault ?? false)), verified };
  if (id) {
    const i = a.addresses.findIndex((x) => x.id === id);
    if (i < 0) throw new MockProblem(404, 'ADDRESS_NOT_FOUND', 'Address not found', 'That address no longer exists.');
    a.addresses[i] = value;
  } else a.addresses.push(value);
  return value;
}

/* ── Payments ──────────────────────────────────────────────────── */

const MockCardSchema = z.object({
  type: z.enum(['card', 'us_bank_account', 'cashapp', 'link']),
  brand: z.string().optional(),
  last4: z.string().optional(),
  expMonth: z.number().int().optional(),
  expYear: z.number().int().optional(),
  bankName: z.string().optional()
});

function addMethod(a: AccountState, c: z.infer<typeof MockCardSchema>): PaymentMethod {
  const n = seq();
  const label =
    c.type === 'us_bank_account' ? `${c.bankName ?? 'Bank account'} checking ending in ${c.last4 ?? '0000'}` :
    c.type === 'cashapp' ? 'Cash App Pay' : c.type === 'link' ? 'Link' :
    `${(c.brand ?? 'card').replace(/^./, (x) => x.toUpperCase())} ending in ${c.last4 ?? '0000'}`;
  const pm: PaymentMethod = { id: `pm_mock_${n}`, type: c.type, brand: c.brand ?? null, last4: c.last4 ?? null, label,
    expires: c.expMonth && c.expYear ? `${String(c.expMonth).padStart(2, '0')}/${String(c.expYear % 100).padStart(2, '0')}` : null, isDefault: a.paymentMethods.length === 0 };
  a.paymentMethods.push(pm);
  return pm;
}

/* ── Checkout ──────────────────────────────────────────────────── */

const fmtDate = (d: Date) => d.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric', timeZone: 'UTC' });

function withDates(o: DeliveryOption, now: Date, days: number): DeliveryOption {
  const from = addBusinessDays(now, days);
  const to = addBusinessDays(now, days + (o.key === 'carrier' ? 1 : 0));
  return { ...o, earliestDate: iso(from).slice(0, 10), latestDate: iso(to).slice(0, 10), cutoffLabel: 'Order within 3 h 12 min',
    eta: from.getTime() === to.getTime() ? fmtDate(from) : `${fmtDate(from)} – ${fmtDate(to)}` };
}

function perishableLine(atin: string): boolean {
  return getDb().products.get(atin)?.cat === 'Groceries';
}

function checkoutPackages(session: MockSession, zip: string, now: Date): CheckoutPackage[] {
  // Options as the cart computes them for the delivery address (zones, nearby origins, pickup points), with dates.
  const cart = buildCart({ ...session, zip }, now);
  const metro = metroOf(locationForZip(zip) ?? sessionLocation(session));
  const here = (city: string) => metro === city;
  return cart.packages
    .map((p): CartPackage => {
      const lines = p.lines.filter((l) => l.selected).map((l) => ({ ...l, perishable: perishableLine(l.atin) }));
      return { ...p, lines, itemCount: lines.reduce((a, l) => a + l.quantity, 0), subtotalMinor: lines.reduce((a, l) => a + l.lineTotalMinor, 0) };
    })
    .filter((p) => p.lines.length > 0)
    .map((p) => {
      const isAT = p.fulfilment === 'AFROTRANSACT';
      const days = (o: DeliveryOption) => (o.key === 'express' || o.key === 'afrotransact_delivery' ? 1 : o.key === 'pickup' ? 0 : Math.max(1, p.delivery.days));
      if (p.fulfilment === 'AFROTRANSACT_DELIVERY') {
        // AfroTransact Delivery from the seller's store (S255): road distance store → address and payments' pricing.
        const q = localDeliveryQuote(p.seller.city, metro);
        const fromCity = p.seller.city;
        const localDelivery: CheckoutLocalDelivery = q.available
          ? { available: true, chargeMinor: q.chargeMinor, roadMiles: q.roadMiles, durationMinutes: q.durationMinutes, fromCity, unavailableReason: null, message: null,
              maxRadiusMiles: LOCAL_DELIVERY.maxRadiusMiles, offerIds: p.lines.map((l) => l.offerId) }
          : { available: false, chargeMinor: null, roadMiles: q.roadMiles, durationMinutes: q.durationMinutes, fromCity, unavailableReason: 'OUT_OF_RANGE',
              message: `${p.seller.name} is ${Math.round(q.roadMiles)} miles from ${metro}: too far for AfroTransact Delivery (up to ${LOCAL_DELIVERY.maxRadiusMiles} miles). Remove it or choose another address.`,
              maxRadiusMiles: LOCAL_DELIVERY.maxRadiusMiles, offerIds: p.lines.map((l) => l.offerId) };
        const base = withDates({ key: 'afrotransact_delivery', label: '', eta: '', feeMinor: 0, free: false }, now, 1);
        const option: DeliveryOption = q.available
          ? { ...base, feeMinor: q.chargeMinor, label: `$${(q.chargeMinor / 100).toFixed(2)} · AfroTransact Delivery from ${fromCity}` }
          : { ...base, label: `Not available to this address · AfroTransact Delivery from ${fromCity}` };
        return { ...p, deliveryOptions: [option], pickupPoints: [], localDelivery, delivery: { ...p.delivery, label: option.eta } };
      }
      const options = p.deliveryOptions.map((o) => withDates(o, now, days(o)));
      const points: PickupPoint[] = !options.some((o) => o.key === 'pickup') ? [] : PICKUP_POINTS.filter((x) => (isAT ? x.owner === 'AT' && here(x.metro) : x.owner === p.seller.name))
        .map((x) => ({ id: x.id, name: x.name, address: x.addr, hours: x.hours, ready: x.ready, setBy: x.owner === 'AT' ? 'platform' : 'seller' }));
      return { ...p, deliveryOptions: options, pickupPoints: points, delivery: { ...p.delivery, label: options[0]?.eta ?? p.delivery.label } };
    });
}

function summary(ctx: Ctx, addressId: string | null): CheckoutSummary {
  const a = me(ctx);
  const addr = a.addresses.find((x) => x.id === addressId) ?? a.addresses.find((x) => x.isDefault) ?? a.addresses[0];
  const packages = checkoutPackages(ctx.session, addr?.zip.slice(0, 5) ?? ctx.session.zip, ctx.now);
  if (!packages.length) throw new MockProblem(409, 'CART_EMPTY', 'Your cart is empty', 'Select at least one item to check out.', { available: 0, inCart: 0, requested: 0 });
  const subtotal = packages.reduce((s, p) => s + p.subtotalMinor, 0);
  const shipping = packages.reduce((s, p) => s + (p.deliveryOptions[0]?.feeMinor ?? 0), 0);
  return { packages, addresses: a.addresses, paymentMethods: a.paymentMethods, subtotalMinor: subtotal, shippingMinor: shipping, taxMinor: 0,
    taxRateLabel: 'Calculated when you place your order', totalMinor: subtotal + shipping, currency: 'USD',
    sellerCount: new Set(packages.map((p) => p.seller.id)).size, publishableKey: MOCK_PUBLISHABLE_KEY };
}

function confirmation(c: { orderNumber: string; status: OrderConfirmation['status']; clientSecret: string; totalMinor: number; order: MockOrder }): OrderConfirmation {
  return { orderNumber: c.orderNumber, status: c.status, paymentClientSecret: c.clientSecret, totalMinor: c.totalMinor, currency: 'USD',
    shipments: c.order.shipments.map((s) => ({ shipmentId: s.shipmentId, label: s.label, eta: s.promisedBy ?? '', deliveryCode: s.deliveryCode })) };
}

/* ── Returns ───────────────────────────────────────────────────── */

const OPEN_RETURN = new Set(['requested', 'approved', 'auto_approved', 'label_issued', 'in_transit', 'received', 'inspected', 'refunded']);

function returnable(a: AccountState, o: MockOrder, now: Date) {
  return o.shipments
    .filter((s) => s.status === 'delivered')
    .map((s) => ({
      shipmentId: s.shipmentId,
      sellerName: s.sellerName,
      deliveredAt: s.deliveredAt,
      lines: s.lines.map((l): ReturnableLine => {
        const returned = a.returns.filter((r) => OPEN_RETURN.has(r.status)).flatMap((r) => r.lines).filter((x) => x.lineId === l.lineId).reduce((n, x) => n + x.quantity, 0);
        const perishable = o.perishable.has(l.lineId);
        const windowEnds = new Date(new Date(s.deliveredAt ?? now).getTime() + 30 * 86_400_000);
        const max = Math.max(0, l.quantity - returned);
        const open = windowEnds > now;
        return { lineId: l.lineId, atin: l.atin, title: l.title, quantity: l.quantity, maxQuantity: max, unitPriceMinor: l.unitPriceMinor,
          eligible: max > 0 && open, reportProblemOnly: perishable, sellerFaultOnly: perishable, windowEndsAt: iso(windowEnds),
          methods: perishable ? ['NO_RETURN'] : l.unitPriceMinor <= 1000 ? ['DROP_OFF', 'NO_RETURN'] : ['DROP_OFF'],
          ineligibleReason: max === 0 ? 'Already being returned' : open ? null : 'The return window has closed' };
      })
    }));
}

/** Return button facts per shipment, as orders computes them from tax-returns' eligibility (S148/S166). */
function returnFacts(a: AccountState, o: MockOrder, shipmentId: string, now: Date) {
  const lines = returnable(a, o, now).find((s) => s.shipmentId === shipmentId)?.lines ?? [];
  if (!lines.length) return { returnable: false, returnWindowEndsAt: null, problemReportOnly: false };
  const eligible = lines.filter((l) => l.eligible);
  return {
    returnable: eligible.some((l) => !l.reportProblemOnly),
    returnWindowEndsAt: lines[0]?.windowEndsAt ?? null,
    problemReportOnly: eligible.length > 0 && eligible.every((l) => l.reportProblemOnly)
  };
}

/** Payment facts (payments): per-line refunds from refunded returns and cancelled shipments; no tax on delivery in TX. */
function orderPayment(a: AccountState, o: MockOrder) {
  const refunded = new Map<string, number>();
  for (const r of a.returns.filter((x) => x.orderNumber === o.orderNumber && x.status === 'refunded'))
    for (const l of r.lines) refunded.set(l.lineId, (refunded.get(l.lineId) ?? 0) + l.unitPriceMinor * l.quantity);
  for (const s of o.shipments.filter((x) => x.status === 'cancelled')) for (const l of s.lines) refunded.set(l.lineId, l.itemsMinor);
  const shipments = o.shipments.map((s) => ({
    ...s,
    ...returnFacts(a, o, s.shipmentId, new Date()),
    lines: s.lines.map((l) => ({ ...l, refundedMinor: refunded.get(l.lineId) ?? 0 }))
  }));
  const refundedMinor = [...refunded.values()].reduce((n, x) => n + x, 0);
  const capturedMinor = o.shipments.filter((s) => s.status === 'delivered').reduce((n, s) => n + s.lines.reduce((m, l) => m + l.itemsMinor, 0), 0);
  return { shipments, payment: { status: capturedMinor ? 'captured' : 'authorized', capturedMinor, refundedMinor, refundableMinor: Math.max(0, capturedMinor - refundedMinor), shippingTaxMinor: 0 } };
}

/* ── Services ──────────────────────────────────────────────────── */

const serviceOr404 = (id: string): ServiceDetail => {
  const s = SERVICES.find((x) => x.id === id);
  if (!s) throw new MockProblem(404, 'NOT_FOUND', 'Service not found', 'That service is no longer offered.');
  return s;
};

const dayKey = (d: Date) => iso(d).slice(0, 10);

/** Live-looking availability: Sundays closed, a few taken hours per day (deterministic). */
function slotsFor(svc: ServiceDetail, date: string, a: AccountState | null, now: Date) {
  const day = new Date(`${date}T12:00:00Z`);
  if (Number.isNaN(day.getTime()) || day.getUTCDay() === 0) return [];
  const hours = Math.ceil(svc.durationMinutes / 60);
  const out: Array<{ start: string; end: string; memberIds: string[] }> = [];
  for (let h = 9; h + hours <= 18; h += 1) {
    if ((h * 7 + day.getUTCDate() + svc.id.length) % 4 === 0) continue;
    const start = new Date(`${date}T${String(h).padStart(2, '0')}:00:00-05:00`);
    if (start.getTime() < now.getTime() + 2 * 3_600_000) continue;
    if (a?.bookings.some((b) => b.startAt === iso(start) && !b.status.startsWith('CANCELLED'))) continue;
    out.push({ start: iso(start), end: iso(new Date(start.getTime() + svc.durationMinutes * 60_000)), memberIds: svc.team.map((m) => m.id) });
  }
  return out;
}

function cancellationQuote(b: { startAt: string; service: { depositMinor: number; currency: string }; policy: { kind: string; label: string } }, now: Date) {
  const hours = (new Date(b.startAt).getTime() - now.getTime()) / 3_600_000;
  const free = b.policy.kind === 'STRICT' ? hours >= 168 : b.policy.kind === 'FLEXIBLE' ? hours >= 24 : hours >= 48;
  const share = free ? 100 : b.policy.kind === 'MODERATE' && hours >= 24 ? 50 : 0;
  const refund = Math.round((b.service.depositMinor * share) / 100);
  return { refundSharePercent: share, depositMinor: b.service.depositMinor, refundMinor: refund, keptMinor: b.service.depositMinor - refund, releaseHold: true,
    policy: b.policy.kind, currency: b.service.currency,
    explanation: free ? `${b.policy.label}: free cancellation now — your full deposit comes back.` : share ? `${b.policy.label}: half the deposit is refunded this close to the appointment.` : `${b.policy.label}: the deposit is kept this close to the appointment.` };
}

const bookingOr404 = (a: AccountState, id: string) => {
  const b = a.bookings.find((x) => x.id === id);
  if (!b) throw notFound('booking');
  return b;
};

function setStatus(b: AccountState['bookings'][number], status: string) {
  const svc = SERVICES.find((s) => s.id === b.service.serviceId) ?? (SERVICES[0] as ServiceDetail);
  const next = bookingFor(svc, b.id, b.reference, status, new Date(b.startAt), { notes: b.notes, location: b.location, service: b.service, payment: b.payment });
  Object.assign(b, next);
}

/* ── Handlers ──────────────────────────────────────────────────── */

const seenClicks = new Set<string>();

export const shopperHandlers = [
  /* Search result clicks (S240): once per search and product; a mock ad token is "charged" once. */
  http.post('*/v1/search/clicks', route(async (ctx) => {
    const c = await body(ctx.request, SearchClickRequestSchema);
    const key = `${c.searchId}|${c.atin}`;
    const duplicate = seenClicks.has(key);
    seenClicks.add(key);
    const sponsored = Boolean(c.adToken);
    return json({ recorded: !duplicate, duplicate, sponsored, ...(sponsored ? { adStatus: duplicate ? 'DUPLICATE' : 'BILLABLE' } : {}) }, 202);
  })),

  /* Addresses */
  http.get('*/v1/account/addresses', route((ctx) => json({ items: me(ctx).addresses, nextCursor: null }))),
  http.post('*/v1/account/addresses', route(async (ctx) => json(saveAddress(me(ctx), null, await body(ctx.request, AddressInputSchema)), 201))),
  http.patch('*/v1/account/addresses/:id', route(async (ctx, p) => json(saveAddress(me(ctx), p.id ?? '', await body(ctx.request, AddressInputSchema))))),
  http.delete('*/v1/account/addresses/:id', route((ctx, p) => {
    const a = me(ctx);
    const gone = a.addresses.find((x) => x.id === p.id);
    if (!gone) throw new MockProblem(404, 'ADDRESS_NOT_FOUND', 'Address not found', 'That address no longer exists.');
    a.addresses = a.addresses.filter((x) => x !== gone);
    if (gone.isDefault && a.addresses[0]) a.addresses[0].isDefault = true;
    return noContent();
  })),
  http.post('*/v1/account/addresses/:id/default', route((ctx, p) => {
    const a = me(ctx);
    const target = a.addresses.find((x) => x.id === p.id);
    if (!target) throw new MockProblem(404, 'ADDRESS_NOT_FOUND', 'Address not found', 'That address no longer exists.');
    a.addresses.forEach((x) => { x.isDefault = x === target; });
    return json(target);
  })),

  /* Payment methods (Stripe in mock mode: the browser's mock Payment Element confirms through mock-stripe) */
  http.get('*/v1/account/payment-methods', route((ctx) => json({ items: me(ctx).paymentMethods, nextCursor: null }))),
  http.post('*/v1/account/payment-methods/setup-intents', route((ctx) => {
    const a = me(ctx);
    const secret = `seti_mock_${seq()}_secret_mock`;
    a.setupIntents.add(secret);
    return json({ clientSecret: secret, publishableKey: MOCK_PUBLISHABLE_KEY }, 201);
  })),
  http.post('*/v1/account/payment-methods/:id/default', route((ctx, p) => {
    const a = me(ctx);
    const pm = a.paymentMethods.find((x) => x.id === p.id);
    if (!pm) throw new MockProblem(404, 'PAYMENT_METHOD_NOT_FOUND', 'Payment method not found', 'That payment method no longer exists.');
    a.paymentMethods.forEach((x) => { x.isDefault = x === pm; });
    return json(pm);
  })),
  http.delete('*/v1/account/payment-methods/:id', route((ctx, p) => {
    const a = me(ctx);
    const pm = a.paymentMethods.find((x) => x.id === p.id);
    if (!pm) throw new MockProblem(404, 'PAYMENT_METHOD_NOT_FOUND', 'Payment method not found', 'That payment method no longer exists.');
    if (pm.isDefault && a.paymentMethods.length > 1) {
      throw new MockProblem(409, 'CONFLICT', 'Choose another default first', 'Your default payment method can’t be removed. Make another one the default first.');
    }
    a.paymentMethods = a.paymentMethods.filter((x) => x !== pm);
    return noContent();
  })),
  http.post('*/v1/account/payment-sheet', route(() => problem(503, 'UPSTREAM_UNAVAILABLE', 'Service unavailable', 'Saved cards in PaymentSheet are not available yet.'))),
  http.post('*/v1/mock-stripe/setup-intents/:secret/confirm', route(async (ctx, p) => {
    const a = me(ctx);
    if (!a.setupIntents.has(p.secret ?? '')) throw notFound('setup intent');
    a.setupIntents.delete(p.secret ?? '');
    return json(addMethod(a, await body(ctx.request, MockCardSchema)), 201);
  })),

  /* Orders */
  http.get('*/v1/account/orders', route((ctx) => json({
    items: me(ctx).orders.map((o) => ({ orderNumber: o.orderNumber, placedAt: o.placedAt, totalMinor: o.totalMinor, currency: o.currency, shipTo: o.shipTo, status: o.status,
      shipments: o.shipments.map((s) => ({ shipmentId: s.shipmentId, label: s.label, status: s.statusLabel, items: s.items, cancellable: s.cancellable, carrier: s.carrier,
        trackingNumber: s.trackingNumber, promisedBy: s.promisedBy, deliveredAt: s.deliveredAt, deliveryCode: s.deliveryCode,
        ...returnFacts(me(ctx), o, s.shipmentId, ctx.now) })) })),
    nextCursor: null
  }))),
  http.get('*/v1/account/orders/:orderNumber', route((ctx, p) => {
    const o = me(ctx).orders.find((x) => x.orderNumber === p.orderNumber);
    if (!o) throw new MockProblem(404, 'ORDER_NOT_FOUND', 'Order not found', 'We couldn’t find that order.');
    const a = me(ctx);
    const { perishable: _p, ...detail } = o;
    return json({ ...detail, ...orderPayment(a, o) });
  })),
  http.post('*/v1/account/orders/:orderNumber/shipments/:shipmentId/cancel', route((ctx, p) => {
    const o = me(ctx).orders.find((x) => x.orderNumber === p.orderNumber);
    const s = o?.shipments.find((x) => x.shipmentId === p.shipmentId);
    if (!o || !s) throw new MockProblem(404, 'ORDER_NOT_FOUND', 'Order not found', 'We couldn’t find that shipment.');
    if (!s.cancellable) throw new MockProblem(409, 'CONFLICT', 'Can’t cancel now', 'This shipment is already packed or on its way, so it can’t be cancelled.');
    Object.assign(s, { status: 'cancelled', statusLabel: 'Cancelled', cancellable: false, deliveryCode: null,
      timeline: [...s.timeline, { status: 'cancelled', label: 'Cancelled by you — refund on its way', at: iso(ctx.now) }] } satisfies Partial<ShipmentDetail>);
    if (o.shipments.every((x) => x.status === 'cancelled')) o.status = 'cancelled';
    return json({ orderNumber: o.orderNumber, placedAt: o.placedAt, totalMinor: o.totalMinor, currency: o.currency, shipTo: o.shipTo, status: o.status,
      shipments: o.shipments.map((x) => ({ shipmentId: x.shipmentId, label: x.label, status: x.statusLabel, items: x.items, cancellable: x.cancellable })) });
  })),

  /* Returns */
  http.get('*/v1/account/orders/:orderNumber/returnable', route((ctx, p) => {
    const a = me(ctx);
    const o = a.orders.find((x) => x.orderNumber === p.orderNumber);
    if (!o) throw new MockProblem(404, 'ORDER_NOT_FOUND', 'Order not found', 'We couldn’t find that order.');
    return json({ orderNumber: o.orderNumber, currency: o.currency, shipments: returnable(a, o, ctx.now) });
  })),
  http.post('*/v1/account/returns', route(async (ctx) => {
    const a = me(ctx);
    const raw = (await body(ctx.request, z.looseObject({ orderNumber: z.string().min(1) }))) as Record<string, unknown>;
    const o = a.orders.find((x) => x.orderNumber === raw.orderNumber);
    if (!o) throw new MockProblem(404, 'ORDER_NOT_FOUND', 'Order not found', 'We couldn’t find that order.');
    const shipments = returnable(a, o, ctx.now);
    let req;
    if (!raw.lines && typeof raw.atin === 'string') {
      const reason = ReturnReasonSchema.safeParse(raw.reason);
      const hit = shipments.flatMap((s) => s.lines.map((l) => ({ s, l }))).find((x) => x.l.atin === raw.atin && x.l.eligible);
      if (!reason.success) throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'Choose a reason.', { errors: { reason: 'Choose a reason' } });
      if (!hit) throw new MockProblem(409, 'CONFLICT', 'Not returnable', 'This item can’t be returned.');
      req = { orderNumber: o.orderNumber, shipmentId: hit.s.shipmentId, method: hit.l.methods[0] ?? 'DROP_OFF', lines: [{ lineId: hit.l.lineId, quantity: 1, reason: reason.data }] };
    } else {
      const parsed = StartReturnRequestSchema.safeParse(raw);
      if (!parsed.success) {
        const errors: Record<string, string> = {};
        parsed.error.issues.forEach((i) => { errors[i.path.join('.') || '_'] = i.message; });
        throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { errors });
      }
      req = parsed.data;
    }
    const sh = shipments.find((s) => s.shipmentId === req.shipmentId);
    if (!sh) throw new MockProblem(422, 'VALIDATION_FAILED', 'Can’t start this return', 'That shipment hasn’t been delivered.', { errors: { shipmentId: 'Not delivered' } });
    const lines = req.lines.map((l, i) => {
      const line = sh.lines.find((x) => x.lineId === l.lineId);
      if (!line || !line.eligible || l.quantity > line.maxQuantity) throw new MockProblem(422, 'VALIDATION_FAILED', 'Can’t start this return', `${line?.title ?? 'This item'} can’t be returned.`, { errors: { [`lines.${i}.lineId`]: 'Not returnable' } });
      if (!line.methods.includes(req.method)) throw new MockProblem(422, 'VALIDATION_FAILED', 'Can’t start this return', 'Choose another return method.', { errors: { method: 'Not available for these items' } });
      if (line.sellerFaultOnly && !SELLER_FAULT_REASONS.includes(l.reason)) throw new MockProblem(422, 'VALIDATION_FAILED', 'Report a problem', 'Food items can only be reported as damaged, wrong, spoiled or not as described.', { errors: { [`lines.${i}.reason`]: 'Choose what went wrong' } });
      return { lineId: line.lineId, atin: line.atin, title: line.title, quantity: l.quantity, unitPriceMinor: line.unitPriceMinor, reason: l.reason, outcome: null };
    });
    const items = lines.reduce((s, l) => s + l.unitPriceMinor * l.quantity, 0);
    const refund = Math.round(items * 1.0825);
    const n = seq();
    const number = `RMA-${String(n).padStart(8, '0')}`;
    const noReturn = req.method === 'NO_RETURN';
    const rma: ReturnDetail = {
      rmaId: `rma-${n}`, orderNumber: o.orderNumber, title: lines.length > 1 ? `${lines[0]?.title} and ${lines.length - 1} more` : (lines[0]?.title ?? 'Return'),
      status: noReturn ? 'refunded' : 'auto_approved', refundMinor: refund, number, method: req.method, reason: lines[0]?.reason, refundState: noReturn ? 'ISSUED' : 'NONE',
      currency: 'USD', cancellable: !noReturn, createdAt: iso(ctx.now), labelUrl: req.method === 'DROP_OFF' ? `https://labels.afrotransact.example/${number}.pdf` : null,
      qrPayload: req.method === 'DROP_OFF' ? `AFROTRANSACT-RETURN:${number}:9400111899560${n}` : null, handoverCode: req.method === 'DRIVER_PICKUP' ? '482913' : null,
      dropOffBy: req.method === 'DROP_OFF' ? iso(new Date(ctx.now.getTime() + 14 * 86_400_000)) : null, carrier: req.method === 'DROP_OFF' ? 'usps' : null,
      trackingNumber: req.method === 'DROP_OFF' ? `9400111899560${n}` : null, lines, quote: null
    };
    rma.quote = MOCK_REFUND_QUOTE(rma);
    a.returns.unshift(rma);
    const { lines: _l, quote: _q, ...summary } = rma;
    return json(summary, 201);
  })),
  http.get('*/v1/account/returns', route((ctx) => {
    const a = me(ctx);
    const orderNumber = new URL(ctx.request.url).searchParams.get('orderNumber');
    if (orderNumber && !a.orders.some((o) => o.orderNumber === orderNumber)) throw new MockProblem(404, 'ORDER_NOT_FOUND', 'Order not found', 'We couldn’t find that order.');
    const list = orderNumber ? a.returns.filter((r) => r.orderNumber === orderNumber) : a.returns;
    return json({ items: list.map(({ lines: _l, quote: _q, ...r }) => r), nextCursor: null });
  })),
  http.get('*/v1/account/returns/:rmaId', route((ctx, p) => {
    const id = String(p.rmaId ?? '');
    const r = me(ctx).returns.find((x) => x.rmaId === id || (x.number && x.number === id.toUpperCase()));
    if (!r) throw notFound('return');
    return json(r);
  })),
  http.post('*/v1/account/returns/:rmaId/cancel', route((ctx, p) => {
    const r = me(ctx).returns.find((x) => x.rmaId === p.rmaId);
    if (!r) throw notFound('return');
    if (!r.cancellable) throw new MockProblem(409, 'CONFLICT', 'Can’t change this return', 'This return can no longer be cancelled.');
    Object.assign(r, { status: 'cancelled', cancellable: false, qrPayload: null, labelUrl: null });
    const { lines: _l, quote: _q, ...summary } = r;
    return json(summary);
  })),

  /* Login & security */
  http.get('*/v1/account/security', route((ctx) => json({ ...me(ctx).security, passkeyRegistrationUrl: 'http://localhost:8100/webauthn/register' }))),
  http.put('*/v1/account/password', route(async (ctx) => {
    const u = requireUser(ctx);
    const b = await body(ctx.request, PasswordChangeSchema);
    if ((b.currentPassword ?? '') !== u.password) throw new MockProblem(422, 'VALIDATION_FAILED', 'Check your password', 'Your current password is incorrect.', { errors: { currentPassword: 'Your current password is incorrect.' } });
    u.password = b.newPassword;
    return noContent();
  })),
  http.post('*/v1/account/mfa/totp', route((ctx) => {
    const a = me(ctx);
    a.pendingTotp = true;
    return json({ secret: 'JBSWY3DPEHPK3PXP', otpauthUri: `otpauth://totp/AfroTransact:${requireUser(ctx).email}?secret=JBSWY3DPEHPK3PXP&issuer=AfroTransact` });
  })),
  http.post('*/v1/account/mfa/totp/confirm', route(async (ctx) => {
    const a = me(ctx);
    const { code } = await body(ctx.request, z.object({ code: z.string().regex(/^\d{6}$/, 'Enter the 6-digit code') }));
    if (code !== DEMO_CODE || !a.pendingTotp) throw new MockProblem(422, 'INVALID_CODE', 'Incorrect code', 'That code is incorrect or expired.', { errors: { code: 'That code is incorrect or expired.' } });
    const first = !a.security.mfa.totp && !a.security.mfa.sms;
    a.security.mfa.totp = true;
    a.pendingTotp = false;
    return json({ recoveryCodes: first ? ['7KQ2-M9XD', 'P4WZ-3HNA', 'C8RT-YV6E', 'L2JF-QK7B'] : [] });
  })),
  http.delete('*/v1/account/mfa/totp', route((ctx) => { me(ctx).security.mfa.totp = false; return noContent(); })),
  http.post('*/v1/account/mfa/sms', route((ctx) => { me(ctx).pendingSms = true; return json({ codeExpiresAt: iso(new Date(ctx.now.getTime() + 600_000)) }, 202); })),
  http.post('*/v1/account/mfa/sms/confirm', route(async (ctx) => {
    const a = me(ctx);
    const { code } = await body(ctx.request, z.object({ code: z.string().regex(/^\d{6}$/, 'Enter the 6-digit code') }));
    if (code !== DEMO_CODE || !a.pendingSms) throw new MockProblem(422, 'INVALID_CODE', 'Incorrect code', 'That code is incorrect or expired.', { errors: { code: 'That code is incorrect or expired.' } });
    a.security.mfa.sms = true;
    a.pendingSms = false;
    return json({ recoveryCodes: [] });
  })),
  http.delete('*/v1/account/mfa/sms', route((ctx) => { me(ctx).security.mfa.sms = false; return noContent(); })),
  http.post('*/v1/account/mfa/recovery-codes', route((ctx) => {
    me(ctx).security.mfa.recoveryCodesLeft = 8;
    return json({ recoveryCodes: ['Q7M2-XK4D', 'H8NA-2PWZ', 'V6YE-C3RT', 'B7JF-L9QK', 'D4TS-8MHW', 'N2CX-6GAE', 'R5UP-1ZBY', 'W9KL-4FJV'] });
  })),
  http.delete('*/v1/account/passkeys/:id', route((ctx, p) => {
    const s = me(ctx).security;
    if (!s.linkedAccounts.hasPassword && s.passkeys.length <= 1 && s.linkedAccounts.accounts.length === 0) {
      throw new MockProblem(409, 'CONFLICT', 'Keep one way to sign in', 'Add another sign-in method before removing this one.');
    }
    s.passkeys = s.passkeys.filter((x) => x.credentialId !== p.id);
    return noContent();
  })),
  http.delete('*/v1/account/sessions/:id', route((ctx, p) => { const s = me(ctx).security; s.sessions = s.sessions.filter((x) => x.id !== p.id || x.current); return noContent(); })),
  http.post('*/v1/account/sessions/revoke-others', route((ctx) => {
    const s = me(ctx).security;
    const before = s.sessions.length;
    s.sessions = s.sessions.filter((x) => x.current);
    return json({ revoked: before - s.sessions.length });
  })),
  http.delete('*/v1/account/devices/:id', route((ctx, p) => { const s = me(ctx).security; s.devices = s.devices.filter((x) => x.id !== p.id); return noContent(); })),
  http.delete('*/v1/account/linked-accounts/:provider', route((ctx, p) => {
    const l = me(ctx).security.linkedAccounts;
    l.accounts = l.accounts.filter((x) => x.provider !== p.provider);
    return json(l);
  })),
  http.get('*/v1/account/notification-preferences', route((ctx) => json(me(ctx).prefs))),
  http.put('*/v1/account/notification-preferences', route(async (ctx) => {
    const a = me(ctx);
    a.prefs = await body(ctx.request, NotificationPreferencesSchema);
    return json(a.prefs);
  })),

  /* Checkout */
  http.get('*/v1/checkout/summary', route((ctx) => json(summary(ctx, new URL(ctx.request.url).searchParams.get('addressId'))))),
  http.post('*/v1/checkout/orders', route(async (ctx) => {
    const a = me(ctx);
    const key = idemKey(ctx);
    if (key.length < 16 || key.length > 64) throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'Send a 16–64 character Idempotency-Key header.', { errors: { 'Idempotency-Key': 'Required' } });
    const replay = a.checkouts.get(key);
    if (replay) return json(confirmation(replay), 201);
    const req = await body(ctx.request, PlaceOrderRequestSchema);
    const errors: Record<string, string> = {};
    const addr = a.addresses.find((x) => x.id === req.addressId);
    if (!addr) errors.addressId = 'Choose a delivery address';
    if (!req.paymentMethodId && !req.wallet) errors.paymentMethodId = 'Choose a payment method';
    if (req.paymentMethodId && !a.paymentMethods.some((x) => x.id === req.paymentMethodId)) errors.paymentMethodId = 'Unknown payment method';
    const s = summary(ctx, req.addressId);
    const far = s.packages.find((p) => p.localDelivery && !p.localDelivery.available);
    if (far?.localDelivery)
      throw new MockProblem(422, 'DELIVERY_OUT_OF_RANGE', 'Too far for AfroTransact Delivery', far.localDelivery.message ?? 'A seller’s store is too far from this address.', {
        errors: { deliverySelections: far.localDelivery.message ?? 'A seller’s store is too far from this address.' }
      });
    s.packages.forEach((p, i) => {
      const sel = req.deliverySelections.find((x) => x.packageId === p.packageId);
      if (!sel || !p.deliveryOptions.some((o) => o.key === sel.option)) errors[`deliverySelections.${i}.option`] = 'Choose a delivery option';
      else if (sel.option === 'pickup' && !p.pickupPoints?.some((x) => x.id === sel.pickupPointId)) errors[`deliverySelections.${i}.pickupPointId`] = 'Choose a pickup location';
      else if (sel.option === 'afrotransact_delivery' && p.lines.some((l) => l.perishable) && sel.preferences?.handover && sel.preferences.handover !== 'hand') {
        errors[`deliverySelections.${i}.preferences.handover`] = 'Perishable items are handed to you with a code';
      }
    });
    if (Object.keys(errors).length) throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { errors });
    const n = seq();
    const orderNumber = `AT-2026-${String(200000 + n).padStart(6, '0')}`;
    const shipping = s.packages.reduce((sum, p) => {
      const sel = req.deliverySelections.find((x) => x.packageId === p.packageId);
      return sum + (p.deliveryOptions.find((o) => o.key === sel?.option)?.feeMinor ?? 0);
    }, 0);
    const tax = Math.round(s.subtotalMinor * (addr?.state === 'TX' ? 0.0825 : addr?.state === 'NY' ? 0.08875 : 0.07));
    const shipments = s.packages.map((p, i): ShipmentDetail => {
      const sel = req.deliverySelections.find((x) => x.packageId === p.packageId);
      const opt = p.deliveryOptions.find((o) => o.key === sel?.option);
      const needsCode = (sel?.option === 'afrotransact_delivery' || sel?.option === 'pickup') && (p.lines.some((l) => l.perishable) || sel.preferences?.handover === 'hand');
      return { shipmentId: `${orderNumber}-s${i + 1}`, label: `${p.heading} · ${opt?.label ?? ''}`.trim(), status: 'placed', statusLabel: 'Order placed',
        items: p.lines.map((l) => `${l.title} × ${l.quantity}`).join(' · '), sellerName: p.seller.name, deliveryMethod: sel?.option ?? 'carrier', cancellable: true,
        carrier: null, trackingNumber: null, promisedFrom: opt?.earliestDate ?? null, promisedBy: opt?.latestDate ?? null, deliveredAt: null,
        deliveryCode: needsCode ? String(1000 + ((n * 7919) % 9000)) : null,
        lines: p.lines.map((l) => ({ lineId: l.lineId, atin: l.atin, title: l.title, imageUrl: null, quantity: l.quantity, unitPriceMinor: l.priceMinor, itemsMinor: l.lineTotalMinor })),
        timeline: [{ status: 'placed', label: 'Order placed', at: iso(ctx.now) }],
        localDelivery: p.localDelivery?.available
          ? { roadMiles: p.localDelivery.roadMiles, durationMinutes: p.localDelivery.durationMinutes, chargeMinor: p.localDelivery.chargeMinor ?? 0 }
          : null };
    });
    const order: MockOrder = { orderNumber, placedAt: iso(ctx.now), status: 'placed', currency: 'USD', itemsMinor: s.subtotalMinor, shippingMinor: shipping, taxMinor: tax,
      discountMinor: 0, totalMinor: s.subtotalMinor + shipping + tax, shipTo: `${addr?.line1}, ${addr?.city}`,
      shippingAddress: addr ? { name: addr.name, line1: addr.line1, line2: addr.line2, city: addr.city, state: addr.state, zip: addr.zip } : null, shipments,
      perishable: new Set(s.packages.flatMap((p) => p.lines.filter((l) => l.perishable).map((l) => l.lineId))) };
    const pending = { orderNumber, clientSecret: `pi_mock_${n}_secret_mock`, totalMinor: order.totalMinor, status: 'awaiting_payment' as const, order,
      lineIds: s.packages.flatMap((p) => p.lines.map((l) => l.lineId)) };
    a.checkouts.set(key, pending);
    return json(confirmation(pending), 201);
  })),
  http.get('*/v1/checkout/orders/:orderNumber', route((ctx, p) => {
    const c = [...me(ctx).checkouts.values()].find((x) => x.orderNumber === p.orderNumber);
    if (!c) throw new MockProblem(404, 'ORDER_NOT_FOUND', 'Order not found', 'We couldn’t find that order.');
    return json(confirmation(c));
  })),
  http.post('*/v1/checkout/orders/:orderNumber/confirm', route((ctx, p) => {
    const a = me(ctx);
    const c = [...a.checkouts.values()].find((x) => x.orderNumber === p.orderNumber);
    if (!c) throw new MockProblem(404, 'ORDER_NOT_FOUND', 'Order not found', 'We couldn’t find that order.');
    if (c.status !== 'placed') {
      Object.assign(c, { status: 'placed' });
      a.orders.unshift(c.order);
      ctx.session.lines = ctx.session.lines.filter((l) => !c.lineIds.includes(l.lineId));
    }
    return json(confirmation(c));
  })),

  /* Services & bookings */
  http.get('*/v1/services/search', route((ctx) => {
    const url = new URL(ctx.request.url);
    const q = (url.searchParams.get('q') ?? '').trim().toLowerCase();
    const category = url.searchParams.get('category') ?? '';
    const zip = url.searchParams.get('zip') ?? ctx.session.zip;
    if (!/^\d{5}$/.test(zip)) throw new MockProblem(422, 'VALIDATION_FAILED', 'Check the ZIP code', 'Enter a 5-digit US ZIP code.', { errors: { zip: 'Enter a 5-digit US ZIP code.' } });
    const local = zip.startsWith('77') ? 'TX' : zip.startsWith('30') ? 'GA' : null;
    const services = SERVICES.filter((s) => {
      const where = PROVIDERS[s.orgId]?.location ?? '';
      const reach = s.modes.includes('VIRTUAL') || (local !== null && where.endsWith(local));
      const text = `${s.name} ${s.providerName} ${s.category} ${s.description}`.toLowerCase();
      return reach && (!q || q.split(/\s+/).every((w) => text.includes(w))) && (!category || s.category === category);
    }).map((s) => ({ serviceId: s.id, orgId: s.orgId, name: s.name, providerName: s.providerName, description: s.description, category: s.category,
      durationMinutes: s.durationMinutes, priceFromMinor: s.priceMinor, depositMinor: s.depositMinor, currency: s.currency, modes: s.modes,
      rating: PROVIDERS[s.orgId]?.rating ?? 0, ratingCount: PROVIDERS[s.orgId]?.reviews ?? 0, nextAvailable: 'Tomorrow', location: PROVIDERS[s.orgId]?.location ?? '' }));
    return json({ zip, city: zip.startsWith('77') ? 'Houston' : zip.startsWith('30') ? 'Atlanta' : zip.startsWith('10') ? 'Bronx' : '', services });
  })),
  http.get('*/v1/services/:id/days', route((ctx, p) => {
    const svc = serviceOr404(p.id ?? '');
    const n = Math.min(62, Number(new URL(ctx.request.url).searchParams.get('days') ?? '14') || 14);
    const days = Array.from({ length: n }, (_, i) => {
      const d = dayKey(new Date(ctx.now.getTime() + (i + 1) * 86_400_000));
      return { date: d, slots: slotsFor(svc, d, null, ctx.now).length };
    });
    return json({ days });
  })),
  http.get('*/v1/services/:id/slots', route((ctx, p) => {
    const svc = serviceOr404(p.id ?? '');
    const date = new URL(ctx.request.url).searchParams.get('date');
    if (!date) throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'Choose a day.', { errors: { date: 'Choose a day' } });
    const u = ctx.session.userId ? account(ctx.session.userId) : null;
    return json({ slots: slotsFor(svc, date, u, ctx.now) });
  })),
  http.get('*/v1/services/:id/summary', route((ctx, p) => {
    const svc = serviceOr404(p.id ?? '');
    const url = new URL(ctx.request.url);
    const addOns = (url.searchParams.get('addOnIds') ?? '').split(',').filter(Boolean).map((id) => svc.addOns.find((x) => x.id === id)).filter((x): x is NonNullable<typeof x> => !!x);
    const price = svc.priceMinor + addOns.reduce((s, x) => s + x.priceMinor, 0);
    return json({ serviceId: svc.id, serviceName: svc.name, providerName: svc.providerName, durationMinutes: svc.durationMinutes + addOns.reduce((s, x) => s + x.extraMinutes, 0),
      priceMinor: price, depositNowMinor: svc.depositMinor, balanceLaterMinor: price - svc.depositMinor, currency: svc.currency,
      mode: url.searchParams.get('mode') ?? svc.modes[0] ?? 'IN_PERSON', addOns: addOns.map((x) => x.name), policy: svc.policy.kind, policyText: svc.policy.text,
      instantBooking: svc.instantBooking, providerAnswersWithinMinutes: svc.instantBooking ? null : 120 });
  })),
  http.get('*/v1/services/:id', route((_ctx, p) => json(serviceOr404(p.id ?? '')))),
  http.get('*/v1/service-providers/:orgId', route((_ctx, p) => {
    const prov = PROVIDERS[p.orgId ?? ''];
    if (!prov) throw new MockProblem(404, 'NOT_FOUND', 'Provider not found', 'That provider is no longer on AfroTransact.');
    const services = SERVICES.filter((s) => s.orgId === p.orgId);
    const first = services[0] as ServiceDetail;
    return json({ orgId: p.orgId, displayName: prov.displayName, timeZone: 'America/Chicago', location: prov.location, policy: first.policy, instantBooking: first.instantBooking,
      team: first.team, services, design: { slug: prov.slug, tagline: prov.tagline, layout: prov.layout, header: prov.layout === 'CLASSIC' ? 'BAND' : 'COVER',
        buttonShape: 'ROUNDED', coverUrl: null, tokens: {}, social: [{ network: 'instagram', url: `https://instagram.com/${prov.slug.replace(/-/g, '')}` }, { network: 'website', url: `https://${prov.slug}.example` }],
        gallery: [{ url: '', caption: 'Recent work' }, { url: '', caption: 'Studio' }, { url: '', caption: 'Happy clients' }],
        featured: [{ id: 'f1', headline: `Book ${first.name}`, body: first.description, url: null, serviceId: first.id }] } });
  })),
  http.post('*/v1/services/bookings', route(async (ctx) => {
    const a = me(ctx);
    const req = await body(ctx.request, BookingRequestSchema);
    const svc = serviceOr404(req.serviceId);
    const start = new Date(req.startAt);
    if (Number.isNaN(start.getTime()) || start < ctx.now) throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'Choose a time.', { errors: { startAt: 'Choose a time' } });
    if (!slotsFor(svc, dayKey(new Date(start.getTime() + 5 * 3_600_000)), a, ctx.now).some((s) => s.start === iso(start))) {
      throw new MockProblem(409, 'CONFLICT', 'That time was just taken', 'Someone booked that time a moment ago. Choose another time.', { errors: { startAt: 'That time was just taken' } });
    }
    if (req.mode === 'AT_HOME' && !req.location?.trim()) throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'Enter the address for the home visit.', { errors: { location: 'Enter the address for the home visit' } });
    const n = seq();
    const b = bookingFor(svc, `bk-${n}`, `BK-${n.toString(36).toUpperCase()}`, 'PENDING_PAYMENT', start, { notes: req.notes ?? '', location: req.location || (PROVIDERS[svc.orgId]?.location ?? '') });
    b.service.mode = req.mode;
    a.bookings.unshift(b);
    return json({ booking: b, payment: { clientSecret: `pi_mock_bk_${n}_secret_mock`, publishableKey: MOCK_PUBLISHABLE_KEY } }, 201);
  })),
  http.get('*/v1/account/bookings', route((ctx) => json({ items: me(ctx).bookings }))),
  http.get('*/v1/account/bookings/:id', route((ctx, p) => json(bookingOr404(me(ctx), p.id ?? '')))),
  http.get('*/v1/account/bookings/:id/cancellation-quote', route((ctx, p) => json(cancellationQuote(bookingOr404(me(ctx), p.id ?? ''), ctx.now)))),
  http.post('*/v1/account/bookings/:id/cancel', route((ctx, p) => {
    const b = bookingOr404(me(ctx), p.id ?? '');
    if (!b.canCancel) throw new MockProblem(409, 'CONFLICT', 'Can’t do that now', 'This booking can’t be cancelled any more.');
    const applied = cancellationQuote(b, ctx.now);
    setStatus(b, 'CANCELLED_CUSTOMER');
    b.payment = { ...b.payment, refundedMinor: applied.refundMinor, state: 'REFUNDED' };
    return json({ booking: b, applied });
  })),
  http.post('*/v1/account/bookings/:id/reschedule', route(async (ctx, p) => {
    const b = bookingOr404(me(ctx), p.id ?? '');
    const { startAt } = await body(ctx.request, z.object({ startAt: z.string().min(1) }));
    if (!b.canReschedule) throw new MockProblem(409, 'CONFLICT', 'Can’t do that now', 'This booking can’t be rescheduled.');
    setStatus(b, 'RESCHEDULE_REQUESTED');
    b.proposedStartAt = startAt;
    return json(b);
  })),
  http.post('*/v1/account/bookings/:id/new-time/:decision', route((ctx, p) => {
    const b = bookingOr404(me(ctx), p.id ?? '');
    if (b.status !== 'RESCHEDULE_PROPOSED' && b.status !== 'RESCHEDULE_REQUESTED') throw new MockProblem(409, 'CONFLICT', 'Can’t do that now', 'There is no new time to answer.');
    const proposed = b.proposedStartAt;
    if (p.decision === 'accept' && proposed) b.startAt = proposed;
    setStatus(b, 'CONFIRMED');
    b.proposedStartAt = null;
    return json(b);
  })),
  http.post('*/v1/account/bookings/:id/review', route(async (ctx, p) => {
    const b = bookingOr404(me(ctx), p.id ?? '');
    const r = await body(ctx.request, z.object({ rating: z.number().int().min(1, 'Choose 1 to 5 stars').max(5, 'Choose 1 to 5 stars'), text: z.string().max(2000).optional() }));
    if (!b.canReview) throw new MockProblem(409, 'CONFLICT', 'Already reviewed', 'You can rate a completed booking once.');
    b.canReview = false;
    return json({ bookingId: b.id, rating: r.rating, text: r.text ?? '' }, 201);
  })),
  http.post('*/v1/account/bookings/:id/payment/sync', route((ctx, p) => {
    const b = bookingOr404(me(ctx), p.id ?? '');
    if (b.status === 'PENDING_PAYMENT') {
      const svc = serviceOr404(b.service.serviceId);
      const mode = b.service.mode;
      setStatus(b, svc.instantBooking ? 'CONFIRMED' : 'REQUESTED');
      b.service.mode = mode;
    }
    return json(b);
  })),

  /* Lola: one turn as Server-Sent Events */
  http.post('*/v1/lola/chat', route(async (ctx) => {
    requireUser(ctx);
    const req = await body(ctx.request, LolaChatRequestSchema);
    return new HttpResponse(lolaStream(req, ctx.session, ctx.now), { status: 200, headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache, no-transform' } });
  })),
  http.get('*/v1/lola/sessions/:id/messages', route((ctx) => { requireUser(ctx); return json({ items: [] }); }))
];

