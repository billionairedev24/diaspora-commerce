import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { createStaffApiClient, isApiError, staffPreorderKeys } from '../src';
import { MOCK_STAFF_ROLE_COOKIE, resetStaffDb } from '../src/mocks/staff';
import { seedFeeRules } from '../src/mocks/staff/preorders';
import { handleStaffMockRequest } from '../src/mocks/staff-runtime';
import {
  areaFromKey,
  areaOn,
  decisionReasonProblem,
  effectiveFees,
  lateAddAddressSnapshot,
  type LateAddCustomer,
  lateAddProblems,
  LINK_EXPIRY_MAX_HOURS,
  linkExpiryProblem,
  preorderChecks,
  preorderSmsLink,
  preorderStatus,
  type PreorderWindow,
  type PreorderWindowInput,
  setAreasOn,
  smsPreview,
  tabOf,
  toCsvText,
  windowAreaOn
} from '../src/staff-preorders';

const api = (roles?: string) =>
  createStaffApiClient({
    baseUrl: 'http://staff.test/api/bff',
    headers: roles ? { cookie: `${MOCK_STAFF_ROLE_COOKIE}=${roles}` } : {},
    fetch: (req) => handleStaffMockRequest(req)
  }).preorders;

const inputOf = (w: PreorderWindow): PreorderWindowInput => ({
  name: w.name, targets: w.targets.map((t) => t.key), opensAt: w.opensAt, closesAt: w.closesAt, deliversOn: w.deliversOn, ribbon: w.ribbon, headline: w.headline,
  smsCopy: w.smsCopy, categories: w.categories, door: w.door, pickup: w.pickup, pickupPoint: w.pickupPoint, doorFeeOverride: w.doorFeeOverride,
  pickupFeeOverride: w.pickupFeeOverride, showCount: w.showCount, socialBudget: w.socialBudget, comms: w.comms
});

describe('pre-order rules', () => {
  it('derives the status from the dates', () => {
    const w = { draft: false, active: true, opensAt: '2026-10-06T14:00:00Z', closesAt: '2026-10-23T04:59:00Z', deliversOn: '2026-10-25' };
    expect(preorderStatus(w, Date.parse('2026-10-01T00:00:00Z'))).toBe('UPCOMING');
    expect(preorderStatus(w, Date.parse('2026-10-10T00:00:00Z'))).toBe('OPEN');
    expect(preorderStatus(w, Date.parse('2026-10-24T00:00:00Z'))).toBe('CLOSED');
    expect(preorderStatus(w, Date.parse('2026-10-28T00:00:00Z'))).toBe('DELIVERED');
    expect(preorderStatus({ ...w, active: false }, 0)).toBe('PAUSED');
    expect(preorderStatus({ ...w, draft: true, active: false }, 0)).toBe('DRAFT');
  });

  it('lists the seven publish checks', () => {
    const base = { name: 'X', targets: ['US/TX/Lubbock'], opensAt: '2026-10-20T14:00:00Z', closesAt: '2026-11-01T23:00:00Z', deliversOn: '2026-11-04', ribbon: 'Open', door: true, pickup: false };
    expect(preorderChecks(base, true).every((c) => c.ok)).toBe(true);
    const bad = preorderChecks({ ...base, name: ' ', targets: [], closesAt: '2026-10-19T00:00:00Z', deliversOn: '2026-10-10', ribbon: '', door: false }, false);
    expect(bad.filter((c) => !c.ok).map((c) => c.key)).toEqual(['name', 'area', 'dates', 'delivery', 'hero', 'ribbon', 'fulfilment']);
  });

  it('lets a city row override its region row and switches whole scopes in bulk', () => {
    const areas = [areaFromKey('US/TX', false), areaFromKey('US/TX/Lubbock'), areaFromKey('CA/ON/Toronto')];
    expect(areaOn(areas, 'US', 'TX', 'lubbock')).toBe(true);
    expect(areaOn(areas, 'US', 'TX', 'Austin')).toBe(false);
    const off = setAreasOn(areas, ['US'], false);
    expect(areaOn(off, 'US', 'TX', 'Lubbock')).toBe(false);
    expect(areaOn(off, 'CA', 'ON', 'Toronto')).toBe(true);
    const regionOn = setAreasOn(off, ['US/TX'], true);
    expect(areaOn(regionOn, 'US', 'TX', 'Austin')).toBe(true);
    expect(windowAreaOn(off, ['US/TX/Lubbock', 'CA/ON/Toronto'])).toBe(true);
    expect(windowAreaOn(off, ['US/TX'])).toBe(false);
  });

  it('uses per-window fee overrides before the delivery fee rules', () => {
    const { rules } = seedFeeRules(Date.parse('2026-10-10T15:00:00Z'));
    const cad = effectiveFees({ doorFeeOverride: null, pickupFeeOverride: null }, rules, 'CAD');
    expect(cad).toMatchObject({ door: 999, pickup: 0 });
    expect(cad.doorRule).toMatchObject({ method: 'AFROTRANSACT_DELIVERY', source: 'RULE', ruleVersion: 1 });
    const own = effectiveFees({ doorFeeOverride: 499, pickupFeeOverride: 150 }, rules, 'USD');
    expect(own).toMatchObject({ door: 499, pickup: 150 });
    expect(own.doorRule).toMatchObject({ source: 'WINDOW_OVERRIDE', ruleVersion: 1 });
    expect(own.pickupRule).toMatchObject({ method: 'PICKUP', source: 'WINDOW_OVERRIDE' });
    expect(effectiveFees({ doorFeeOverride: null, pickupFeeOverride: null }, rules, 'GBP')).toMatchObject({ door: 0, doorRule: null, pickup: 0 });
  });

  it('validates a late add', () => {
    const d = { customerId: 'c0', addressId: 'a1', newAddress: null, phoneConfirmed: true, items: ['x'], payment: 'SAVED_CARD' as const, reason: 'CUSTOMER_CALLED' as const, note: '' };
    expect(lateAddProblems(d, 'CLOSED')).toEqual([]);
    expect(lateAddProblems({ ...d, addressId: '', newAddress: { line1: '1 Main', zip: '79401', placeId: null } }, 'OPEN')).toEqual(['verify']);
    expect(lateAddProblems({ ...d, customerId: '', phoneConfirmed: false, items: [] }, 'DELIVERED')).toEqual(['status', 'customer', 'phone', 'items']);
  });

  it('builds the saved-address snapshot delivery-runs needs with an addressId', () => {
    const c: LateAddCustomer = {
      id: 'c0', name: 'Ada Okafor', email: 'ada@example.com', phone: '+1 806 555 0101', city: 'Lubbock', region: 'TX', country: 'US',
      addresses: [{ id: 'a1', label: 'Home', line1: '2401 Broadway ', line2: '', city: 'Lubbock', region: 'TX', zip: '79401', instructions: 'Gate 4' }]
    };
    expect(lateAddAddressSnapshot(c, 'a1')).toEqual({
      fullName: 'Ada Okafor', phone: '+1 806 555 0101', line1: '2401 Broadway', city: 'Lubbock', region: 'TX', zip: '79401', country: 'US', instructions: 'Gate 4'
    });
    expect(lateAddAddressSnapshot(c, 'a9')).toBeUndefined();
    expect(lateAddAddressSnapshot(null, 'a1')).toBeUndefined();
  });

  it('keeps payment links within orders-service’s 1–24 h', () => {
    expect(LINK_EXPIRY_MAX_HOURS).toBe(24);
    expect(linkExpiryProblem(1)).toBeNull();
    expect(linkExpiryProblem(24)).toBeNull();
    expect(linkExpiryProblem(25)).toContain('at most 24 hours');
    expect(linkExpiryProblem(0)).toContain('at least 1 hour');
    expect(linkExpiryProblem(Number.NaN)).toBe('Enter whole hours');
  });

  it('counts {link} as the tracked SMS link notifications sends', () => {
    expect(preorderSmsLink('https://afrotransact.com/', 'LBK-7')).toBe('https://afrotransact.com/pre-orders?src=sms&po=LBK-7&cr=announce');
    const link = preorderSmsLink('https://afrotransact.com', 'LBK-7');
    const p = smsPreview('AfroTransact: Lubbock pre-order is open. {link} Reply STOP to opt out', 'https://afrotransact.com', 'LBK-7');
    expect(p.text).toBe(`AfroTransact: Lubbock pre-order is open. ${link} Reply STOP to opt out`);
    expect(p.linkLength).toBe(link.length);
    expect(p.length).toBe(p.text.length);
    expect(p).toMatchObject({ encoding: 'GSM-7', limit: 160, fits: true, usesLink: true });
    // The raw copy fits 160 but the expanded link pushes it over: the counter is honest.
    const long = `${'x'.repeat(150)} {link}`;
    expect(long.length).toBeLessThanOrEqual(160);
    expect(smsPreview(long, 'https://afrotransact.com', null).fits).toBe(false);
    // Typography is made SMS-safe like notifications does; emoji switch to UCS-2 (70).
    expect(smsPreview('Order by Thu · 6 PM – thanks…', 'https://a.b').text).toBe('Order by Thu at 6 PM - thanks...');
    expect(smsPreview('Fresh egusi 🍲', 'https://a.b')).toMatchObject({ encoding: 'UCS-2', limit: 70, usesLink: false, linkLength: 0 });
  });

  it('quotes CSV cells', () => expect(toCsvText([['a', 'b,c'], ['say "hi"', 1]])).toBe('a,"b,c"\n"say ""hi""",1'));

  it('keys hang off the staff family', () => expect(staffPreorderKeys.summary('lbk-7').slice(0, 2)).toEqual(['staff', 'preorders']));
});

describe('mock /v1/staff/preorders', () => {
  beforeEach(() => {
    resetStaffDb(new Date('2026-10-10T15:00:00Z'));
  });

  it('lists every status with tab counts', async () => {
    const list = await api().list({ status: 'all', page: 0 });
    const by = Object.fromEntries(list.items.map((r) => [r.id, r]));
    expect(by['lbk-7']?.status).toBe('OPEN');
    expect(by['ama-4']?.status).toBe('CLOSED');
    expect(by['elp-3']?.status).toBe('UPCOMING');
    expect(by['mid-2']?.status).toBe('DRAFT');
    expect(by['lbk-6']?.status).toBe('DELIVERED');
    expect(by['ama-5']?.status).toBe('PAUSED');
    expect(by['lrd-1']?.areaOff).toBe(true);
    expect(by['ode-1']?.status).toBe('AWAITING_DECISION');
    expect(list.counts).toEqual({ all: 9, open: 1, upcoming: 3, decision: 1, closed: 2, draft: 1 });
    expect(list.defaultMinOrders).toBe(10);
    expect((await api().list({ status: 'closed', page: 0 })).items.map((r) => r.id).sort()).toEqual(['ama-4', 'lbk-6']);
  });

  it('generates orders deterministically and summarises them', async () => {
    const s1 = await api().summary('lbk-7');
    resetStaffDb(new Date('2026-10-10T15:00:00Z'));
    const s2 = await api().summary('lbk-7');
    expect(s1.kpis).toEqual(s2.kpis);
    expect(s1.ordersTotal).toBe(142);
    expect(s1.kpis.preorders + s1.kpis.cancelled).toBe(142);
    expect(s1.byDay.some((d) => d.afterClose)).toBe(false);
    const closed = await api().summary('ama-4');
    expect(closed.kpis.lateAdds).toBe(3);
    const page = await api().orders('lbk-7', { status: 'CANCELLED', page: 0, size: 12 });
    expect(page.items.every((o) => o.status === 'CANCELLED')).toBe(true);
  });

  it('saves with If-Match, refuses stale versions and blocks publishing until the checks pass', async () => {
    const w = await api().create({ ...inputOf(await api().get('elp-3')), name: 'Houston Pre-order #1', targets: ['US/TX/Houston'] });
    expect(w.status).toBe('DRAFT');
    expect(w.code).toBe('HOU-1');
    const saved = await api().update(w.id, { ...inputOf(w), ribbon: 'Houston pre-order' }, w.version);
    await expect(api().update(w.id, inputOf(w), w.version)).rejects.toMatchObject({ status: 412 });
    const err = await api().publish(w.id).catch((e: unknown) => e);
    expect(isApiError(err) && err.code === 'PREORDER_CHECKS_FAILED' && Object.keys(err.fieldErrors)).toEqual(['hero']);
    const ticket = await api().uploadCreative(saved.id, 'HERO', new Blob(['img'], { type: 'image/png' }), { width: 1920, height: 640 });
    expect(ticket.creatives.map((c) => c.slot)).toEqual(['HERO']);
    expect((await api().publish(w.id)).status).toBe('UPCOMING');
    const areas = await api().areas({ country: 'US', region: 'TX', page: 0 });
    expect(areas.rows.some((r) => r.key === 'US/TX/Houston' && r.on === 1)).toBe(true);
  });

  it('gates writes by role', async () => {
    await expect(api('platform.customer_support').pause('lbk-7')).rejects.toMatchObject({ status: 403 });
    await expect(api('platform.finance').downloadCsv('lbk-7', 'picking-list')).resolves.toMatchObject({ fileName: 'lbk-7-picking-list.csv' });
    await expect(api('platform.customer_support').downloadCsv('lbk-7', 'orders')).rejects.toMatchObject({ status: 403 });
    const settings = await api('platform.operations_manager').getSettings();
    await expect(api('platform.operations_manager').updateSettings({ ...settings, enabled: false }, settings.version)).rejects.toMatchObject({ status: 403 });
    const long = { ...settings, payment: { ...settings.payment, lateAdd: { ...settings.payment.lateAdd, linkExpiryHours: 48 } } };
    await expect(api().updateSettings(long, settings.version)).rejects.toMatchObject({ status: 422 });
    await expect(api('platform.trust_safety').list({ status: 'all', page: 0 })).rejects.toMatchObject({ status: 403 });
  });

  it('adds a late order as customer service with the effective door fee', async () => {
    const cs = api('platform.customer_support');
    const customers = await cs.customers('', 'ama-4');
    const c = customers.items[0];
    if (!c) throw new Error('no customers');
    const products = await cs.products('ama-4');
    const o = await cs.addLateOrder('ama-4', { customerId: c.id, addressId: 'a1', phoneConfirmed: true, items: [{ atin: products[0]?.atin ?? '', qty: 1 }], payment: 'PAYMENT_LINK', reason: 'LIVE_CHAT', note: 'Gate 2' }, 'late-dialog-key-0001');
    expect(o).toMatchObject({ status: 'LATE_ADD', channel: 'AGENT_LATE', total: (products[0]?.price ?? 0) + 799 });
    expect(o.note).toContain('Live chat · pay-now link sent by SMS');
    // A retry with the same key (the answer was lost) is the same order; the order is found by the status lookup too.
    const again = await cs.addLateOrder('ama-4', { customerId: c.id, addressId: 'a1', phoneConfirmed: true, items: [{ atin: products[0]?.atin ?? '', qty: 1 }], payment: 'PAYMENT_LINK', reason: 'LIVE_CHAT', note: 'Gate 2' }, 'late-dialog-key-0001');
    expect(again.orderId).toBe(o.orderId);
    expect((await cs.findLateAdd('ama-4', o.phone, [products[0]?.atin ?? ''], o.placedAt))?.orderId).toBe(o.orderId);
    expect(await cs.findLateAdd('ama-4', o.phone, ['OTHER-ATIN'], o.placedAt)).toBeNull();
    await expect(cs.addLateOrder('ama-4', { customerId: c.id, addressId: 'a1', phoneConfirmed: true, items: [{ atin: products[0]?.atin ?? '', qty: 1 }], payment: 'PAYMENT_LINK', reason: 'LIVE_CHAT', note: '' }, '')).rejects.toMatchObject({ status: 422 });
    const snap = await cs.addLateOrder('ama-4', { customerId: c.id, addressId: 'a1', address: { line1: '9 Override Rd' }, phoneConfirmed: true, items: [{ atin: products[0]?.atin ?? '', qty: 1 }], payment: 'SAVED_CARD', reason: 'CUSTOMER_CALLED', note: '' }, 'late-dialog-key-0002');
    expect(snap).toMatchObject({ addressLine: '9 Override Rd', zip: c.addresses[0]?.zip, city: c.addresses[0]?.city });
    await expect(cs.addLateOrder('ama-4', { customerId: c.id, addressId: 'zz', phoneConfirmed: true, items: [{ atin: products[0]?.atin ?? '', qty: 1 }], payment: 'SAVED_CARD', reason: 'CUSTOMER_CALLED', note: '' }, 'late-dialog-key-0003')).rejects.toMatchObject({ status: 422 });
    await expect(cs.addLateOrder('lbk-6', { customerId: c.id, addressId: 'a1', phoneConfirmed: true, items: [{ atin: products[0]?.atin ?? '', qty: 1 }], payment: 'SAVED_CARD', reason: 'GOODWILL', note: '' }, 'late-dialog-key-0004')).rejects.toMatchObject({ status: 409 });
  });

  it('drills coverage areas down and switches a country off in bulk', async () => {
    const root = await api().areas({ page: 0 });
    expect(root.level).toBe('COUNTRY');
    expect(root.rows.map((r) => r.name)).toEqual(['Canada', 'United States']);
    await api().setAreas(['US'], false);
    const us = await api().areas({ country: 'US', page: 0 });
    expect(us.rows[0]).toMatchObject({ name: 'Texas', on: 0 });
    expect((await api().get('lbk-7')).areaOff).toBe(true);
    const places = await api().places({ country: 'US', region: 'TX' });
    expect(places.items.map((p) => p.name)).toContain('Lubbock');
  });
});

describe('minimum order count and decisions (ADR 0005 §4)', () => {
  const ops = 'platform.operations_manager';

  beforeEach(() => {
    resetStaffDb(new Date('2026-10-10T15:00:00Z'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('lets the outcome override the dates and files the window under the right tab', () => {
    const w = { draft: false, active: true, opensAt: '2026-10-06T14:00:00Z', closesAt: '2026-10-23T04:59:00Z', deliversOn: '2026-10-25' };
    const closed = Date.parse('2026-10-24T00:00:00Z');
    expect(preorderStatus({ ...w, outcome: 'AWAITING_DECISION' }, closed)).toBe('AWAITING_DECISION');
    expect(preorderStatus({ ...w, outcome: 'AWAITING_DECISION' }, Date.parse('2026-12-01T00:00:00Z'))).toBe('AWAITING_DECISION');
    expect(preorderStatus({ ...w, outcome: 'PROCEEDED' }, closed)).toBe('CLOSED');
    expect(preorderStatus({ ...w, outcome: 'CANCELLED', active: false }, 0)).toBe('CANCELLED');
    expect(tabOf('AWAITING_DECISION')).toBe('decision');
    expect(tabOf('CANCELLED')).toBe('closed');
    expect(decisionReasonProblem('  ')).toBe('required');
    expect(decisionReasonProblem('x'.repeat(501))).toBe('tooLong');
    expect(decisionReasonProblem('Not enough orders')).toBeNull();
  });

  it('shows the minimum, the orders so far and when staff may close early', async () => {
    const lbk = await api().get('lbk-7');
    expect(lbk).toMatchObject({ status: 'OPEN', minOrders: 100, minimumMet: true, canCloseEarly: true, decisionNeeded: false, outcome: null });
    const abl = await api().get('ode-1');
    expect(abl).toMatchObject({ status: 'AWAITING_DECISION', minOrders: 10, minimumMet: false, canCloseEarly: false, decisionNeeded: true, outcome: 'AWAITING_DECISION' });
    expect(abl.orderCount).toBeLessThan(10);
    const row = (await api().list({ status: 'decision', page: 0 })).items;
    expect(row.map((r) => r.id)).toEqual(['ode-1']);
    expect(row[0]).toMatchObject({ minOrders: 10, decisionNeeded: true });
  });

  it('takes the default minimum for a new window, keeps it on an edit and saves one that is set', async () => {
    const base = inputOf(await api().get('elp-3'));
    const created = await api().create({ ...base, name: 'Houston Pre-order #1', targets: ['US/TX/Houston'] });
    expect(created.minOrders).toBe(10);
    const edited = await api().update(created.id, { ...inputOf(created), ribbon: 'Houston pre-order' }, created.version);
    expect(edited.minOrders).toBe(10);
    const raised = await api().update(created.id, { ...inputOf(edited), minOrders: 25 }, edited.version);
    expect(raised.minOrders).toBe(25);
    const none = await api().create({ ...base, name: 'Dallas Pre-order #1', targets: ['US/TX/Dallas'], minOrders: 0 });
    expect(none.minOrders).toBe(0);
  });

  it('closes an open window early only once its minimum is met, with a reason', async () => {
    await expect(api(ops).closeEarly('lbk-7', ' ')).rejects.toMatchObject({ status: 422 });
    const closed = await api(ops).closeEarly('lbk-7', 'Both seller lots are gone');
    expect(closed.status).toBe('CLOSED');
    expect((await api(ops).decisions('lbk-7')).map((d) => [d.kind, d.reason])).toEqual([['EARLY_CLOSE', 'Both seller lots are gone']]);
    await expect(api(ops).closeEarly('lbk-7', 'again')).rejects.toMatchObject({ status: 409 });
    await expect(api(ops).closeEarly('elp-3', 'not yet')).rejects.toMatchObject({ status: 409 });
  });

  it('proceeds below the minimum: needs a reason, is logged with who and when, and cannot be decided twice', async () => {
    const err = await api(ops).decide('ode-1', 'PROCEED', '').catch((e: unknown) => e);
    expect(isApiError(err) && err.status === 422 && Object.keys(err.fieldErrors)).toEqual(['reason']);
    const done = await api(ops).decide('ode-1', 'PROCEED', 'Two orders still cover the drive');
    expect(done).toMatchObject({ outcome: 'PROCEEDED', status: 'CLOSED', decisionNeeded: false });
    const log = await api(ops).decisions('ode-1');
    expect(log.map((d) => d.kind)).toEqual(['AWAITING', 'PROCEED']);
    expect(log[0]).toMatchObject({ actor: 'system', minimum: 10 });
    expect(log[1]).toMatchObject({ reason: 'Two orders still cover the drive', minimum: 10 });
    expect(log[1]?.actor).not.toBe('system');
    expect(Date.parse(log[1]?.at ?? '')).not.toBeNaN();
    await expect(api(ops).decide('ode-1', 'CANCEL', 'changed my mind')).rejects.toMatchObject({ status: 409 });
  });

  it('cancels, refunds every order and shows each order’s refund progress until it settles', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-10T15:00:00Z'));
    const before = await api().orders('ode-1', { page: 0, size: 50 });
    const counted = before.items.filter((o) => o.status !== 'CANCELLED').length;
    expect(counted).toBeGreaterThan(0);
    const cancelled = await api(ops).decide('ode-1', 'CANCEL', 'Not enough orders to make the trip');
    expect(cancelled).toMatchObject({ status: 'CANCELLED', outcome: 'CANCELLED', orderCount: 0 });

    const refunding = (await api().orders('ode-1', { page: 0, size: 50 })).items.filter((o) => o.refundStatus);
    expect(refunding).toHaveLength(counted);
    expect(refunding.every((o) => o.refundStatus === 'PENDING' && o.status === 'CANCELLED')).toBe(true);
    expect(await api().refunds('ode-1')).toMatchObject({ total: counted, pending: counted, done: false });

    vi.setSystemTime(new Date('2026-10-10T15:00:10Z'));
    const settled = (await api().orders('ode-1', { page: 0, size: 50 })).items.filter((o) => o.refundStatus);
    expect(settled.every((o) => o.refundStatus === 'REFUNDED' || o.refundStatus === 'VOIDED')).toBe(true);
    expect(settled.every((o) => o.refundAmount === o.total)).toBe(true);
    const summary = await api().refunds('ode-1');
    expect(summary).toMatchObject({ total: counted, pending: 0, failed: 0, done: true });
    expect(summary.refunded + summary.voided).toBe(counted);
    expect((await api(ops).decisions('ode-1')).map((d) => d.kind)).toEqual(['AWAITING', 'CANCEL']);
    await expect(api(ops).cancel('ode-1', 'again')).rejects.toMatchObject({ status: 409 });
    await expect(api(ops).update('ode-1', inputOf(cancelled), cancelled.version)).rejects.toMatchObject({ status: 422 });
  });

  it('cancels an open window with a reason, and only managers may decide', async () => {
    await expect(api(ops).cancel('lbk-7', '')).rejects.toMatchObject({ status: 422 });
    expect(await api(ops).cancel('lbk-7', 'Seller stock fell through')).toMatchObject({ status: 'CANCELLED' });
    await expect(api(ops).cancel('lbk-6', 'delivered long ago')).rejects.toMatchObject({ status: 409 });
    for (const role of ['platform.customer_support', 'platform.finance']) {
      await expect(api(role).decide('ode-1', 'CANCEL', 'x')).rejects.toMatchObject({ status: 403 });
      await expect(api(role).cancel('ama-4', 'x')).rejects.toMatchObject({ status: 403 });
      await expect(api(role).closeEarly('lbk-7', 'x')).rejects.toMatchObject({ status: 403 });
    }
    await expect(api('platform.finance').decisions('ode-1')).resolves.toHaveLength(1);
  });

  it('keeps the new routes in the query-key family so a decision refreshes the screen', () => {
    expect(staffPreorderKeys.decisions('ode-1')).toEqual(['staff', 'preorders', 'window', 'ode-1', 'decisions']);
    expect(staffPreorderKeys.refunds('ode-1')[0]).toBe('staff');
  });
});
