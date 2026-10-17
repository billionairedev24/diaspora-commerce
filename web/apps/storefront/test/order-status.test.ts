import { describe, expect, it } from 'vitest';

import { matchesOrder, orderGroup, shipmentLook } from '@/components/orders/orderStatus';

describe('how a shipment reads to a shopper', () => {
  it('names the stage in plain words and says how far along it is', () => {
    expect(shipmentLook('confirmed')).toMatchObject({ key: 'preparing', step: 1, group: 'IN_PROGRESS' });
    expect(shipmentLook('PACKED')).toMatchObject({ key: 'preparing', step: 2 });
    expect(shipmentLook('out_for_delivery')).toMatchObject({ key: 'onTheWay', step: 3 });
    expect(shipmentLook('In transit')).toMatchObject({ key: 'onTheWay' });
    expect(shipmentLook('shipped', '2026-10-01T10:00:00Z')).toMatchObject({ key: 'delivered', step: 4, group: 'DELIVERED' });
    expect(shipmentLook('cancelled')).toMatchObject({ key: 'cancelled', step: -1, group: 'CLOSED' });
    expect(shipmentLook('delivery_failed')).toMatchObject({ key: 'failed', tone: 'danger', group: 'IN_PROGRESS' });
  });

  it('groups an order by its shipments', () => {
    expect(orderGroup([{ status: 'cancelled' }, { status: 'returned' }])).toBe('CLOSED');
    expect(orderGroup([{ status: 'delivered' }, { status: 'cancelled' }])).toBe('DELIVERED');
    expect(orderGroup([{ status: 'delivered' }, { status: 'packed' }])).toBe('IN_PROGRESS');
    expect(orderGroup([])).toBe('IN_PROGRESS');
  });

  it('searches the order number, items, shipment and destination', () => {
    const o = { orderNumber: 'AT-2026-KZZE6B', shipTo: 'Houston, TX', shipments: [{ label: 'Mama Put Pantry', items: 'Shito ×2, Gari' }] };
    expect(matchesOrder(o, 'kzze')).toBe(true);
    expect(matchesOrder(o, 'GARI')).toBe(true);
    expect(matchesOrder(o, 'pantry')).toBe(true);
    expect(matchesOrder(o, 'houston')).toBe(true);
    expect(matchesOrder(o, 'turmeric')).toBe(false);
    expect(matchesOrder(o, '  ')).toBe(true);
  });
});
