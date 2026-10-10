/* Order tracking view-model (S166). */

export const TRACKING_STEPS = [
  { key: 'placed', title: 'Order placed', detail: 'Payment confirmed' },
  { key: 'packed', title: 'Packed', detail: 'Packed at the fulfilment centre' },
  { key: 'picked_up', title: 'Picked up', detail: 'AfroTransact Delivery' },
  { key: 'out_for_delivery', title: 'Out for delivery', detail: 'On the way to you' },
  { key: 'delivered', title: 'Delivered', detail: 'Photo + code confirmation' }
] as const;

export type TrackingStepKey = (typeof TRACKING_STEPS)[number]['key'];

const STATUS_TO_STEP: Record<string, number> = {
  placed: 0, pending: 0, confirmed: 0, paid: 0,
  picking: 1, packed: 1, ready: 1, ready_to_ship: 1,
  picked_up: 2, shipped: 2, in_transit: 2, label_created: 2,
  out_for_delivery: 3,
  delivered: 4
};

/** Index of the current step for a shipment status from orders-service (unknown statuses stay at "placed"). */
export function stepIndex(status: string): number {
  return STATUS_TO_STEP[status.toLowerCase().replace(/[\s-]/g, '_')] ?? 0;
}

export function headline(status: string, eta?: string | null): string {
  const i = stepIndex(status);
  if (i >= 4) return 'Delivered';
  const when = eta ? eta.charAt(0).toLowerCase() + eta.slice(1) : '';
  if (i === 3) return when ? (/^today/.test(when) ? `Arriving ${when}` : `Arriving today, ${when}`) : 'Out for delivery';
  return when ? `Arriving ${when}` : 'Preparing your order';
}

export interface ShipmentForCode {
  method: 'afrotransact_delivery' | 'carrier' | 'express' | 'pickup';
  handover: 'hand' | 'door' | 'desk' | null;
  perishable: boolean;
  deliveryCode: string | null;
  status: string;
}

/** The 4-digit handover code is shown for hand-delivery and perishables on AfroTransact Delivery, until delivered. */
export function showDeliveryCode(s: ShipmentForCode): boolean {
  if (s.method !== 'afrotransact_delivery' || !s.deliveryCode) return false;
  if (stepIndex(s.status) >= 4) return false;
  return s.handover === 'hand' || s.perishable;
}
