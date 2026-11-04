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
    expect(byTo(patel, 'READY')).toMatchObject({ allowed: false, blocked: 'SELLER', reason: 'Only the seller does this step' });
    expect(byTo(first, 'DELIVERED')).toMatchObject({ allowed: false, blocked: 'SEQUENCE', reason: 'Earlier steps come first' });
    expect(byTo(patel, 'DELIVERED')).toMatchObject({ allowed: false, blocked: 'STATE' });
    expect(byTo(patel, 'DELIVERY_FAILED')).toMatchObject({ allowed: false, blocked: 'STATE' });
  });

  it('turns every status off for a role without the permission', async () => {
    const sheet = await as('platform.customer_support').getRecord('orders', ORDER);
    const all = (sheet.shipments ?? []).flatMap((s) => s.transitions);
    expect(all.length).toBeGreaterThan(0);
    expect(all.every((t) => !t.allowed && t.blocked === 'PERMISSION')).toBe(true);
  });

  it('groups the facts, hides empty ones and keeps ids under technical details', async () => {
    const sheet = await as('platform.super_admin').getRecord('orders', ORDER);
    const titles = sheet.sections.map((s) => s.title);
    expect(titles.slice(0, 2)).toEqual(['Customer', 'Payment']);
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
      .runAction('orders', ORDER, 'shipment-status', { step: 'shp-0-2|READY' }, 'k1')
      .catch((e: unknown) => e);
    expect(isApiError(err) && err.fieldErrors.step).toBeTruthy();
  });
});
