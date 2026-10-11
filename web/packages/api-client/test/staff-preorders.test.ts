import { beforeEach, describe, expect, it } from 'vitest';

import { createStaffApiClient, isApiError, staffPreorderKeys } from '../src';
import { MOCK_STAFF_ROLE_COOKIE, resetStaffDb } from '../src/mocks/staff';
import { handleStaffMockRequest } from '../src/mocks/staff-runtime';
import {
  areaFromKey,
  areaOn,
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

  it('uses per-window fee overrides before the platform default', () => {
    const payment = { doorFee: { USD: 799, CAD: 999 }, pickupFee: { USD: 0 }, pickupWindow: '', allowedMethods: [], lateAdd: { savedCard: true, paymentLink: true, linkExpiryHours: 24 }, missedDelivery: { offerRefund: true, offerNewDate: true } } as const;
    const p = { ...payment, allowedMethods: [...payment.allowedMethods] };
    expect(effectiveFees({ doorFeeOverride: null, pickupFeeOverride: null }, p, 'CAD')).toEqual({ door: 999, pickup: 0 });
    expect(effectiveFees({ doorFeeOverride: 499, pickupFeeOverride: 150 }, p, 'USD')).toEqual({ door: 499, pickup: 150 });
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
    expect(list.counts).toEqual({ all: 8, open: 1, upcoming: 3, closed: 2, draft: 1 });
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
    const o = await cs.addLateOrder('ama-4', { customerId: c.id, addressId: 'a1', phoneConfirmed: true, items: [{ atin: products[0]?.atin ?? '', qty: 1 }], payment: 'PAYMENT_LINK', reason: 'LIVE_CHAT', note: 'Gate 2' });
    expect(o).toMatchObject({ status: 'LATE_ADD', channel: 'AGENT_LATE', total: (products[0]?.price ?? 0) + 799 });
    expect(o.note).toContain('Live chat · payment link sent by SMS');
    const snap = await cs.addLateOrder('ama-4', { customerId: c.id, addressId: 'a1', address: { line1: '9 Override Rd' }, phoneConfirmed: true, items: [{ atin: products[0]?.atin ?? '', qty: 1 }], payment: 'SAVED_CARD', reason: 'CUSTOMER_CALLED', note: '' });
    expect(snap).toMatchObject({ addressLine: '9 Override Rd', zip: c.addresses[0]?.zip, city: c.addresses[0]?.city });
    await expect(cs.addLateOrder('ama-4', { customerId: c.id, addressId: 'zz', phoneConfirmed: true, items: [{ atin: products[0]?.atin ?? '', qty: 1 }], payment: 'SAVED_CARD', reason: 'CUSTOMER_CALLED', note: '' })).rejects.toMatchObject({ status: 422 });
    await expect(cs.addLateOrder('lbk-6', { customerId: c.id, addressId: 'a1', phoneConfirmed: true, items: [{ atin: products[0]?.atin ?? '', qty: 1 }], payment: 'SAVED_CARD', reason: 'GOODWILL', note: '' })).rejects.toMatchObject({ status: 409 });
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
