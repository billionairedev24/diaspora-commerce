import { describe, expect, it } from 'vitest';

import { refundTotal, returnQrPayload, validateReturn } from '../src/domain/returns';
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
    expect(refundTotal(items, ['AT00000001', 'AT00000002'])).toBe(3498);
  });
  it('encodes the QR payload', () => {
    expect(returnQrPayload(['RMA-1', 'RMA-2'], 'ups')).toBe('afrotransact:return:ups:RMA-1,RMA-2');
  });
});
