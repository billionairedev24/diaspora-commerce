import { type DeliveryRow, type DeliveryStatus } from '@afrotransact/api-client';

import { type MessageKey } from '@/i18n/t';

/** The tabs of the queue: what AfroTransact holds now, what was delivered, and what the seller or warehouse is still preparing. */
export const DELIVERY_TABS = ['active', 'delivered', 'preparing'] as const;
export type DeliveryTab = (typeof DELIVERY_TABS)[number];

export const TAB_STATUSES: Record<DeliveryTab, readonly DeliveryStatus[]> = {
  active: ['ready', 'picked_up', 'in_transit', 'out_for_delivery', 'delivery_failed'],
  delivered: ['delivered'],
  preparing: ['placed', 'confirmed', 'packed']
};

export const tabOf = (value: string | null): DeliveryTab => (DELIVERY_TABS as readonly string[]).includes(value ?? '') ? (value as DeliveryTab) : 'active';

export const STATUS_LABEL: Record<DeliveryStatus, MessageKey> = {
  placed: 'st.dlv.status.placed',
  confirmed: 'st.dlv.status.confirmed',
  packed: 'st.dlv.status.packed',
  ready: 'st.dlv.status.ready',
  picked_up: 'st.dlv.status.picked_up',
  in_transit: 'st.dlv.status.in_transit',
  out_for_delivery: 'st.dlv.status.out_for_delivery',
  delivery_failed: 'st.dlv.status.delivery_failed',
  delivered: 'st.dlv.status.delivered',
  returned: 'st.dlv.status.returned'
};

export const STATUS_TONE: Record<DeliveryStatus, 'good' | 'warn' | 'bad' | 'info' | 'brand'> = {
  placed: 'info',
  confirmed: 'info',
  packed: 'info',
  ready: 'brand',
  picked_up: 'warn',
  in_transit: 'warn',
  out_for_delivery: 'warn',
  delivery_failed: 'bad',
  delivered: 'good',
  returned: 'warn'
};

export const AUDIT_LABEL: Record<string, MessageKey> = {
  TAKE: 'st.dlv.audit.TAKE',
  ASSIGN: 'st.dlv.audit.ASSIGN',
  REASSIGN: 'st.dlv.audit.REASSIGN',
  PICKED_UP: 'st.dlv.audit.PICKED_UP',
  IN_TRANSIT: 'st.dlv.audit.IN_TRANSIT',
  RETURNED: 'st.dlv.audit.RETURNED',
  OUT_FOR_DELIVERY: 'st.dlv.audit.OUT_FOR_DELIVERY',
  DELIVERED: 'st.dlv.audit.DELIVERED',
  FAILED: 'st.dlv.audit.FAILED',
  REATTEMPT: 'st.dlv.audit.REATTEMPT',
  PROOF_CORRECTED: 'st.dlv.audit.PROOF_CORRECTED',
  DATA_FIX: 'st.dlv.audit.DATA_FIX'
};

/** "Houston, TX" for a customer; empty parts are left out. */
export const place = (city: string, state: string): string => [city, state].filter(Boolean).join(', ');

export const customerPlace = (r: Pick<DeliveryRow, 'customerCity' | 'customerState'>): string => place(r.customerCity, r.customerState);
