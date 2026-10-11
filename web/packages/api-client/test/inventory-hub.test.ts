import { describe, expect, it } from 'vitest';

import { daysToExpiry, expiryTone, fefoAllocate, fefoOrder, inboundStep, receiptVariance, requiresExpiry, validateCountEntry, validateInboundLine } from '../src/inventory-hub';

const today = '2026-10-11';

describe('inbound line validation', () => {
  it('requires a lot and an expiry date for perishable product types', () => {
    expect(requiresExpiry({ productType: 'grocery' })).toBe(true);
    expect(requiresExpiry({ productType: 'perishable' })).toBe(true);
    expect(requiresExpiry({ productType: 'apparel' })).toBe(false);
    expect(requiresExpiry({ storage: 'FROZEN' })).toBe(true);
    expect(requiresExpiry({ shelfLifeDays: 540 })).toBe(true);
    expect(requiresExpiry({ shelfLifeDays: 3650 })).toBe(false);
    const e = validateInboundLine({ qty: 12 }, { expiryRequired: true, today });
    expect(e.expDate).toMatch(/required/);
    expect(e.lotNo).toMatch(/required/);
    expect(validateInboundLine({ qty: 12 }, { expiryRequired: false, today })).toEqual({});
  });

  it('checks quantities, whole cases and dates', () => {
    expect(validateInboundLine({ qty: 0 }, { expiryRequired: false, today }).qty).toBeDefined();
    expect(validateInboundLine({ qty: '2.5' }, { expiryRequired: false, today }).qty).toBeDefined();
    expect(validateInboundLine({ qty: 10, casePack: 4 }, { expiryRequired: false, today }).qty).toMatch(/multiple of 4/);
    expect(validateInboundLine({ qty: 12, casePack: 4 }, { expiryRequired: false, today })).toEqual({});
    expect(validateInboundLine({ qty: 1, mfgDate: '2027-01-01' }, { expiryRequired: false, today }).mfgDate).toMatch(/future/);
    expect(validateInboundLine({ qty: 1, expDate: '2026-10-01' }, { expiryRequired: false, today }).expDate).toMatch(/expired/);
    expect(validateInboundLine({ qty: 1, mfgDate: '2026-09-01', expDate: '2026-08-01' }, { expiryRequired: false, today }).expDate).toBeDefined();
    expect(validateInboundLine({ qty: 1, lotNo: 'B 7' }, { expiryRequired: false, today }).lotNo).toBeDefined();
    expect(validateInboundLine({ qty: 1, lotNo: 'B-7-DMG' }, { expiryRequired: false, today }).lotNo).toBeDefined();
  });
});

describe('receiving and counting', () => {
  it('flags short, over within 2 % and over-receipts that need approval', () => {
    expect(receiptVariance(100, 100, 0)).toEqual({ kind: 'match', diff: 0 });
    expect(receiptVariance(100, 95, 2).kind).toBe('short');
    expect(receiptVariance(100, 101, 1).kind).toBe('over');
    expect(receiptVariance(100, 103, 0).kind).toBe('overBlocked');
    expect(receiptVariance(10, 11, 0).kind).toBe('overBlocked');
  });

  it('needs a reason for every count variance', () => {
    expect(validateCountEntry(10, '10', undefined)).toEqual({});
    expect(validateCountEntry(10, '8', undefined).reasonCode).toBeDefined();
    expect(validateCountEntry(10, '8', 'LOST')).toEqual({});
    expect(validateCountEntry(10, '-1', 'LOST').countedQty).toBeDefined();
    expect(validateCountEntry(10, '', undefined).countedQty).toBeDefined();
  });
});

describe('FEFO and expiry', () => {
  const lot = (lotNo: string, expDate: string | null, qtyAvailable: number, status = 'SELLABLE', receivedAt = '2026-09-01T00:00:00Z') =>
    ({ lotNo, expDate, qtyAvailable, status, receivedAt }) as const;

  it('picks the earliest expiry first, skips held and empty lots, and puts lots without expiry last', () => {
    const lots = [lot('C', null, 5), lot('B', '2027-03-01', 4), lot('A', '2026-12-01', 2), lot('Q', '2026-11-01', 9, 'QUARANTINE'), lot('Z', '2026-10-20', 0)];
    expect(fefoOrder(lots).map((l) => l.lotNo)).toEqual(['A', 'B', 'C']);
    expect(fefoAllocate(lots, 5).map((a) => [a.lot.lotNo, a.qty])).toEqual([['A', 2], ['B', 3]]);
  });

  it('buckets days to expiry', () => {
    expect(daysToExpiry('2026-10-21', today)).toBe(10);
    expect(expiryTone(daysToExpiry('2026-10-01', today))).toBe('expired');
    expect(expiryTone(25)).toBe('within30');
    expect(expiryTone(45)).toBe('within60');
    expect(expiryTone(400)).toBe('ok');
    expect(expiryTone(daysToExpiry(null, today))).toBe('none');
  });

  it('places a shipment on the timeline', () => {
    const s = { status: 'SHIPPED' as const, shippedAt: '2026-10-09T10:00:00Z', expectedArrival: '2026-10-15', receivedUnits: 0 };
    expect(inboundStep({ ...s, status: 'WORKING' }, today)).toBe(0);
    expect(inboundStep(s, today)).toBe(2);
    expect(inboundStep({ ...s, expectedArrival: '2026-10-11' }, today)).toBe(3);
    expect(inboundStep({ ...s, status: 'RECEIVING' }, today)).toBe(4);
    expect(inboundStep({ ...s, status: 'CLOSED' }, today)).toBe(5);
    expect(inboundStep({ ...s, status: 'CANCELLED' }, today)).toBe(-1);
  });
});
