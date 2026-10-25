import { beforeEach, describe, expect, it } from 'vitest';

import { createStaffApiClient, isApiError, staffRunKeys } from '../src';
import { MOCK_STAFF_ROLE_COOKIE, resetStaffDb } from '../src/mocks/staff';
import { handleStaffMockRequest } from '../src/mocks/staff-runtime';
import {
  advertorialActions,
  advertorialInputProblems,
  economicsInputProblems,
  runBreakEven,
  runCostModel,
  runSnapshot
} from '../src/staff-runs';

const api = (roles?: string) =>
  createStaffApiClient({
    baseUrl: 'http://staff.test/api/bff',
    headers: roles ? { cookie: `${MOCK_STAFF_ROLE_COOKIE}=${roles}` } : {},
    fetch: (req) => handleStaffMockRequest(req)
  });
const runs = (roles?: string) => api(roles).runs;

const NUMBERS = { driverCostPerHourMinor: 2500, driverCostPerMileMinor: 70, collectionMinutes: 45, perStopMinutes: 6.5, pickupPointStopMinutes: 30, handoverMinutesPerOrder: 1, roundTrip: true };

beforeEach(() => resetStaffDb(new Date('2026-10-10T15:00:00Z')));

describe('the run cost calculation', () => {
  it('costs a doorstep run as fixed drive and sweep plus a stop per order', () => {
    const m = runCostModel(NUMBERS, { roadMiles: 148, driveMinutes: 165 }, 'DOORSTEP');
    expect(m).toMatchObject({ driveMiles: 296, fixedMinutes: 375, perOrderMinutes: 6.5 });
    expect(m.fixedCostMinor).toBeCloseTo((375 * 2500) / 60 + 296 * 70, 6);
    expect(m.perOrderCostMinor).toBeCloseTo((6.5 * 2500) / 60, 6);
  });

  it('costs a pickup point as one stop plus a handover per order, and a one-way trip as one leg', () => {
    const m = runCostModel({ ...NUMBERS, roundTrip: false }, { roadMiles: 100, driveMinutes: 120 }, 'PICKUP_POINT');
    expect(m).toMatchObject({ driveMiles: 100, fixedMinutes: 195, perOrderMinutes: 1 });
  });

  it('finds the fewest orders whose fees cover the cost, and none when a stop costs more than the fee', () => {
    const m = runCostModel(NUMBERS, { roadMiles: 148, driveMinutes: 165 }, 'DOORSTEP');
    const n = runBreakEven(799, m);
    expect(n).not.toBeNull();
    expect((n as number) * 799).toBeGreaterThanOrEqual(m.fixedCostMinor + (n as number) * m.perOrderCostMinor);
    expect(((n as number) - 1) * 799).toBeLessThan(m.fixedCostMinor + ((n as number) - 1) * m.perOrderCostMinor);
    expect(runBreakEven(100, m)).toBeNull();
    const at = runSnapshot(12, 799, 500, m);
    expect(at).toMatchObject({ orders: 12, feeRevenueMinor: 9588, revenueMinor: 10088, commissionShareMinor: 500 });
    expect(at.contributionMarginMinor).toBe(at.revenueMinor - at.estimatedCostMinor);
  });
});

describe('drafts', () => {
  it('checks a cost model and its reason', () => {
    expect(economicsInputProblems({ ...NUMBERS, reason: 'Fuel went up' })).toEqual({});
    expect(economicsInputProblems({ ...NUMBERS, driverCostPerHourMinor: 100_001, perStopMinutes: null, driverCostPerMileMinor: 7.5, reason: ' ' })).toEqual({
      driverCostPerHourMinor: 'range',
      perStopMinutes: 'required',
      driverCostPerMileMinor: 'whole',
      reason: 'required'
    });
    expect(economicsInputProblems({ ...NUMBERS, reason: 'x'.repeat(501) }).reason).toBe('tooLong');
  });

  it('checks an advertorial by characters, and what staff can do in each state', () => {
    expect(advertorialInputProblems({ headline: 'Hi', ctaLabel: 'Go', source: 'WINDOW', sourceId: 'lbk-7' }, true)).toEqual({});
    expect(advertorialInputProblems({ headline: ' ', ctaLabel: 'é'.repeat(41), body: 'x'.repeat(401), weight: 0, priority: 1001, startsAt: '2026-10-12T10:00:00Z', endsAt: '2026-10-11T10:00:00Z' }, true)).toEqual({
      headline: 'required',
      ctaLabel: 'tooLong',
      body: 'tooLong',
      sourceId: 'required',
      weight: 'range',
      priority: 'range',
      endsAt: 'order'
    });
    expect(advertorialInputProblems({ headline: 'é'.repeat(140), ctaLabel: 'Go' }, false)).toEqual({});
    expect(advertorialActions('DRAFT')).toEqual(['publish', 'end']);
    expect(advertorialActions('LIVE')).toEqual(['pause', 'end']);
    expect(advertorialActions('ENDED')).toEqual([]);
  });
});

describe('economics settings through the mock BFF', () => {
  it('starts on the environment defaults and saves a version with a reason, If-Match and an audit trail', async () => {
    const first = await runs().settings();
    expect(first).toMatchObject({ version: 0, source: 'CONFIG', driverCostPerHourMinor: 2500, can: { manage: true } });
    const saved = await runs().updateSettings({ ...NUMBERS, driverCostPerHourMinor: 2800, reason: 'Fuel and insurance went up' }, first.version);
    expect(saved).toMatchObject({ version: 1, source: 'STAFF', driverCostPerHourMinor: 2800, reason: 'Fuel and insurance went up', updatedBy: expect.any(String) });
    await expect(runs().updateSettings({ ...NUMBERS, reason: 'Stale' }, 0)).rejects.toMatchObject({ status: 412 });
    const same = await runs().updateSettings({ ...NUMBERS, driverCostPerHourMinor: 2800, reason: 'Looked again' }, 1);
    expect(same.version).toBe(1);
    const changes = await runs().settingChanges();
    expect(changes).toHaveLength(1);
    expect(changes[0]).toMatchObject({ version: 1, reason: 'Fuel and insurance went up', before: { driverCostPerHourMinor: 2500 }, after: { driverCostPerHourMinor: 2800 } });
    expect(staffRunKeys.settings()).toEqual(['staff', 'runs', 'settings']);
  });

  it('refuses a missing reason and out-of-range numbers field by field', async () => {
    const err = await runs().updateSettings({ ...NUMBERS, collectionMinutes: 5000, reason: '' }, 0).catch((e: unknown) => e);
    expect(isApiError(err) && err.status === 422 && Object.keys(err.fieldErrors).sort()).toEqual(['collectionMinutes', 'reason']);
  });

  it('lets viewers read but not change, and keeps other roles out', async () => {
    const read = await runs('platform.finance').settings();
    expect(read.can.manage).toBe(false);
    await expect(runs('platform.finance').updateSettings({ ...NUMBERS, reason: 'x' }, 0)).rejects.toMatchObject({ status: 403 });
    await expect(runs('platform.trust_safety').settings()).rejects.toMatchObject({ status: 403 });
  });

  it('moves every later calculation when the cost model changes', async () => {
    const before = await runs().previewRun({ locationId: '4b2f9d3a-0000-4000-8000-00000000000a', feeMinor: 799, minOrders: 12 });
    await runs().updateSettings({ ...NUMBERS, driverCostPerHourMinor: 5000, reason: 'Overtime rates' }, 0);
    const after = await runs().previewRun({ locationId: '4b2f9d3a-0000-4000-8000-00000000000a', feeMinor: 799, minOrders: 12 });
    expect(after.cost.fixedCostMinor).toBeGreaterThan(before.cost.fixedCostMinor);
    expect(after.breakEvenOrders).toBeGreaterThan(before.breakEvenOrders as number);
  });
});

describe('break-even and P&L through the mock BFF', () => {
  it('shows the break-even of the window being created and whether its minimum reaches it', async () => {
    const plan = await runs().previewWindow({ targets: ['US/TX/Lubbock'], door: true, minOrders: 5 });
    expect(plan).toMatchObject({ locationName: 'Lubbock', dropMode: 'DOORSTEP', feeMinor: 799, minOrders: 5 });
    expect(plan.economics?.breakEvenOrders).toBeGreaterThan(5);
    expect(plan.minimumCoversBreakEven).toBe(false);
    const enough = await runs().previewWindow({ targets: ['US/TX/Lubbock'], door: true, minOrders: 100 });
    expect(enough.minimumCoversBreakEven).toBe(true);
    expect((await runs().previewWindow({ targets: ['US/TX/Nowhere'] })).economics).toBeNull();
    await expect(runs().previewWindow({})).rejects.toMatchObject({ status: 422 });
  });

  it('reports a window at its actual orders, and a cancelled window as refunds', async () => {
    const open = await runs().windowEconomics('lbk-7');
    expect(open).toMatchObject({ cancelled: false, locationName: 'Lubbock' });
    expect(open.orders).toBeGreaterThan(0);
    expect(open.revenueMinor).toBeGreaterThan(0);
    expect(open.economics?.atOrders.orders).toBe(open.orders);
    await api().preorders.cancel('lbk-7', 'Not enough sellers to fill it');
    const cancelled = await runs().windowEconomics('lbk-7');
    expect(cancelled).toMatchObject({ cancelled: true, orders: 0, revenueMinor: 0, deliveryFeesMinor: 0, economics: null });
    expect(cancelled.ordersRefunded).toBeGreaterThan(0);
    expect(cancelled.refundedMinor).toBeGreaterThan(0);
    await expect(runs().windowEconomics('nope')).rejects.toMatchObject({ status: 404 });
  });

  it('gives a run a P&L before, after and when cancelled', async () => {
    const all = await runs().runs({ locationId: '4b2f9d3a-0000-4000-8000-00000000000a' });
    const byStatus = (s: string) => all.find((r) => r.status === s)!;
    const before = await runs().runPnl(byStatus('OPEN').id);
    expect(before).toMatchObject({ phase: 'BEFORE', ordersRefunded: 0 });
    const after = await runs().runPnl(byStatus('COMPLETED').id, 2000);
    expect(after).toMatchObject({ phase: 'AFTER' });
    expect(after.economics.atOrders).toMatchObject({ commissionShareMinor: 2000 });
    const cancelled = await runs().runPnl(byStatus('CANCELLED').id);
    expect(cancelled).toMatchObject({ phase: 'CANCELLED', ordersRefunded: 7, refundedFeesMinor: 7 * 799, cancelReason: expect.any(String) });
    expect(cancelled.economics.atOrders).toMatchObject({ orders: 0, feeRevenueMinor: 0 });
    expect(await runs().runs({ status: ['OPEN', 'COMPLETED'] })).toHaveLength(6);
  });

  it('keeps a location history of orders per run and subscriber growth', async () => {
    const { items } = await runs().locations();
    expect(items.map((l) => l.name)).toEqual(['Lubbock', 'Amarillo', 'Midland']);
    const h = await runs().locationHistory(items[0]!.id);
    expect(h.runs[0]!.windowStart > h.runs[h.runs.length - 1]!.windowStart).toBe(true);
    const cancelled = h.runs.find((r) => r.status === 'CANCELLED')!;
    expect(cancelled).toMatchObject({ feeRevenueMinor: 0, ordersRefunded: 7 });
    expect(h.subscribers.byChannel.EMAIL).toBeGreaterThan(0);
    expect(h.subscriberGrowth.at(-1)).toMatchObject({ month: '2026-10' });
    expect(h.subscriberGrowth.map((g) => g.count)).toEqual([...h.subscriberGrowth.map((g) => g.count)].sort((a, b) => a - b));
  });
});

describe('advertorials through the mock BFF', () => {
  it('lists the variants of a window with results and what is serving', async () => {
    const { items, can } = await runs().advertorials({ source: 'WINDOW', sourceId: 'lbk-7' });
    expect(can.manage).toBe(true);
    expect(items.map((a) => a.variant).sort()).toEqual(['A', 'B']);
    expect(items.find((a) => a.variant === 'B')!.results).toMatchObject({ views: 1792, clicks: 274 });
    expect(items.every((a) => a.serving)).toBe(true);
  });

  it('generates one from the window, edits with If-Match, pauses, publishes again and ends it', async () => {
    const refused = await runs().generateAdvertorial('WINDOW', 'amr-1').catch((e: unknown) => e);
    expect(isApiError(refused) && refused.status).toBe(422);
    const created = await runs().generateAdvertorial('WINDOW', 'lbk-6');
    expect(created).toMatchObject({ status: 'LIVE', generated: true, variant: 'A', version: 0 });
    expect((await runs().generateAdvertorial('WINDOW', 'lbk-6')).id).toBe(created.id);
    const edited = await runs().updateAdvertorial(created.id, { headline: 'Come and get it', ctaLabel: 'Order', body: 'Short and sweet' }, 1);
    expect(edited).toMatchObject({ headline: 'Come and get it', generated: false });
    await expect(runs().updateAdvertorial(created.id, { headline: 'Stale', ctaLabel: 'Order' }, 0)).rejects.toMatchObject({ status: 412 });
    const paused = await runs().actAdvertorial(created.id, 'pause', edited.version);
    expect(paused.status).toBe('PAUSED');
    await expect(runs().actAdvertorial(created.id, 'pause', paused.version)).rejects.toMatchObject({ status: 422 });
    const live = await runs().actAdvertorial(created.id, 'publish', paused.version);
    expect(live.status).toBe('LIVE');
    const ended = await runs().actAdvertorial(created.id, 'end', live.version);
    expect(ended).toMatchObject({ status: 'ENDED', ended: 'STAFF', serving: false });
    await expect(runs().updateAdvertorial(created.id, { headline: 'Late', ctaLabel: 'Go' }, ended.version)).rejects.toMatchObject({ status: 422 });
  });

  it('writes a new variant, refusing what the form would refuse', async () => {
    const err = await runs().createAdvertorial({ source: 'WINDOW', sourceId: 'lbk-7', headline: '', ctaLabel: 'Go' }).catch((e: unknown) => e);
    expect(isApiError(err) && err.status === 422 && Object.keys(err.fieldErrors)).toEqual(['headline']);
    const c = await runs().createAdvertorial({ source: 'WINDOW', sourceId: 'lbk-7', headline: 'Third idea', ctaLabel: 'Go', weight: 2 });
    expect(c).toMatchObject({ variant: 'C', weight: 2, status: 'DRAFT' });
  });

  it('keeps viewers from changing anything', async () => {
    await expect(runs('platform.finance').generateAdvertorial('WINDOW', 'lbk-6')).rejects.toMatchObject({ status: 403 });
    expect((await runs('platform.finance').advertorials()).can.manage).toBe(false);
  });
});
