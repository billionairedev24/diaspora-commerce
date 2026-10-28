import { beforeEach, describe, expect, it } from 'vitest';

import { createStaffApiClient, isApiError, staffDeliveryFeeKeys } from '../src';
import { MOCK_STAFF_ROLE_COOKIE, resetStaffDb } from '../src/mocks/staff';
import { handleStaffMockRequest } from '../src/mocks/staff-runtime';
import {
  bandDraftsOf,
  bandLabel,
  bandsFromDrafts,
  type DeliveryFeeRule,
  doorFeeRange,
  feeAmountText,
  feeReasonProblem,
  parseFeeAmount,
  parseMiles,
  resolveCarrierFee,
  resolveDoorFee,
  resolvePickupFee,
  ruleInputProblems
} from '../src/staff-delivery-fees';

const api = (roles?: string) =>
  createStaffApiClient({
    baseUrl: 'http://staff.test/api/bff',
    headers: roles ? { cookie: `${MOCK_STAFF_ROLE_COOKIE}=${roles}` } : {},
    fetch: (req) => handleStaffMockRequest(req)
  }).deliveryFees;

const rule = (extra: Partial<DeliveryFeeRule>): DeliveryFeeRule => ({
  id: 'r', method: 'AFROTRANSACT_DELIVERY', currency: 'USD', version: 3, bands: [], feeMinor: 0, handlingMinor: 0, fallbackMinor: 0, freeAboveMinor: null, coverageMiles: 0,
  updatedAt: '2026-10-24T12:00:00Z', updatedBy: 'stf', reason: 'x', ...extra
});
const BANDS = [
  { fromMiles: 0, toMiles: 10, feeMinor: 599 },
  { fromMiles: 10, toMiles: 40, feeMinor: 799 },
  { fromMiles: 40, toMiles: 80, feeMinor: 1299 }
];

beforeEach(() => resetStaffDb(new Date('2026-10-10T15:00:00Z')));

describe('delivery fee drafts', () => {
  it('reads amounts and distances people type', () => {
    expect(parseFeeAmount('7.99')).toBe(799);
    expect(parseFeeAmount('$0')).toBe(0);
    expect(parseFeeAmount(' 12 ')).toBe(1200);
    expect(parseFeeAmount('')).toBeNull();
    expect(parseFeeAmount('seven')).toBeNaN();
    expect(parseFeeAmount('1000')).toBeNaN();
    expect(feeAmountText(799)).toBe('7.99');
    expect(feeAmountText(null)).toBe('');
    expect(parseMiles('12.5')).toBe(12.5);
    expect(parseMiles('12.555')).toBeNaN();
    expect(parseMiles('')).toBeNaN();
  });

  it('turns "up to N miles costs $X" rows into contiguous bands from zero', () => {
    const ok = bandsFromDrafts([
      { upTo: '10', fee: '5.99' },
      { upTo: '40', fee: '7.99' },
      { upTo: '80.5', fee: '12.99' }
    ]);
    expect(ok.problems).toBeNull();
    expect(ok.bands).toEqual([
      { fromMiles: 0, toMiles: 10, feeMinor: 599 },
      { fromMiles: 10, toMiles: 40, feeMinor: 799 },
      { fromMiles: 40, toMiles: 80.5, feeMinor: 1299 }
    ]);
    expect(bandDraftsOf(rule({ bands: BANDS }))).toEqual([
      { upTo: '10', fee: '5.99' },
      { upTo: '40', fee: '7.99' },
      { upTo: '80', fee: '12.99' }
    ]);
    expect(bandLabel(BANDS[0]!)).toBe('Up to 10 miles');
    expect(bandLabel(BANDS[1]!)).toBe('10 to 40 miles');
  });

  it('says which row and field is wrong', () => {
    const bad = bandsFromDrafts([
      { upTo: '10', fee: '5.99' },
      { upTo: '8', fee: '' },
      { upTo: '600', fee: '200' }
    ]);
    expect(bad.bands).toEqual([]);
    expect(bad.problems?.rows[1]).toEqual({ upTo: 'notAbove', after: 10, fee: 'notAmount' });
    expect(bad.problems?.rows[2]).toEqual({ upTo: 'tooFar', fee: 'tooHigh' });
    expect(bad.problems?.rows[0]).toBeUndefined();
    expect(bandsFromDrafts([{ upTo: 'far', fee: '1' }]).problems?.rows[0]).toEqual({ upTo: 'notDistance' });
    expect(bandsFromDrafts([]).problems?.table).toBe('none');
    expect(bandsFromDrafts(Array.from({ length: 13 }, (_, i) => ({ upTo: String(i + 1), fee: '1' }))).problems?.table).toBe('tooMany');
  });

  it('checks a rule like the BFF does, reason included', () => {
    expect(ruleInputProblems('AFROTRANSACT_DELIVERY', { bands: BANDS, reason: 'Fuel' })).toEqual({});
    expect(ruleInputProblems('AFROTRANSACT_DELIVERY', { bands: [], reason: '' })).toMatchObject({ bands: expect.any(String), reason: expect.any(String) });
    expect(ruleInputProblems('AFROTRANSACT_DELIVERY', { bands: [{ fromMiles: 5, toMiles: 10, feeMinor: 100 }], reason: 'x' })['bands[0].fromMiles']).toMatch(/starts at 0/);
    expect(ruleInputProblems('AFROTRANSACT_DELIVERY', { bands: [BANDS[0]!, { fromMiles: 15, toMiles: 30, feeMinor: 1 }], reason: 'x' })['bands[1].fromMiles']).toMatch(/gap/);
    expect(ruleInputProblems('AFROTRANSACT_DELIVERY', { bands: [BANDS[0]!, { fromMiles: 8, toMiles: 30, feeMinor: 1 }], reason: 'x' })['bands[1].fromMiles']).toMatch(/overlap/);
    expect(ruleInputProblems('CARRIER', { handlingMinor: 200, fallbackMinor: 599, freeAboveMinor: null, reason: 'x' })).toEqual({});
    expect(ruleInputProblems('CARRIER', { handlingMinor: -1, fallbackMinor: 20000, freeAboveMinor: -5, reason: 'x' })).toMatchObject({ handlingMinor: expect.any(String), fallbackMinor: expect.any(String), freeAboveMinor: expect.any(String) });
    expect(ruleInputProblems('PICKUP', { feeMinor: 0, reason: 'free' })).toEqual({});
    expect(ruleInputProblems('PICKUP', { reason: 'x' }).feeMinor).toBeDefined();
    expect(feeReasonProblem('  ')).toBe('required');
    expect(feeReasonProblem('x'.repeat(501))).toBe('tooLong');
    expect(feeReasonProblem('Fuel went up')).toBeNull();
  });
});

describe('the one resolution', () => {
  const door = rule({ bands: BANDS, coverageMiles: 80 });
  const pickup = rule({ id: 'p', method: 'PICKUP', feeMinor: 100, version: 2 });
  const carrier = rule({ id: 'c', method: 'CARRIER', handlingMinor: 200, fallbackMinor: 800, freeAboveMinor: 7500, version: 4 });
  const rules = [door, pickup, carrier];

  it('prices a door delivery by the band of the distance, with the rule and band named', () => {
    expect(resolveDoorFee(rules, 'USD', 4.2, null)).toMatchObject({ method: 'AFROTRANSACT_DELIVERY', amountMinor: 599, ruleVersion: 3, band: '0–10 mi', source: 'RULE', distanceMiles: 4.2 });
    expect(resolveDoorFee(rules, 'USD', 10, null)?.amountMinor).toBe(799);
    expect(resolveDoorFee(rules, 'USD', 79.99, null)?.amountMinor).toBe(1299);
    expect(resolveDoorFee(rules, 'USD', null, null)?.amountMinor).toBe(1299);
  });

  it('routes to carrier beyond the last band and without a rule', () => {
    expect(resolveDoorFee(rules, 'USD', 80, null)).toBeNull();
    expect(resolveDoorFee(rules, 'CAD', 5, null)).toBeNull();
  });

  it('lets the pre-order own fee win but still names the rule', () => {
    expect(resolveDoorFee(rules, 'USD', 200, 450)).toMatchObject({ amountMinor: 450, source: 'WINDOW_OVERRIDE', ruleId: 'r' });
    expect(resolvePickupFee(rules, 'USD', null)).toMatchObject({ amountMinor: 100, source: 'RULE', ruleVersion: 2 });
    expect(resolvePickupFee(rules, 'USD', 0)).toMatchObject({ amountMinor: 0, source: 'WINDOW_OVERRIDE' });
    expect(resolvePickupFee([], 'USD', null)).toMatchObject({ amountMinor: 0, ruleId: null });
  });

  it('prices carrier shipping by the quote plus handling, the fallback, or free over the threshold', () => {
    expect(resolveCarrierFee(rules, 'USD', 1150, 2000)).toMatchObject({ amountMinor: 1350, source: 'CARRIER_QUOTE' });
    expect(resolveCarrierFee(rules, 'USD', null, 2000)).toMatchObject({ amountMinor: 800, source: 'CARRIER_FALLBACK' });
    expect(resolveCarrierFee(rules, 'USD', 1150, 7500)).toMatchObject({ amountMinor: 0, band: 'free over 75.00' });
    expect(resolveCarrierFee(rules, 'CAD', null, 100)).toBeNull();
  });

  it('shows the range a rule charges', () => {
    const fmt = (m: number) => `$${(m / 100).toFixed(2)}`;
    expect(doorFeeRange(door, fmt)).toBe('$5.99–$12.99');
    expect(doorFeeRange(rule({ bands: [{ fromMiles: 0, toMiles: 100, feeMinor: 799 }] }), fmt)).toBe('$7.99');
    expect(doorFeeRange(undefined, fmt)).toBeNull();
  });
});

describe('delivery fees through the mock BFF', () => {
  it('lists the three methods carried over from the old fee maps', async () => {
    const { items, can } = await api().list();
    expect(can.manage).toBe(true);
    expect(items.map((r) => `${r.method}/${r.currency}`).sort()).toEqual(['AFROTRANSACT_DELIVERY/CAD', 'AFROTRANSACT_DELIVERY/USD', 'CARRIER/USD', 'PICKUP/CAD', 'PICKUP/USD']);
    const usd = items.find((r) => r.method === 'AFROTRANSACT_DELIVERY' && r.currency === 'USD');
    expect(usd).toMatchObject({ version: 1, coverageMiles: 100, bands: [{ fromMiles: 0, toMiles: 100, feeMinor: 799 }] });
    expect(items.find((r) => r.method === 'CARRIER')).toMatchObject({ fallbackMinor: 599, freeAboveMinor: 3500 });
  });

  it('saves a new version with a reason, with If-Match, and audits it', async () => {
    const door = (await api().list()).items.find((r) => r.method === 'AFROTRANSACT_DELIVERY' && r.currency === 'USD')!;
    const saved = await api().update('AFROTRANSACT_DELIVERY', 'USD', { bands: BANDS, reason: 'Texas launch rates' }, door.version);
    expect(saved).toMatchObject({ version: 2, updatedBy: expect.any(String), reason: 'Texas launch rates', coverageMiles: 80 });
    await expect(api().update('AFROTRANSACT_DELIVERY', 'USD', { bands: BANDS, reason: 'Stale' }, door.version)).rejects.toMatchObject({ status: 412 });
    const again = await api().update('AFROTRANSACT_DELIVERY', 'USD', { bands: BANDS, reason: 'Looked again' }, 2);
    expect(again.version).toBe(2);
    const changes = await api().changes({ method: 'AFROTRANSACT_DELIVERY', currency: 'USD' });
    expect(changes[0]).toMatchObject({ version: 2, reason: 'Texas launch rates', before: { version: 1 }, after: { version: 2 } });
    expect(changes[1]).toMatchObject({ version: 1, actor: 'migration', before: null });
    expect(await api().changes({ method: 'PICKUP', limit: 1 })).toHaveLength(1);
    expect(staffDeliveryFeeKeys.rules()).toEqual(['staff', 'delivery-fees', 'rules']);
  });

  it('creates a rule for a new currency at version 1', async () => {
    const created = await api().update('PICKUP', 'MXN', { feeMinor: 150, reason: 'Add Mexico' }, 0);
    expect(created).toMatchObject({ version: 1, feeMinor: 150, currency: 'MXN' });
  });

  it('refuses a missing reason and numbers that are not a rule, field by field', async () => {
    const err = await api().update('AFROTRANSACT_DELIVERY', 'USD', { bands: [{ fromMiles: 3, toMiles: 10, feeMinor: 100 }], reason: ' ' }, 1).catch((e: unknown) => e);
    expect(isApiError(err) && err.status === 422 && Object.keys(err.fieldErrors).sort()).toEqual(['bands[0].fromMiles', 'reason']);
    await expect(api().update('PICKUP', 'USD', { reason: 'x' }, 1)).rejects.toMatchObject({ status: 422 });
    await expect(api().update('CARRIER', 'USD', { handlingMinor: 0, fallbackMinor: 99999, reason: 'x' }, 1)).rejects.toMatchObject({ status: 422 });
  });

  it('gates changes by role but lets viewers read and preview', async () => {
    await expect(api('platform.customer_support').update('PICKUP', 'USD', { feeMinor: 0, reason: 'x' }, 1)).rejects.toMatchObject({ status: 403 });
    const read = await api('platform.finance').list();
    expect(read.can.manage).toBe(false);
    await expect(api('platform.finance').preview({ method: 'PICKUP', currency: 'USD' })).resolves.toMatchObject({ applies: true });
    await expect(api('platform.trust_safety').list()).rejects.toMatchObject({ status: 403 });
  });

  it('previews what checkout would show', async () => {
    const near = await api().preview({ method: 'AFROTRANSACT_DELIVERY', currency: 'usd', distanceMiles: 32 });
    expect(near).toMatchObject({ applies: true, fee: { amountMinor: 799, band: '0–100 mi', ruleVersion: 1 } });
    expect(await api().preview({ method: 'AFROTRANSACT_DELIVERY', currency: 'USD', distanceMiles: 140 })).toMatchObject({ applies: false, fee: null });
    expect(await api().preview({ method: 'CARRIER', currency: 'USD', itemsMinor: 5000 })).toMatchObject({ applies: true, fee: { amountMinor: 0, band: 'free over 35.00' } });
    expect(await api().preview({ method: 'CARRIER', currency: 'USD', carrierQuoteMinor: 900, itemsMinor: 1000 })).toMatchObject({ fee: { amountMinor: 900, source: 'CARRIER_QUOTE' } });
    const win = await api().preview({ method: 'PICKUP', currency: 'USD', windowId: 'lbk-7' });
    expect(win).toMatchObject({ applies: true, fee: { amountMinor: 0 } });
    await expect(api().preview({ method: 'PICKUP', currency: 'USD', windowId: 'nope' })).rejects.toMatchObject({ status: 404 });
  });

  it('shows the rule behind a window fee, and a change moves the next quote only', async () => {
    const before = await createStaffApiClient({ baseUrl: 'http://staff.test/api/bff', fetch: (req) => handleStaffMockRequest(req) }).preorders.get('lbk-7');
    expect(before.doorFeeRule).toMatchObject({ method: 'AFROTRANSACT_DELIVERY', ruleVersion: 1, amountMinor: 799, source: 'RULE' });
    expect(before.effectiveDoorFee).toBe(799);
    await api().update('AFROTRANSACT_DELIVERY', 'USD', { bands: [{ fromMiles: 0, toMiles: 100, feeMinor: 899 }], reason: 'Fuel' }, 1);
    const after = await createStaffApiClient({ baseUrl: 'http://staff.test/api/bff', fetch: (req) => handleStaffMockRequest(req) }).preorders.get('lbk-7');
    expect(after.doorFeeRule).toMatchObject({ ruleVersion: 2, amountMinor: 899 });
    expect(after.effectiveDoorFee).toBe(899);
  });
});
