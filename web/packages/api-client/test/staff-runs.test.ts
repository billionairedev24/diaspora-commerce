import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { createStaffApiClient, isApiError, type RunScheduleInput, staffRunKeys } from '../src';
import { MOCK_STAFF_ROLE_COOKIE, resetStaffDb } from '../src/mocks/staff';
import { handleStaffMockRequest } from '../src/mocks/staff-runtime';
import {
  advertorialActions,
  advertorialInputProblems,
  economicsInputProblems,
  runBreakEven,
  runCostModel,
  runCutoffOutcome,
  runNeedsAttention,
  runOutlook,
  runScheduleProblems,
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
    expect(await runs().runs({ status: ['OPEN', 'COMPLETED'] })).toHaveLength(7);
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

/* ── Run lifecycle (S260) ─────────────────────────────── */

const LUBBOCK = '4b2f9d3a-0000-4000-8000-00000000000a';
const AMARILLO = '4b2f9d3a-0000-4000-8000-00000000000b';
const NOW = Date.parse('2026-10-10T15:00:00Z');
const iso = (days: number, hours = 0) => new Date(NOW + days * 86_400_000 + hours * 3_600_000).toISOString();

/** A run that starts in three days, closing orders tomorrow morning. */
const schedule = (over: Partial<RunScheduleInput> = {}): RunScheduleInput => ({
  locationId: LUBBOCK,
  windowStart: iso(3, 0),
  windowEnd: iso(3, 4),
  cutoffAt: iso(1, 8),
  minOrders: 12,
  capacityOrders: 30,
  feeMinor: null,
  notes: 'Cold bags in the van',
  ...over
});

describe('the checks of a run being scheduled', () => {
  it('accepts a valid schedule and reports each field that is not', () => {
    expect(runScheduleProblems(schedule(), true)).toEqual({});
    expect(
      runScheduleProblems(
        {
          windowStart: iso(3, 0),
          windowEnd: iso(5, 0),
          cutoffAt: iso(3, 1),
          opensAt: iso(4, 0),
          sellerPickupAt: iso(0, 0),
          minOrders: 0,
          capacityOrders: 5.5,
          capacityPackages: 100_001,
          feeMinor: 20_000,
          notes: 'x'.repeat(501)
        },
        true
      )
    ).toEqual({
      locationId: 'required',
      windowEnd: 'order',
      cutoffAt: 'order',
      opensAt: 'order',
      sellerPickupAt: 'order',
      minOrders: 'range',
      capacityOrders: 'whole',
      capacityPackages: 'range',
      feeMinor: 'range',
      notes: 'tooLong'
    });
    expect(runScheduleProblems(schedule({ minOrders: 12, capacityOrders: 10 }), true)).toEqual({ capacityOrders: 'order' });
    expect(runScheduleProblems({ ...schedule(), locationId: undefined }, false)).toEqual({});
    expect(runScheduleProblems(schedule({ notes: '😀'.repeat(500) }), true)).toEqual({});
  });
});

describe('where an order-taking run stands', () => {
  const run = { status: 'OPEN', cutoffAt: iso(1), minOrders: 12, reservedOrders: 5, cutoffPolicy: 'EXTEND' as const, extensions: 0, ordersLeft: 25 };

  it('predicts what the cutoff job does: confirm at the minimum, extend a short run once, then cancel', () => {
    expect(runCutoffOutcome({ minimumMet: true, cutoffPolicy: 'CANCEL' })).toBe('CONFIRM');
    expect(runCutoffOutcome({ minimumMet: false, cutoffPolicy: 'EXTEND', extensions: 0 })).toBe('EXTEND');
    expect(runCutoffOutcome({ minimumMet: false, cutoffPolicy: 'EXTEND', extensions: 1 })).toBe('CANCEL');
    expect(runCutoffOutcome({ minimumMet: false, cutoffPolicy: 'CANCEL', extensions: 0 })).toBe('CANCEL');
  });

  it('is at risk when short with the cutoff within two days, filling when there is time, on track at the minimum', () => {
    expect(runOutlook(run, NOW)).toMatchObject({ kind: 'AT_RISK', short: 7, outcome: 'EXTEND', ordersLeft: 25 });
    expect(runOutlook({ ...run, cutoffAt: iso(6) }, NOW)).toMatchObject({ kind: 'FILLING' });
    expect(runOutlook({ ...run, reservedOrders: 12 }, NOW)).toMatchObject({ kind: 'ON_TRACK', short: 0, outcome: 'CONFIRM' });
    expect(runOutlook({ ...run, cutoffAt: iso(-0.01) }, NOW)).toMatchObject({ kind: 'DECIDING' });
    expect(runOutlook({ ...run, extensions: 1 }, NOW)).toMatchObject({ outcome: 'CANCEL' });
  });

  it('has no outlook once ordering is over, and flags only the runs that need a decision', () => {
    for (const status of ['DRAFT', 'ANNOUNCED', 'CONFIRMED', 'CANCELLED', 'COMPLETED']) expect(runOutlook({ ...run, status }, NOW)).toBeNull();
    expect(runNeedsAttention(run, NOW)).toBe(true);
    expect(runNeedsAttention({ ...run, cutoffAt: iso(6) }, NOW)).toBe(false);
    expect(runNeedsAttention({ ...run, reservedOrders: 12 }, NOW)).toBe(false);
    expect(runNeedsAttention({ ...run, status: 'CANCELLED' }, NOW)).toBe(false);
  });
});

describe('scheduling, opening, extending, confirming and cancelling a run through the mock BFF', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('shows a location with its area, and a run with the location it goes to', async () => {
    const { items } = await runs().locations();
    expect(items.find((l) => l.name === 'Lubbock')?.area).toMatchObject({ kind: 'RADIUS', radiusMiles: 15 });
    expect(items.find((l) => l.name === 'Amarillo')?.area).toMatchObject({ kind: 'POLYGON', geoZoneId: expect.stringMatching(/^zone-/) });
    const list = await runs().runs({ locationId: LUBBOCK });
    expect(list.every((r) => r.locationName === 'Lubbock')).toBe(true);
    const open = list.find((r) => r.status === 'OPEN')!;
    const detail = await runs().run(open.id);
    expect(detail).toMatchObject({ locationName: 'Lubbock', location: { area: { kind: 'RADIUS' } }, can: { manage: true }, allowed: { extend: true, confirm: true, cancel: true, announce: false, delete: false } });
    expect(detail.breakEvenOrders).toBeGreaterThan(0);
    await expect(runs().run('7e5c2a6d-0000-4000-8000-0000000fffff')).rejects.toMatchObject({ status: 404 });
  });

  it('creates a draft with the location area and notes, edits it, announces it and opens it', async () => {
    const created = await runs().createRun(schedule({ notes: '  Cold bags in the van ' }));
    expect(created).toMatchObject({ status: 'DRAFT', locationName: 'Lubbock', notes: 'Cold bags in the van', feeMinor: 799, minOrders: 12, capacityOrders: 30, allowed: { announce: true, open: true, delete: true, edit: true, extend: false } });
    const edited = await runs().updateRun(created.id, schedule({ minOrders: 15, notes: '' }));
    expect(edited).toMatchObject({ minOrders: 15, notes: null });
    expect((await runs().announceRun(created.id)).status).toBe('ANNOUNCED');
    await expect(runs().announceRun(created.id)).rejects.toMatchObject({ status: 409 });
    const open = await runs().openRun(created.id);
    expect(open).toMatchObject({ status: 'OPEN', allowed: { extend: true, confirm: true, announce: false, open: false, delete: false } });
    expect(open.milestones).toMatchObject({ openedAt: expect.any(String) });
    // An open run keeps its fee and cutoff; customers have ordered on it.
    await expect(runs().updateRun(created.id, schedule({ minOrders: 15, feeMinor: 999 }))).rejects.toMatchObject({ status: 422 });
    await expect(runs().updateRun(created.id, schedule({ minOrders: 15, cutoffAt: iso(1, 10) }))).rejects.toMatchObject({ status: 422 });
    expect((await runs().updateRun(created.id, schedule({ minOrders: 8, capacityOrders: 20 }))).minOrders).toBe(8);
    await expect(runs().updateRun(created.id, { ...schedule(), locationId: AMARILLO })).rejects.toMatchObject({ status: 422 });
  });

  it('refuses a schedule field by field and leaves a draft that can be deleted', async () => {
    const err = await runs().createRun(schedule({ cutoffAt: iso(4), minOrders: 0 })).catch((e: unknown) => e);
    expect(isApiError(err) && err.status === 422 && Object.keys(err.fieldErrors).sort()).toEqual(['cutoffAt', 'minOrders']);
    const past = await runs().createRun(schedule({ windowStart: iso(-1, 4), windowEnd: iso(-1, 8), cutoffAt: iso(-1, 0) })).catch((e: unknown) => e);
    expect(isApiError(past) && past.fieldErrors.cutoffAt).toBeTruthy();
    await expect(runs().createRun(schedule({ locationId: '4b2f9d3a-0000-4000-8000-0000000000ee' }))).rejects.toMatchObject({ status: 422 });
    const draft = await runs().createRun(schedule());
    await runs().deleteRun(draft.id);
    await expect(runs().run(draft.id)).rejects.toMatchObject({ status: 404 });
    const open = (await runs().runs({ status: ['OPEN'] }))[0]!;
    await expect(runs().deleteRun(open.id)).rejects.toMatchObject({ status: 422 });
  });

  it('extends with a reason, logging who did it and when', async () => {
    const open = (await runs().runs({ locationId: LUBBOCK, status: ['OPEN'] }))[0]!;
    const cutoff = Date.parse(open.cutoffAt);
    const body = { cutoffAt: new Date(cutoff + 2 * 86_400_000).toISOString() };
    await expect(runs().extendRun(open.id, { ...body, reason: '  ' })).rejects.toMatchObject({ status: 422 });
    await expect(runs().extendRun(open.id, { cutoffAt: new Date(cutoff - 86_400_000).toISOString(), reason: 'Earlier?' })).rejects.toMatchObject({ status: 422 });
    const extended = await runs().extendRun(open.id, { ...body, reason: 'Two sellers need another day' });
    expect(extended).toMatchObject({ status: 'EXTENDED', extensions: 1, cutoffAt: body.cutoffAt });
    expect(Date.parse(extended.windowStart) - Date.parse(open.windowStart)).toBe(2 * 86_400_000);
    const log = await runs().runLog(open.id);
    expect(log).toHaveLength(1);
    expect(log[0]).toMatchObject({ kind: 'EXTEND', reason: 'Two sellers need another day', bySystem: false, fromStatus: 'OPEN', toStatus: 'EXTENDED', fromCutoff: open.cutoffAt, toCutoff: body.cutoffAt });
    expect(log[0]!.actor).not.toBe('System');
    // Staff may extend again; the job extends a short run only once.
    expect((await runs().extendRun(open.id, { cutoffAt: new Date(cutoff + 4 * 86_400_000).toISOString(), reason: 'Still short' })).extensions).toBe(2);
  });

  it('confirms below the minimum only with a reason, and logs the override', async () => {
    const short = (await runs().runs({ locationId: AMARILLO, status: ['OPEN'] })).find((r) => !r.minimumMet)!;
    const err = await runs().confirmRun(short.id).catch((e: unknown) => e);
    expect(isApiError(err) && err.status).toBe(422);
    expect((await runs().run(short.id)).status).toBe('OPEN');
    const confirmed = await runs().confirmRun(short.id, 'The sellers already packed');
    expect(confirmed).toMatchObject({ status: 'CONFIRMED', allowed: { cancel: true, extend: false, confirm: false } });
    const log = await runs().runLog(short.id);
    expect(log.at(-1)).toMatchObject({ kind: 'CONFIRM_OVERRIDE', reason: 'The sellers already packed', orders: short.reservedOrders, minimum: short.minOrders, toStatus: 'CONFIRMED' });
    await expect(runs().confirmRun(short.id, 'Again')).rejects.toMatchObject({ status: 409 });
    const enough = (await runs().runs({ locationId: LUBBOCK, status: ['OPEN'] })).find((r) => r.minimumMet)!;
    await runs().confirmRun(enough.id);
    expect((await runs().runLog(enough.id)).at(-1)).toMatchObject({ kind: 'CONFIRM', reason: null });
  });

  it('cancels with a reason, refunds every order and cannot be cancelled twice', async () => {
    const open = (await runs().runs({ locationId: LUBBOCK, status: ['OPEN'] }))[0]!;
    await expect(runs().cancelRun(open.id, '   ')).rejects.toMatchObject({ status: 422 });
    const cancelled = await runs().cancelRun(open.id, 'Tropical storm warning');
    expect(cancelled).toMatchObject({ status: 'CANCELLED', cancelReason: 'Tropical storm warning', allowed: { cancel: false, extend: false, edit: false } });
    const pnl = await runs().runPnl(open.id);
    expect(pnl).toMatchObject({ phase: 'CANCELLED', ordersRefunded: open.reservedOrders, cancelReason: 'Tropical storm warning' });
    const orders = await runs().runOrders(open.id);
    expect(orders).toHaveLength(open.reservedOrders);
    expect(orders.every((o) => o.status === 'CANCELLED' && o.orderNumber && o.customerName)).toBe(true);
    expect((await runs().runLog(open.id)).at(-1)).toMatchObject({ kind: 'CANCEL', reason: 'Tropical storm warning' });
    await expect(runs().cancelRun(open.id, 'Again')).rejects.toMatchObject({ status: 409 });
    await expect(runs().runOrders(open.id, 'MAYBE' as never)).rejects.toMatchObject({ status: 422 });
  });

  it('lists the orders of a run by number and customer name, filtered by slot status', async () => {
    const open = (await runs().runs({ locationId: LUBBOCK, status: ['OPEN'] }))[0]!;
    const all = await runs().runOrders(open.id);
    expect(all).toHaveLength(open.reservedOrders);
    expect(all.every((o) => o.status === 'RESERVED' && /^AT-\d+$/.test(o.orderNumber ?? '') && !/^ord-/.test(o.customerName ?? ''))).toBe(true);
    expect(await runs().runOrders(open.id, 'CANCELLED')).toEqual([]);
  });

  it('keeps everyone without manage rights read-only', async () => {
    const open = (await runs('platform.finance').runs({ status: ['OPEN'] }))[0]!;
    const detail = await runs('platform.finance').run(open.id);
    expect(detail.can.manage).toBe(false);
    expect(detail.allowed.cancel).toBe(true);
    await expect(runs('platform.finance').createRun(schedule())).rejects.toMatchObject({ status: 403 });
    await expect(runs('platform.finance').cancelRun(open.id, 'No')).rejects.toMatchObject({ status: 403 });
    await expect(runs('platform.finance').confirmRun(open.id, 'No')).rejects.toMatchObject({ status: 403 });
    await expect(runs('platform.finance').extendRun(open.id, { cutoffAt: iso(30), reason: 'No' })).rejects.toMatchObject({ status: 403 });
    expect(await runs('platform.finance').runLog(open.id)).toBeInstanceOf(Array);
    await expect(runs('platform.trust_safety').run(open.id)).rejects.toMatchObject({ status: 403 });
  });

  it('lets the cutoff job decide a run when its cutoff passes, and log it as System', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(NOW));
    resetStaffDb(new Date(NOW));
    const short = await runs().createRun(schedule({ cutoffAt: iso(0, 2), windowStart: iso(1, 0), windowEnd: iso(1, 4), minOrders: 4, capacityOrders: 10 }));
    await runs().openRun(short.id);
    expect((await runs().run(short.id)).reservedOrders).toBe(0);
    vi.setSystemTime(new Date(NOW + 3 * 3_600_000));
    const extended = await runs().run(short.id);
    expect(extended).toMatchObject({ status: 'EXTENDED', extensions: 1 });
    expect(Date.parse(extended.cutoffAt) - Date.parse(short.cutoffAt)).toBe(7 * 86_400_000);
    vi.setSystemTime(new Date(NOW + 9 * 86_400_000));
    const cancelled = await runs().run(short.id);
    expect(cancelled).toMatchObject({ status: 'CANCELLED', cancelReason: 'MINIMUM_NOT_MET' });
    const log = await runs().runLog(short.id);
    expect(log.map((e) => [e.kind, e.actor, e.bySystem, e.reason])).toEqual([
      ['EXTEND', 'System', true, 'MINIMUM_NOT_MET'],
      ['CANCEL', 'System', true, 'MINIMUM_NOT_MET']
    ]);
  });
});
