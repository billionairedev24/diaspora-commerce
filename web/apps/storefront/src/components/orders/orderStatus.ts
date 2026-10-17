/* How a shipment's status reads to a shopper: a plain-language label, a tone and how far along it is, so the orders
   list shows "On the way" and a progress track instead of a raw status word. The five groups drive the page's filters. */
export type OrderGroup = 'IN_PROGRESS' | 'DELIVERED' | 'CLOSED';

export interface ShipmentLook {
  key: 'placed' | 'preparing' | 'ready' | 'onTheWay' | 'delivered' | 'cancelled' | 'returned' | 'failed';
  /** 0-4: ordered, packed, on the way, delivered (steps done); -1 when the track does not apply (closed shipments). */
  step: number;
  tone: 'info' | 'brand' | 'success' | 'neutral' | 'danger';
  group: OrderGroup;
}

export function shipmentLook(status: string, deliveredAt?: string | null): ShipmentLook {
  const s = status.toLowerCase().replace(/[\s-]+/g, '_');
  if (deliveredAt || s === 'delivered') return { key: 'delivered', step: 4, tone: 'success', group: 'DELIVERED' };
  switch (s) {
    case 'cancelled':
    case 'canceled':
      return { key: 'cancelled', step: -1, tone: 'neutral', group: 'CLOSED' };
    case 'returned':
      return { key: 'returned', step: -1, tone: 'neutral', group: 'CLOSED' };
    case 'delivery_failed':
      return { key: 'failed', step: -1, tone: 'danger', group: 'IN_PROGRESS' };
    case 'picked_up':
    case 'in_transit':
    case 'out_for_delivery':
      return { key: 'onTheWay', step: 3, tone: 'brand', group: 'IN_PROGRESS' };
    case 'packed':
    case 'ready':
      return { key: s === 'ready' ? 'ready' : 'preparing', step: 2, tone: 'info', group: 'IN_PROGRESS' };
    case 'confirmed':
      return { key: 'preparing', step: 1, tone: 'info', group: 'IN_PROGRESS' };
    default:
      return { key: 'placed', step: 1, tone: 'info', group: 'IN_PROGRESS' };
  }
}

/** An order's group: closed when every shipment is, delivered when every open one is, otherwise in progress. */
export function orderGroup(shipments: Array<{ status: string; deliveredAt?: string | null }>): OrderGroup {
  if (shipments.length === 0) return 'IN_PROGRESS';
  const groups = shipments.map((s) => shipmentLook(s.status, s.deliveredAt).group);
  if (groups.every((g) => g === 'CLOSED')) return 'CLOSED';
  if (groups.some((g) => g === 'IN_PROGRESS')) return 'IN_PROGRESS';
  return 'DELIVERED';
}

/** Case-insensitive match on the order number, the items, the shipment label and where it ships. */
export function matchesOrder(o: { orderNumber: string; shipTo: string; shipments: Array<{ label: string; items: string }> }, text: string): boolean {
  const needle = text.trim().toLowerCase();
  if (!needle) return true;
  return [o.orderNumber, o.shipTo, ...o.shipments.flatMap((s) => [s.label, s.items])].some((v) => v.toLowerCase().includes(needle));
}

export type OrderRange = 'all' | '3m' | '6m' | `y${number}`;

/** Amazon-style time filter: the past 3 or 6 months, then this year and the previous two years. */
export function rangeOptions(now: Date): Array<{ value: OrderRange; labelKey: 'ord.r.all' | 'ord.r.3m' | 'ord.r.6m' | 'ord.r.year'; vars?: Record<string, number> }> {
  const y = now.getFullYear();
  return [
    { value: 'all', labelKey: 'ord.r.all' },
    { value: '3m', labelKey: 'ord.r.3m' },
    { value: '6m', labelKey: 'ord.r.6m' },
    { value: `y${y}`, labelKey: 'ord.r.year', vars: { year: y } },
    { value: `y${y - 1}`, labelKey: 'ord.r.year', vars: { year: y - 1 } },
    { value: `y${y - 2}`, labelKey: 'ord.r.year', vars: { year: y - 2 } }
  ];
}

/** The start of the range (null for all orders and for a calendar year, which has its own end). */
export function rangeStart(range: OrderRange, now: Date): Date | null {
  if (range === 'all') return null;
  if (range === '3m' || range === '6m') {
    const from = new Date(now);
    from.setMonth(from.getMonth() - (range === '3m' ? 3 : 6));
    return from;
  }
  return new Date(Number(range.slice(1)), 0, 1);
}

/** True when the order was placed inside the range. */
export function inRange(placedAt: string, range: OrderRange, now: Date): boolean {
  if (range === 'all') return true;
  const at = new Date(placedAt);
  if (Number.isNaN(at.getTime())) return true;
  if (range === '3m' || range === '6m') {
    const from = new Date(now);
    from.setMonth(from.getMonth() - (range === '3m' ? 3 : 6));
    return at >= from;
  }
  return at.getFullYear() === Number(range.slice(1));
}
