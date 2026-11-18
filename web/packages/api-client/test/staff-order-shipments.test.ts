import { describe, expect, it } from 'vitest';

import { createStaffApiClient, isApiError, type RecordShipment } from '../src';
import { MOCK_STAFF_ROLE_COOKIE } from '../src/mocks/staff';
import { handleStaffMockRequest } from '../src/mocks/staff-runtime';

const as = (role: string) =>
  createStaffApiClient({
    baseUrl: 'http://staff.test/api/bff',
    headers: { cookie: `${MOCK_STAFF_ROLE_COOKIE}=${role}`, origin: 'http://staff.test' },
    fetch: (req) => handleStaffMockRequest(req)
  });

/** The mock order with two packages: AfroTransact (ready) and Patel Grocers (packed). */
const ORDER = 'AT-200400';
const byTo = (s: RecordShipment, to: string) => s.transitions.find((t) => t.to === to);

describe('staff order sheet (mock BFF, same shape as the Go one)', () => {
  it('lists each package with its seller, items and the statuses that may be set', async () => {
    const sheet = await as('platform.super_admin').getRecord('orders', ORDER);
    const [first, second] = sheet.shipments ?? [];
    expect(first?.sellerName).toBe('AfroTransact');
    expect(second?.sellerName).toBe('Patel Grocers');
    expect(first?.status).toBe('READY');
    expect(second?.status).toBe('PACKED');
    expect(first?.itemTitles).toContain('Jollof seasoning 200 g');
    expect(byTo(first as RecordShipment, 'PICKED_UP')).toMatchObject({ allowed: true, noteRequired: false });
    expect(byTo(first as RecordShipment, 'CANCELLED')).toMatchObject({ allowed: true, noteRequired: true });
  });

  it('lists what cannot be done with the reason instead of leaving it out', async () => {
    const sheet = await as('platform.super_admin').getRecord('orders', ORDER);
    const [first, patel] = sheet.shipments as [RecordShipment, RecordShipment];
    expect(byTo(patel, 'READY')).toMatchObject({ allowed: false, blocked: 'SELLER_UPDATES', reason: 'Updated by the seller: use Override status' });
    expect(byTo(first, 'DELIVERED')).toMatchObject({ allowed: false, blocked: 'SEQUENCE', reason: 'Earlier steps come first' });
    expect(byTo(patel, 'DELIVERED')).toMatchObject({ allowed: false, blocked: 'SELLER_UPDATES' });
    expect(byTo(patel, 'DELIVERY_FAILED')).toMatchObject({ allowed: false, blocked: 'SELLER_UPDATES' });
  });

  it('turns every status off for a role without the permission', async () => {
    const sheet = await as('platform.customer_support').getRecord('orders', ORDER);
    const all = (sheet.shipments ?? []).flatMap((s) => s.transitions);
    expect(all.length).toBeGreaterThan(0);
    expect(all.every((t) => !t.allowed)).toBe(true);
    // The reason is accurate: the delivery steps need the delivery permission, the seller's shipment is the seller's, the rest needs the order permission.
    const [fba, patel] = sheet.shipments as [RecordShipment, RecordShipment];
    expect(byTo(fba, 'PICKED_UP')).toMatchObject({ blocked: 'DELIVERY_PERMISSION', reason: 'You need the delivery permission', via: 'delivery' });
    expect(byTo(fba, 'CANCELLED')).toMatchObject({ blocked: 'PERMISSION', via: 'orders' });
    expect(byTo(patel, 'DELIVERED')).toMatchObject({ blocked: 'SELLER_UPDATES' });
  });

  it('names the mode of every package: fulfilled by AfroTransact, seller pickup, or an old seller-shipped one (history)', async () => {
    const api = as('platform.super_admin');
    expect(((await api.getRecord('orders', ORDER)).shipments ?? []).map((s) => s.mode)).toEqual(['fulfilled_by_afrotransact', 'legacy_seller_shipped']);
    expect(((await api.getRecord('orders', 'AT-200435')).shipments ?? []).map((s) => s.mode)).toEqual(['afrotransact_delivery']);
    expect(((await api.getRecord('orders', 'AT-200421')).shipments ?? []).map((s) => s.mode)).toEqual(['pickup']);
    expect(((await api.getRecord('orders', 'AT-200407')).shipments ?? []).map((s) => s.mode)).toEqual(['fulfilled_by_afrotransact']);
  });

  it('offers a shipment AfroTransact delivers the same steps as the Delivery queue, one at a time, never skipping', async () => {
    const api = as('platform.super_admin');
    const sheet = await api.getRecord('orders', ORDER);
    const fba = (sheet.shipments ?? [])[0] as RecordShipment;
    expect(fba.status).toBe('READY');
    expect(byTo(fba, 'PICKED_UP')).toMatchObject({ allowed: true, via: 'delivery', noteRequired: false });
    expect(byTo(fba, 'OUT_FOR_DELIVERY')).toMatchObject({ allowed: false, blocked: 'SEQUENCE' });
    expect(byTo(fba, 'DELIVERED')).toMatchObject({ allowed: false, blocked: 'SEQUENCE', proofRequired: true, noteRequired: false });
    // The generic status action refuses the delivery steps: they carry the proof and the audit.
    const refused = await api.runAction('orders', ORDER, 'shipment-status', { step: `${fba.id}|PICKED_UP` }, 'k-desk').catch((e: unknown) => e);
    expect(isApiError(refused) && refused.fieldErrors.step).toBeTruthy();
    // The delivery steps move it, and the order sheet shows the result with who did it.
    const moved = await api.markDeliveryPickedUp(fba.id, { note: 'Collected from the seller', expectedStatus: 'ready' });
    expect(moved.row.status).toBe('picked_up');
    const after = ((await api.getRecord('orders', ORDER)).shipments ?? [])[0] as RecordShipment;
    expect(after.status).toBe('PICKED_UP');
    expect(after.history[0]).toMatchObject({ to: 'PICKED_UP', from: 'READY', note: 'Collected from the seller' });
    expect(byTo(after, 'IN_TRANSIT')).toMatchObject({ allowed: true, via: 'delivery' });
    expect(byTo(after, 'PICKED_UP')).toBeUndefined();
  });

  it('offers a pickup order Ready, Collected and Not collected', async () => {
    const api = as('platform.super_admin');
    const pickup = ((await api.getRecord('orders', 'AT-200421')).shipments ?? [])[0] as RecordShipment;
    expect(byTo(pickup, 'DELIVERED')).toMatchObject({ allowed: true, via: 'orders' });
    expect(byTo(pickup, 'RETURNED')).toMatchObject({ allowed: true, noteRequired: true });
    expect(byTo(pickup, 'OUT_FOR_DELIVERY')).toMatchObject({ allowed: false, blocked: 'PICKUP_ORDER', reason: 'Not available for pickup orders' });
  });

  it('offers the override on an old seller-shipped order only to its holders, with a reason, forward only', async () => {
    const owner = as('platform.super_admin');
    const patel = ((await owner.getRecord('orders', ORDER)).shipments ?? [])[1] as RecordShipment;
    expect(patel.mode).toBe('legacy_seller_shipped');
    expect(patel.canOverride).toBe(true);
    expect(patel.overrideSteps).toContain('PICKED_UP');
    const noReason = await owner.runAction('orders', ORDER, 'shipment-override', { step: `${patel.id}|PICKED_UP` }, 'k-o1').catch((e: unknown) => e);
    expect(isApiError(noReason) && noReason.fieldErrors.reason).toBeTruthy();
    const done = await owner.runAction('orders', ORDER, 'shipment-override', { step: `${patel.id}|PICKED_UP`, reason: 'Seller phoned: it left this morning' }, 'k-o2');
    const moved = (done.shipments ?? [])[1] as RecordShipment;
    expect(moved.status).toBe('PICKED_UP');
    expect(moved.history[0]).toMatchObject({ to: 'PICKED_UP', note: 'Seller phoned: it left this morning' });
    // Never backwards.
    const back = await owner.runAction('orders', ORDER, 'shipment-override', { step: `${patel.id}|PACKED`, reason: 'Wrong click' }, 'k-o3').catch((e: unknown) => e);
    expect(isApiError(back) && back.fieldErrors.step).toBeTruthy();
    // Operations managers do not hold the override permission: nothing is offered and the action is refused.
    const ops = as('platform.operations_manager');
    const theirs = ((await ops.getRecord('orders', 'AT-200400')).shipments ?? [])[1] as RecordShipment;
    expect(theirs.canOverride).toBe(false);
    expect(theirs.overrideSteps).toEqual([]);
    const refused = await ops.runAction('orders', 'AT-200400', 'shipment-override', { step: `${patel.id}|DELIVERED`, reason: 'Trying' }, 'k-o4').catch((e: unknown) => e);
    expect(isApiError(refused) && refused.status).toBe(403);
  });

  it('groups the facts, hides empty ones and keeps ids under technical details', async () => {
    const sheet = await as('platform.super_admin').getRecord('orders', ORDER);
    const titles = sheet.sections.map((s) => s.title);
    expect(titles.slice(0, 3)).toEqual(['Order', 'Customer', 'Payment']);
    expect(titles.at(-1)).toBe('Technical details');
    for (const s of sheet.sections) {
      expect(s.fields.every((f) => f.value.trim() !== '' && f.value !== '—')).toBe(true);
      if (s.title !== 'Technical details') expect(s.fields.some((f) => f.kind === 'mono')).toBe(false);
    }
  });

  it('sets a status, refuses an exceptional step without a note, and records who did it', async () => {
    const api = as('platform.super_admin');
    const step = (to: string, values: Record<string, string> = {}) => api.runAction('orders', 'AT-200407', 'shipment-status', { step: `shp-1-1|${to}`, ...values }, `key-${to}`);
    const before = await api.getRecord('orders', 'AT-200407');
    const shipment = (before.shipments ?? [])[0] as RecordShipment;
    const next = shipment.transitions.find((t) => t.allowed && !t.noteRequired);
    const exceptional = shipment.transitions.find((t) => t.allowed && t.noteRequired);
    expect(next && exceptional).toBeTruthy();
    const rejected = await step(exceptional?.to ?? '').catch((e: unknown) => e);
    expect(isApiError(rejected) && rejected.fieldErrors.reason).toMatch(/Say why/);
    const after = await step(next?.to ?? '', { reason: 'Boxed and labelled' });
    const moved = (after.shipments ?? [])[0] as RecordShipment;
    expect(moved.status).toBe(next?.to);
    expect(moved.history[0]).toMatchObject({ to: next?.to, from: shipment.status, note: 'Boxed and labelled' });
  });

  it('refuses a status that is not on offer', async () => {
    const err = await as('platform.super_admin')
      .runAction('orders', 'AT-200414', 'shipment-status', { step: 'shp-2-1|DELIVERED' }, 'k1')
      .catch((e: unknown) => e);
    expect(isApiError(err) && err.fieldErrors.step).toBeTruthy();
  });
});
