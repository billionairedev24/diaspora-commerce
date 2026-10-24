import { beforeEach, describe, expect, it } from 'vitest';

import { createStaffApiClient, lineUsdTotal, minorDigits, usdMinorOf } from '../src';
import { resetHubDb } from '../src/mocks/inventory-hub';
import { MOCK_STAFF_ROLE_COOKIE, resetStaffDb } from '../src/mocks/staff';
import { handleStaffMockRequest } from '../src/mocks/staff-runtime';

/* ADR 0005 §7: customers pay in USD, stock is received in the currency actually paid. The USD total on the receiving
   form is computed like inventory-service values the lot; the mock BFF applies the same rules (known currency, positive
   rate, the table's latest rate as the default, USD value fixed at receiving). */

const NOW = new Date('2026-10-24T12:00:00Z');
const client = (roles?: string) =>
  createStaffApiClient({
    baseUrl: 'http://staff.test/api/bff',
    headers: roles ? { cookie: `${MOCK_STAFF_ROLE_COOKIE}=${roles}`, origin: 'http://staff.test' } : {},
    fetch: (req) => handleStaffMockRequest(req)
  });
const ASN = 'dddd0001-0000-4000-8000-000000000002';

describe('USD value of a cost', () => {
  it('converts minor units of any currency to cents with half-even rounding, like the service', () => {
    expect(usdMinorOf(12_000, 'MXN', '0.0577')).toBe(692);
    expect(usdMinorOf(150_000, 'NGN', '0.00066667')).toBe(100);
    expect(usdMinorOf(6_000, 'XOF', '0.00165')).toBe(990);
    expect(usdMinorOf(5, 'GHS', '0.5')).toBe(2);
    expect(usdMinorOf(7, 'GHS', '0.5')).toBe(4);
    expect(usdMinorOf(1_999, 'USD', 1)).toBe(1_999);
    expect(usdMinorOf(12_000, 'MXN', 0.0577)).toBe(692);
  });

  it('refuses a rate that is not a positive decimal', () => {
    for (const bad of ['', '0', '0.0', '-1', 'abc', '1,5', '0.00000000001']) expect(usdMinorOf(100, 'MXN', bad), bad).toBeNull();
    expect(usdMinorOf(-1, 'MXN', '0.05')).toBeNull();
  });

  it('knows how many minor-unit digits a currency has and totals a line as units × the USD unit cost', () => {
    expect(minorDigits('XOF')).toBe(0);
    expect(minorDigits('MXN')).toBe(2);
    expect(lineUsdTotal(40, 12_000, 'MXN', '0.0577')).toBe(40 * 692);
    expect(lineUsdTotal(40, 12_000, 'MXN', '')).toBeNull();
  });
});

describe('inventory hub mock: cost currencies, rates and valuation', () => {
  beforeEach(() => {
    resetHubDb(NOW);
    resetStaffDb(NOW);
  });
  const hub = () => client().inventory;

  it('lists the currencies stock is received in, USD first, and the rate suggested for each', async () => {
    const currencies = await hub().currencies();
    expect(currencies[0]).toMatchObject({ code: 'USD', base: true, minorUnits: 2 });
    expect(currencies.map((c) => c.code)).toEqual(expect.arrayContaining(['MXN', 'NGN', 'GHS', 'CAD', 'EUR', 'GBP']));
    expect(currencies.find((c) => c.code === 'XOF')?.minorUnits).toBe(0);

    const today = await hub().currentRates();
    expect(today.find((r) => r.currency === 'MXN')?.rateToUsd).toBe(0.0577);
    const earlier = await hub().currentRates('2026-10-10');
    expect(earlier.find((r) => r.currency === 'MXN')?.rateToUsd).toBe(0.0561);
    expect(earlier.some((r) => r.currency === 'CAD')).toBe(false);
  });

  it('keeps a Mexican lot in pesos with the rate and the USD value, and values the overview in USD with pesos alongside', async () => {
    const lots = await hub().lots({ sku: 'AT-JOL-200061', page: 0, size: 50 });
    const mx = lots.items.find((l) => l.lotNo === 'JOL-MX-2610');
    expect(mx).toMatchObject({ currency: 'MXN', unitLandedCostMinor: 12_000, fxRateToUsd: 0.0577, fxRateSource: 'TABLE', unitCostUsdMinor: 692 });
    expect(mx?.costComponents).toEqual({ goodsMinor: 10_000, freightMinor: 1_500, dutyMinor: 400, otherMinor: 100 });

    const overview = await hub().overview();
    const sellable = overview.byStatus.filter((s) => s.status === 'SELLABLE');
    expect(sellable.map((s) => s.currency)).toEqual(['MXN', 'USD']);
    const peso = sellable.find((s) => s.currency === 'MXN');
    expect(peso).toMatchObject({ units: 60, valueMinor: 60 * 12_000, valueUsdMinor: 60 * 692 });
    const houston = overview.nodes.find((n) => n.nodeId === 'HOU-FC');
    expect(houston?.currency).toBe('USD');
    expect(houston?.byCurrency.map((c) => c.currency)).toEqual(['MXN', 'USD']);
    const usdValue = houston?.byCurrency.reduce((a, c) => a + c.valueUsdMinor, 0);
    expect(houston?.valueMinor).toBe(usdValue);
  });

  it('receives a retail ASN in pesos at the receiver’s rate, or at the table’s rate when none is given', async () => {
    const entered = await hub().receive(ASN, [{ sku: 'AT-JOL-200061', qtyGood: 20, qtyDamaged: 0, lotNo: 'JOL-MX-A', expDate: '2027-12-01', cost: { unitLandedCostMinor: 12_000, currency: 'MXN', fxRateToUsd: 0.06, fxRateDate: '2026-10-23' } }], 'rcv-mx-1');
    expect(entered.lots[0]).toMatchObject({ currency: 'MXN', fxRateToUsd: 0.06, fxRateDate: '2026-10-23', fxRateSource: 'ENTERED', unitCostUsdMinor: 720 });
    const table = await hub().receive(ASN, [{ sku: 'AT-JOL-200061', qtyGood: 10, qtyDamaged: 2, lotNo: 'JOL-MX-B', expDate: '2027-12-01', cost: { unitLandedCostMinor: 12_000, currency: 'MXN', costComponents: { goodsMinor: 10_000, freightMinor: 2_000, dutyMinor: 0, otherMinor: 0 } } }], 'rcv-mx-2');
    expect(table.lots.map((l) => [l.lotNo, l.fxRateSource, l.fxRateToUsd, l.unitCostUsdMinor])).toEqual([['JOL-MX-B', 'TABLE', 0.0577, 692], ['JOL-MX-B-DMG', 'TABLE', 0.0577, 692]]);
    expect(table.lots[0]?.costComponents?.freightMinor).toBe(2_000);
  });

  it('refuses costs it cannot value, with the field to fix', async () => {
    const line = (cost: object) => [{ sku: 'AT-JOL-200061', qtyGood: 1, qtyDamaged: 0, lotNo: 'JOL-BAD', expDate: '2027-12-01', cost: cost as never }];
    await expect(hub().receive(ASN, line({ unitLandedCostMinor: 100, currency: 'JPY' }), 'k1')).rejects.toMatchObject({ status: 422, problem: { errors: { 'lines[0].cost.currency': expect.any(String) } } });
    await expect(hub().receive(ASN, line({ unitLandedCostMinor: 100, currency: 'ZAR' }), 'k2')).rejects.toMatchObject({ status: 422, problem: { errors: { 'lines[0].cost.fxRateToUsd': expect.stringContaining('No ZAR rate') } } });
    await expect(hub().receive(ASN, line({ unitLandedCostMinor: 100, currency: 'MXN', fxRateToUsd: 0 }), 'k3')).rejects.toMatchObject({ status: 422 });
    await expect(hub().receive(ASN, line({ unitLandedCostMinor: 100, currency: 'MXN', costComponents: { goodsMinor: 1, freightMinor: 1, dutyMinor: 1, otherMinor: 1 } }), 'k4')).rejects.toMatchObject({ status: 422 });
    const plan = await hub().createPlan({ nodeId: 'HOU-FC', lines: [{ atin: 'ATJOLLOF01', sku: 'AT-JOL-200061', qty: 24, casePack: 24 }] });
    await hub().ship(plan.id, plan.version);
    await expect(hub().receive(plan.id, line({ unitLandedCostMinor: 100, currency: 'USD' }), 'k5')).rejects.toMatchObject({ status: 422 });
  });
});

describe('Settings → Exchange rates (staff resource)', () => {
  beforeEach(() => {
    resetHubDb(NOW);
    resetStaffDb(NOW);
  });

  it('lists the table, newest day first, with search by currency', async () => {
    const page = await client().listResource('exchange-rates', { page: 0, size: 20 });
    expect(page.title).toBe('Exchange rates');
    expect(page.canCreate).toBe(true);
    expect(page.rows[0]?.cells.currency).toBeTruthy();
    const days = page.rows.map((r) => String(r.cells.rateDate));
    expect(days).toEqual([...days].sort().reverse());
    const mxn = await client().listResource('exchange-rates', { page: 0, size: 20, q: 'mxn' });
    expect(mxn.rows.every((r) => r.cells.currency === 'MXN')).toBe(true);
  });

  it('sets a rate with a reason, replaces one for the same day, and receiving then suggests it', async () => {
    await expect(client().createRecord('exchange-rates', { currency: 'ZAR', rateDate: '2026-10-22', rateToUsd: 0.054 })).rejects.toMatchObject({ status: 422 });
    const made = await client().createRecord('exchange-rates', { currency: 'ZAR', rateDate: '2026-10-22', rateToUsd: 0.054, note: 'SARB', reason: 'First ZAR purchase' });
    expect(made.id).toBe('ZAR_2026-10-22');
    expect(made.activity[0]?.text).toBe('Set to 0.054: First ZAR purchase');
    const suggested = (await client().inventory.currentRates()).find((r) => r.currency === 'ZAR');
    expect(suggested).toMatchObject({ rateDate: '2026-10-22', rateToUsd: 0.054 });

    const edited = await client().updateRecord('exchange-rates', made.id, { rateToUsd: 0.055, note: 'SARB', reason: 'Bank restated it' }, made.version ?? null);
    expect(edited.activity[0]?.text).toBe('Changed from 0.054 to 0.055: Bank restated it');
    await expect(client().updateRecord('exchange-rates', made.id, { rateToUsd: 0.056 }, null)).rejects.toMatchObject({ status: 422 });

    const replaced = await client().createRecord('exchange-rates', { currency: 'ZAR', rateDate: '2026-10-22', rateToUsd: 0.057, reason: 'Corrected' });
    expect(replaced.id).toBe(made.id);
    const rows = (await client().listResource('exchange-rates', { page: 0, size: 50, q: 'ZAR' })).rows;
    expect(rows).toHaveLength(1);
    expect(rows[0]?.cells.rateToUsd).toBe(0.057);

    const lot = await client().inventory.receive(ASN, [{ sku: 'AT-JOL-200061', qtyGood: 5, qtyDamaged: 0, lotNo: 'JOL-ZA-1', expDate: '2027-12-01', cost: { unitLandedCostMinor: 2_000, currency: 'ZAR' } }], 'rcv-za');
    expect(lot.lots[0]).toMatchObject({ currency: 'ZAR', fxRateSource: 'TABLE', fxRateToUsd: 0.057, unitCostUsdMinor: 114 });
  });

  it('is open to inventory managers and finance for reading; only inventory:adjust changes it', async () => {
    expect((await client('platform.finance').listResource('exchange-rates', { page: 0, size: 5 })).canCreate).toBe(false);
    await expect(client('platform.finance').createRecord('exchange-rates', { currency: 'MXN', rateDate: '2026-10-24', rateToUsd: 0.05, reason: 'no' })).rejects.toMatchObject({ status: 403 });
    expect((await client('platform.inventory_manager').listResource('exchange-rates', { page: 0, size: 5 })).canCreate).toBe(true);
    await expect(client('platform.customer_support').listResource('exchange-rates', { page: 0, size: 5 })).rejects.toMatchObject({ status: 403 });
  });
});
