/* In-memory state of the dev mock backend (per app session). Shapes follow the contracts so the real Zod-validated
   @afrotransact/api-client accepts them. */
import { type PaymentMethod } from '@afrotransact/api-client';

import { type Booking } from '../types';
import { ADDRESSES, type FixtureProduct, PAYMENT_METHODS, PRODUCTS, PROVIDERS } from './fixtures';
import { atZoned } from './zoned';

export interface MockLine { atin: string; quantity: number; selected: boolean; lineId: string }
export interface MockShipment {
  shipmentId: string;
  label: string;
  status: string;
  eta: string | null;
  method: 'afrotransact_delivery' | 'carrier' | 'express' | 'pickup';
  handover: 'hand' | 'door' | 'desk' | null;
  perishable: boolean;
  deliveryCode: string | null;
  items: Array<{ atin: string; quantity: number }>;
}
export interface MockReview { id: string; rating: number; title: string | null; text: string | null; status: 'PUBLISHED' | 'PENDING'; createdAt: string; updatedAt: string }
export interface MockOrder { orderNumber: string; placedAt: string; totalMinor: number; shipTo: string; status: string; shipments: MockShipment[] }

const daysFromNow = (d: number, h = 10) => { const x = new Date(); x.setDate(x.getDate() + d); x.setHours(h, 0, 0, 0); return x; };

function seedBookings(): Booking[] {
  const ama = PROVIDERS[0];
  const svc = ama?.services[1];
  if (!ama || !svc) return [];
  const day = daysFromNow(3);
  if (day.getUTCDay() === 0) day.setUTCDate(day.getUTCDate() + 1);
  const start = atZoned(day.toISOString().slice(0, 10), 10 * 60, ama.timeZone);
  const end = new Date(start.getTime() + svc.durationMinutes * 60_000);
  return [{
    id: '7b1c2d3e-0000-4000-8000-000000000001', reference: 'BK-4471', orgId: ama.orgId, status: 'CONFIRMED', statusLabel: 'Confirmed',
    startAt: start.toISOString(), endAt: end.toISOString(), location: ama.location, channel: 'app',
    customer: { name: 'Adaeze Okafor', email: 'adaeze@example.com', phone: '+17135550142' },
    service: { serviceId: svc.id, name: svc.name, durationMinutes: svc.durationMinutes, priceMinor: svc.priceMinor, depositMinor: svc.depositMinor, balanceMinor: svc.priceMinor - svc.depositMinor, currency: 'USD', providerName: ama.displayName, mode: 'IN_PERSON' },
    policy: { kind: ama.policy.kind, label: ama.policy.label, text: ama.policy.text },
    payment: { state: 'DEPOSIT_CAPTURED', authorizedMinor: svc.priceMinor, depositCapturedMinor: svc.depositMinor, refundedMinor: 0 },
    createdAt: daysFromNow(-2).toISOString(), version: 1
  }];
}

function seedOrders(): MockOrder[] {
  return [
    { orderNumber: 'AT-1049-2283', placedAt: daysFromNow(0, 9).toISOString(), totalMinor: 5247, shipTo: 'Adaeze Okafor, Houston TX 77032', status: 'out_for_delivery',
      shipments: [{ shipmentId: 'shp_1049_1', label: 'AfroTransact Delivery', status: 'out_for_delivery', eta: '6–8 pm', method: 'afrotransact_delivery', handover: 'hand', perishable: false, deliveryCode: '4821', items: [{ atin: 'ATBASMAT05', quantity: 1 }, { atin: 'ATSUYA0006', quantity: 2 }] }] },
    { orderNumber: 'AT-0988-1120', placedAt: '2026-09-25T15:00:00Z', totalMinor: 3498, shipTo: 'Adaeze Okafor, Houston TX 77032', status: 'delivered',
      shipments: [{ shipmentId: 'shp_0988_1', label: 'UPS Ground', status: 'delivered', eta: null, method: 'carrier', handover: null, perishable: false, deliveryCode: null, items: [{ atin: 'ATBASMAT05', quantity: 1 }, { atin: 'ATGARI0004', quantity: 1 }] }] },
    { orderNumber: 'AT-0951-7743', placedAt: '2026-09-30T15:00:00Z', totalMinor: 1800, shipTo: 'Adaeze Okafor, Houston TX 77032', status: 'refunded',
      shipments: [{ shipmentId: 'shp_0951_1', label: 'USPS Ground Advantage', status: 'returned', eta: null, method: 'carrier', handover: null, perishable: false, deliveryCode: null, items: [{ atin: 'ATKENTE008', quantity: 1 }] }] }
  ];
}

function seed() {
  return {
    lines: [
      { atin: 'ATBASMAT05', quantity: 1, selected: true, lineId: 'ln_1' },
      { atin: 'ATSUYA0006', quantity: 2, selected: true, lineId: 'ln_2' }
    ] as MockLine[],
    saved: [] as Array<{ savedId: string; atin: string }>,
    addresses: ADDRESSES.map((a) => ({ ...a })),
    paymentMethods: PAYMENT_METHODS.map((m): PaymentMethod => ({ ...m })),
    orders: seedOrders(),
    bookings: seedBookings(),
    bookingHistory: {} as Record<string, Array<{ at: string; by: string; action: string }>>,
    devices: [] as Array<{ id: string; token: string; app: string; platform: string; provider: string }>,
    /** The shopper's own product reviews by product ATIN (S324). */
    reviews: {} as Record<string, MockReview>,
    prefs: { email: true, sms: true, push: true, marketingOptIn: false, updatedAt: new Date().toISOString() },
    seq: 100
  };
}

export type MockDb = ReturnType<typeof seed>;

let db: MockDb = seed();

export function mockDb(): MockDb {
  return db;
}

export function resetMockDb(): void {
  db = seed();
}

export function productByAtin(atin: string): FixtureProduct | undefined {
  return PRODUCTS.find((x) => x.atin === atin);
}

export function nextId(prefix: string): string {
  db.seq += 1;
  return `${prefix}${db.seq}`;
}

/** Mock Stripe: the PaymentSheet in setup mode "attaches" a payment method (dev only). */
export function mockAttachPaymentMethod(kind: 'card' | 'us_bank_account', last4: string, brand: string | null): string {
  const id = nextId('pm_mock_');
  db.paymentMethods = db.paymentMethods.map((m) => ({ ...m, isDefault: false }));
  db.paymentMethods.push({
    id, type: kind, brand, last4,
    label: kind === 'card' ? `${brand ? brand[0]?.toUpperCase() + brand.slice(1) : 'Card'} •• ${last4}` : `Checking •• ${last4}`,
    expires: kind === 'card' ? '08/29' : null, isDefault: true
  });
  return id;
}
