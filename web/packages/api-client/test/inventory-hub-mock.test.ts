import { beforeEach, describe, expect, it } from 'vitest';

import { createStaffApiClient } from '../src';
import { resetHubDb } from '../src/mocks/inventory-hub';
import { handleStaffMockRequest } from '../src/mocks/staff-runtime';

/* The mock BFF of the Inventory hub (S281) applies the services' rules: a received inbound line becomes a lot with
   the packing-list batch, and a cycle-count variance is posted as an ADJUST of the lot. */
const api = createStaffApiClient({ baseUrl: 'http://staff.test/api/bff', fetch: (req) => handleStaffMockRequest(req) }).inventory;

describe('inventory hub mock', () => {
  beforeEach(() => {
    resetHubDb(new Date('2026-10-11T12:00:00Z'));
  });

  it('creates an inbound plan, ships it, receives it and the lot appears with the announced batch', async () => {
    const plan = await api.createPlan({ nodeId: 'HOU-FC', lines: [{ atin: 'ATJOLLOF01', sku: 'AT-JOL-200061', qty: 48, casePack: 24, lotNo: 'jol-2611', mfgDate: '2026-09-20', expDate: '2028-03-01' }] });
    expect(plan.status).toBe('WORKING');
    expect(plan.lines[0]?.cases).toBe(2);
    await expect(api.createPlan({ lines: [{ atin: 'ATJOLLOF01', qty: 50, casePack: 24 }] })).rejects.toMatchObject({ status: 422 });
    const shipped = await api.ship(plan.id, plan.version);
    expect(shipped.status).toBe('SHIPPED');
    const res = await api.receive(plan.id, [{ sku: 'AT-JOL-200061', qtyGood: 46, qtyDamaged: 2 }], 'rcv-1');
    expect(res.lots.map((l) => [l.lotNo, l.status, l.qtyOnHand])).toEqual([['JOL-2611', 'SELLABLE', 46], ['JOL-2611-DMG', 'DAMAGED', 2]]);
    expect(res.lots[0]?.expDate).toBe('2028-03-01');
    const closed = await api.close(plan.id);
    expect(closed.status).toBe('CLOSED');
    const lots = await api.lots({ sku: 'AT-JOL-200061', page: 0, size: 50 });
    expect(lots.items.some((l) => l.lotNo === 'JOL-2611')).toBe(true);
  });

  it('posts a cycle count variance as an adjustment with its reason', async () => {
    const count = await api.startCount({ nodeId: 'HOU-FC', sku: 'AT-JOL-200061' });
    const line = count.lines.find((l) => l.lotNo === 'JOL-2607');
    expect(line?.expectedQty).toBe(120);
    const counted = await api.recordCounts(count.id, count.lines.map((l) => ({ lineNo: l.lineNo, countedQty: l.lotNo === 'JOL-2607' ? 117 : l.expectedQty })), count.version);
    expect(counted.status).toBe('COUNTED');
    expect(counted.netVariance).toBe(-3);
    await expect(api.postCount(count.id, counted.version)).rejects.toMatchObject({ status: 422 });
    const reasoned = await api.recordCounts(count.id, [{ lineNo: line?.lineNo ?? 0, countedQty: 117, reasonCode: 'LOST' }], counted.version);
    const posted = await api.postCount(count.id, reasoned.version);
    expect(posted.status).toBe('POSTED');
    const lot = await api.lot(line?.lotId ?? '');
    expect(lot.qtyOnHand).toBe(117);
    const moves = await api.movements({ lotId: lot.id, type: 'ADJUST', page: 0, size: 10 });
    expect(moves.items[0]?.reason).toMatch(/lost/);
  });

  it('lists the item on the storefront and the trace follows it to the product page', async () => {
    const before = await api.trace({ atin: 'ATJOLLOF01' });
    expect(before.steps.find((s) => s.key === 'offer')?.status).toBe('DONE');
    expect(before.badges).toContain('FULFILLED_BY_AFROTRANSACT');
    const items = await api.retail.items({ q: 'okra', page: 0, size: 10 });
    const okra = items.items[0];
    await expect(api.retail.setListing(okra?.id ?? '', 'LIVE', okra?.version)).rejects.toMatchObject({ status: 422 });
    const linked = await api.retail.linkAtin(okra?.id ?? '', 'ATOKRA0001', okra?.version);
    const broken = await api.trace({ sku: 'AT-OKR-200085' });
    expect(broken.steps.find((s) => s.key === 'offer')?.status).toBe('BROKEN');
    await api.retail.setListing(linked.id, 'LIVE', linked.version);
    const listed = await api.trace({ sku: 'AT-OKR-200085' });
    expect(listed.steps.find((s) => s.key === 'offer')?.status).toBe('DONE');
  });
});
