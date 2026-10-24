import { describe, expect, it } from 'vitest';

import type { ReturnableLine } from '../src/api/types';
import { commonMethods, reasonsFor, refundTotal, returnQrPayload, validateReturn } from '../src/domain/returns';
import { headline, showDeliveryCode, stepIndex } from '../src/domain/tracking';

describe('tracking', () => {
  it('maps shipment statuses to steps', () => {
    expect(stepIndex('placed')).toBe(0);
    expect(stepIndex('PICKED_UP')).toBe(2);
    expect(stepIndex('out for delivery')).toBe(3);
    expect(stepIndex('delivered')).toBe(4);
    expect(stepIndex('something-new')).toBe(0);
    expect(headline('out_for_delivery', '6–8 pm')).toBe('Arriving today, 6–8 pm');
    expect(headline('delivered')).toBe('Delivered');
    expect(headline('placed', 'In 3–4 days')).toBe('Arriving in 3–4 days');
    expect(headline('out_for_delivery', 'Today 6–8 pm')).toBe('Arriving today 6–8 pm');
  });

  it('shows the delivery code for hand delivery and perishables only', () => {
    const base = { method: 'afrotransact_delivery' as const, handover: 'door' as const, perishable: false, deliveryCode: '4821', status: 'out_for_delivery' };
    expect(showDeliveryCode(base)).toBe(false);
    expect(showDeliveryCode({ ...base, handover: 'hand' })).toBe(true);
    expect(showDeliveryCode({ ...base, perishable: true })).toBe(true);
    expect(showDeliveryCode({ ...base, perishable: true, status: 'delivered' })).toBe(false);
    expect(showDeliveryCode({ ...base, perishable: true, method: 'carrier' })).toBe(false);
  });
});

describe('returns', () => {
  const items = [{ atin: 'AT00000001', title: 'Rice', priceMinor: 2199 }, { atin: 'AT00000002', title: 'Gari', priceMinor: 1299 }];
  it('validates and totals the refund', () => {
    expect(validateReturn([], 'DAMAGED')).toBe('Choose at least one item.');
    expect(validateReturn(['AT00000001'], null)).toBe('Choose a reason.');
    expect(validateReturn(['AT00000001'], 'DAMAGED')).toBeNull();
    expect(validateReturn(['AT00000001'], 'DAMAGED', null)).toMatch(/can’t be sent back together/);
    expect(refundTotal(items, ['AT00000001', 'AT00000002'])).toBe(3498);
  });
  it('encodes the QR payload', () => {
    expect(returnQrPayload(['RMA-1', 'RMA-2'], 'ups')).toBe('afrotransact:return:ups:RMA-1,RMA-2');
    expect(returnQrPayload(['RMA-1'], 'DRIVER_PICKUP')).toBe('afrotransact:return:driver:RMA-1');
  });
  it('limits food to seller-fault reasons and to the methods every chosen line allows', () => {
    const line = (o: Partial<ReturnableLine>): ReturnableLine => ({ lineId: 'l', atin: 'a', title: 't', quantity: 1, maxQuantity: 1, unitPriceMinor: 100, eligible: true, reportProblemOnly: false, sellerFaultOnly: false, windowEndsAt: null, methods: ['DROP_OFF', 'DRIVER_PICKUP'], ineligibleReason: null, ...o });
    const food = line({ reportProblemOnly: true, methods: ['NO_RETURN'] });
    expect(reasonsFor([line({})]).map((r) => r.code)).toContain('NO_LONGER_NEEDED');
    expect(reasonsFor([food]).map((r) => r.code)).not.toContain('NO_LONGER_NEEDED');
    expect(commonMethods([line({}), line({ methods: ['DROP_OFF'] })])).toEqual(['DROP_OFF']);
    expect(commonMethods([line({}), food])).toEqual([]);
  });
});
