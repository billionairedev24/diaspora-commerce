import { describe, expect, it } from 'vitest';

import { deliveredByAfroTransact, headline, showDeliveryCode, stepDetail, stepIndex, TRACKING_STEPS } from '../src/domain/tracking';

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

  it('names AfroTransact as the deliverer, and shows proof, only for the modes AfroTransact delivers', () => {
    expect(deliveredByAfroTransact('afrotransact_delivery')).toBe(true);
    expect(deliveredByAfroTransact('fulfilled_by_afrotransact')).toBe(true);
    expect(deliveredByAfroTransact('preorder_run')).toBe(true);
    expect(deliveredByAfroTransact('legacy_seller_shipped')).toBe(false);
    expect(deliveredByAfroTransact('pickup')).toBe(false);
  });

  it('never says the fulfilment centre or AfroTransact Delivery moves a seller-shipped order', () => {
    const texts = TRACKING_STEPS.map((st) => stepDetail(st, 'legacy_seller_shipped'));
    expect(texts.join(' ')).not.toMatch(/AfroTransact|fulfilment centre|driver/i);
    expect(stepDetail(TRACKING_STEPS[1], 'legacy_seller_shipped')).toBe('Packed by the seller');
    expect(stepDetail(TRACKING_STEPS[2], 'legacy_seller_shipped')).toBe('Shipped by the seller');
    expect(stepDetail(TRACKING_STEPS[1], 'fulfilled_by_afrotransact')).toBe('Packed at the fulfilment centre');
  });

  it('the delivery code is only for AfroTransact Delivery, never a seller-shipped order', () => {
    expect(showDeliveryCode({ method: 'legacy_seller_shipped', handover: 'hand', perishable: true, deliveryCode: '4821', status: 'out_for_delivery' })).toBe(false);
  });
});
