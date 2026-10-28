/* Per-account state of the mock BFF for the account pages, checkout, help requests, security and bookings. Lives on the
   mock database (getDb()), so resetDb() restores the fixtures. */
import { type PreorderAttribution, type PreorderConfirmation } from '../preorders';
import { type Address, type PaymentMethod } from '../schemas/account';
import { type HelpRequest } from '../schemas/help-requests';
import {
  type CustomerBooking,
  type NotificationPreferences,
  type OrderDetail,
  type SecurityOverview,
  type ServiceDetail
} from '../schemas/shopper';
import { getDb } from './db';
import { atinOf, PRODUCTS } from './fixtures/catalog';

export interface MockOrder extends OrderDetail {
  /** Lines that are food / perishables (report a problem only, within the window). */
  perishable: Set<string>;
  /** The part of the total paid with credit (S320). */
  creditMinor?: number;
  /** The card part of refunds comes back as credit (S320): staff, with a reason, chose credit; customers cannot. */
  cardRefundsAsCredit?: boolean;
}

export interface PendingCheckout {
  orderNumber: string;
  /** Null when credit paid the whole order (no card). */
  clientSecret: string | null;
  totalMinor: number;
  status: 'awaiting_payment' | 'placed';
  order: MockOrder;
  lineIds: string[];
  /** Last-touch channel sent with checkout (S273). */
  attribution?: PreorderAttribution | null;
  /** The "You're in" facts when the order holds a pre-order package. */
  preorder?: PreorderConfirmation | null;
}

export interface AccountState {
  addresses: Address[];
  paymentMethods: PaymentMethod[];
  orders: MockOrder[];
  helpRequests: HelpRequest[];
  /** Evidence photos uploaded for the next request: asset id → completed. */
  evidence: Map<string, boolean>;
  bookings: CustomerBooking[];
  security: Omit<SecurityOverview, 'passkeyRegistrationUrl'>;
  prefs: NotificationPreferences;
  checkouts: Map<string, PendingCheckout>;
  setupIntents: Set<string>;
  pendingTotp: boolean;
  pendingSms: boolean;
}

export interface ShopperState {
  accounts: Map<string, AccountState>;
  seq: number;
}

const iso = (d: Date) => d.toISOString();
const daysAgo = (n: number) => new Date(Date.now() - n * 86_400_000);
const daysAhead = (n: number) => new Date(Date.now() + n * 86_400_000);

function product(id: string) {
  const p = PRODUCTS.find((x) => x.id === id);
  if (!p) throw new Error(`fixture ${id}`);
  const o = p.offers[0];
  return { atin: atinOf(p.id), title: p.name, priceMinor: Math.round((o?.price ?? 0) * 100), seller: o?.seller ?? '', grocery: p.cat === 'Groceries' };
}

function deliveredOrder(orderNumber: string, placed: Date, items: Array<{ id: string; qty: number }>, label: string, seller: string): MockOrder {
  const lines = items.map((it, i) => {
    const p = product(it.id);
    return { lineId: `${orderNumber}-l${i + 1}`, atin: p.atin, title: p.title, imageUrl: null, quantity: it.qty, unitPriceMinor: p.priceMinor, itemsMinor: p.priceMinor * it.qty };
  });
  const itemsMinor = lines.reduce((a, l) => a + l.itemsMinor, 0);
  const taxMinor = Math.round(itemsMinor * 0.0825);
  const delivered = new Date(placed.getTime() + 2 * 86_400_000);
  return {
    orderNumber,
    placedAt: iso(placed),
    status: 'completed',
    currency: 'USD',
    itemsMinor,
    shippingMinor: 0,
    taxMinor,
    discountMinor: 0,
    totalMinor: itemsMinor + taxMinor,
    shipTo: '9800 Westheimer Rd, Houston',
    shippingAddress: { name: 'Adaeze Nwosu', line1: '9800 Westheimer Rd', line2: '', city: 'Houston', state: 'TX', zip: '77042' },
    shipments: [
      {
        shipmentId: `${orderNumber}-s1`,
        label,
        status: 'delivered',
        statusLabel: 'Delivered',
        items: lines.map((l) => `${l.title} × ${l.quantity}`).join(' · '),
        sellerName: seller,
        deliveryMethod: 'carrier',
        cancellable: false,
        carrier: 'ups',
        trackingNumber: `1Z${orderNumber.replace(/\D/g, '').slice(-8)}`,
        promisedFrom: null,
        promisedBy: iso(delivered).slice(0, 10),
        deliveredAt: iso(delivered),
        deliveryCode: null,
        lines,
        timeline: [
          { status: 'placed', label: 'Order placed', at: iso(placed) },
          { status: 'picked_up', label: 'Picked up by the carrier', at: iso(new Date(placed.getTime() + 86_400_000)) },
          { status: 'delivered', label: 'Delivered', at: iso(delivered) }
        ]
      }
    ],
    perishable: new Set(lines.filter((_, i) => product(items[i]?.id ?? '').grocery).map((l) => l.lineId))
  };
}

export const SERVICES: ServiceDetail[] = [
  {
    id: 'svc-braids-knotless', orgId: 'org_braids_ama', providerName: 'Braids by Ama', name: 'Knotless braids, mid-back',
    description: 'Lightweight knotless braids with pre-stretched hair included. Bring clean, detangled hair.', category: 'Hair & beauty',
    durationMinutes: 300, priceMinor: 22000, depositMinor: 4000, balanceMinor: 18000, currency: 'USD', modes: ['IN_PERSON', 'AT_HOME'],
    addOns: [{ id: 'wash', name: 'Wash & blow-dry', priceMinor: 2500, extraMinutes: 30 }, { id: 'beads', name: 'Beads & cuffs', priceMinor: 1500, extraMinutes: 15 }],
    policy: { kind: 'MODERATE', label: 'Moderate', text: 'Free cancellation until 48 h before; half the deposit 24–48 h before.' }, instantBooking: false,
    team: [{ id: 'm-ama', displayName: 'Ama' }, { id: 'm-efua', displayName: 'Efua' }]
  },
  {
    id: 'svc-braids-cornrows', orgId: 'org_braids_ama', providerName: 'Braids by Ama', name: 'Cornrows (6–10 rows)',
    description: 'Straight-back or design cornrows, natural hair.', category: 'Hair & beauty', durationMinutes: 90, priceMinor: 6500, depositMinor: 1500,
    balanceMinor: 5000, currency: 'USD', modes: ['IN_PERSON'], addOns: [],
    policy: { kind: 'MODERATE', label: 'Moderate', text: 'Free cancellation until 48 h before; half the deposit 24–48 h before.' }, instantBooking: true,
    team: [{ id: 'm-ama', displayName: 'Ama' }]
  },
  {
    id: 'svc-mama-put-catering', orgId: 'org_mama_put', providerName: 'Mama Put Catering', name: 'Party jollof & small chops (per 20 guests)',
    description: 'Smoky party jollof, fried plantain, puff-puff and peppered chicken, delivered hot.', category: 'Catering', durationMinutes: 120,
    priceMinor: 45000, depositMinor: 10000, balanceMinor: 35000, currency: 'USD', modes: ['AT_HOME'], addOns: [{ id: 'moimoi', name: 'Moi moi tray', priceMinor: 6000, extraMinutes: 0 }],
    policy: { kind: 'STRICT', label: 'Strict', text: 'Free cancellation until 7 days before.' }, instantBooking: false, team: [{ id: 'm-funke', displayName: 'Funke' }]
  },
  {
    id: 'svc-kente-tailor', orgId: 'org_kente_tailors', providerName: 'Kente Tailors', name: 'Agbada or kaftan fitting & sew',
    description: 'Measurements, fabric consultation and a finished outfit in two weeks.', category: 'Tailoring', durationMinutes: 60, priceMinor: 18000,
    depositMinor: 5000, balanceMinor: 13000, currency: 'USD', modes: ['IN_PERSON'], addOns: [],
    policy: { kind: 'FLEXIBLE', label: 'Flexible', text: 'Free cancellation until 24 h before.' }, instantBooking: true, team: [{ id: 'm-kojo', displayName: 'Kojo' }]
  },
  {
    id: 'svc-diaspora-tax', orgId: 'org_diaspora_tax', providerName: 'Diaspora Tax Help', name: 'Tax return review (video call)',
    description: 'A licensed preparer reviews your federal and state return, including foreign income.', category: 'Tax & legal', durationMinutes: 45,
    priceMinor: 9000, depositMinor: 2000, balanceMinor: 7000, currency: 'USD', modes: ['VIRTUAL'], addOns: [],
    policy: { kind: 'FLEXIBLE', label: 'Flexible', text: 'Free cancellation until 24 h before.' }, instantBooking: true, team: [{ id: 'm-ngozi', displayName: 'Ngozi' }]
  }
];

export const PROVIDERS: Record<string, { displayName: string; location: string; slug: string; tagline: string; layout: 'CLASSIC' | 'GALLERY_FIRST' | 'STORY'; rating: number; reviews: number }> = {
  org_braids_ama: { displayName: 'Braids by Ama', location: 'Houston, TX', slug: 'braids-by-ama', tagline: 'Protective styles that last', layout: 'GALLERY_FIRST', rating: 4.9, reviews: 212 },
  org_mama_put: { displayName: 'Mama Put Catering', location: 'Houston, TX', slug: 'mama-put', tagline: 'Owambe-ready food, delivered hot', layout: 'STORY', rating: 4.8, reviews: 96 },
  org_kente_tailors: { displayName: 'Kente Tailors', location: 'Atlanta, GA', slug: 'kente-tailors', tagline: 'Made to measure', layout: 'CLASSIC', rating: 4.7, reviews: 58 },
  org_diaspora_tax: { displayName: 'Diaspora Tax Help', location: 'Online', slug: 'diaspora-tax', tagline: 'Taxes across borders, explained', layout: 'CLASSIC', rating: 4.9, reviews: 143 }
};

export function bookingFor(svc: ServiceDetail, id: string, reference: string, status: string, start: Date, extra: Partial<CustomerBooking> = {}): CustomerBooking {
  const end = new Date(start.getTime() + svc.durationMinutes * 60_000);
  const labels: Record<string, string> = {
    PENDING_PAYMENT: 'Waiting for the deposit', REQUESTED: 'Waiting for the provider', CONFIRMED: 'Confirmed', COMPLETED: 'Completed',
    RESCHEDULE_REQUESTED: 'New time requested', RESCHEDULE_PROPOSED: 'Provider proposed a new time', CANCELLED_CUSTOMER: 'Cancelled by you',
    CANCELLED_PROVIDER: 'Cancelled by the provider', DECLINED: 'Declined'
  };
  const live = ['PENDING_PAYMENT', 'REQUESTED', 'CONFIRMED', 'RESCHEDULE_REQUESTED', 'RESCHEDULE_PROPOSED'].includes(status);
  return {
    id, reference, orgId: svc.orgId, status, statusLabel: labels[status] ?? status, startAt: iso(start), endAt: iso(end), proposedStartAt: null,
    location: svc.modes.includes('VIRTUAL') ? 'Video call' : (PROVIDERS[svc.orgId]?.location ?? ''), notes: '',
    service: { serviceId: svc.id, name: svc.name, durationMinutes: svc.durationMinutes, priceMinor: svc.priceMinor, depositMinor: svc.depositMinor,
      balanceMinor: svc.balanceMinor, currency: svc.currency, mode: svc.modes[0] ?? 'IN_PERSON', providerName: svc.providerName,
      memberName: svc.team[0]?.displayName ?? '', addOns: [] },
    policy: svc.policy,
    payment: { state: status === 'PENDING_PAYMENT' ? 'REQUIRES_PAYMENT_METHOD' : 'DEPOSIT_CAPTURED', depositCapturedMinor: status === 'PENDING_PAYMENT' ? 0 : svc.depositMinor,
      refundedMinor: 0, balanceState: 'ON_AUTHORISATION' },
    canCancel: live, canReschedule: status === 'CONFIRMED', canReview: status === 'COMPLETED', awaitingPayment: status === 'PENDING_PAYMENT',
    ...extra
  };
}

function seed(userId: string): AccountState {
  const empty: AccountState = {
    addresses: [], paymentMethods: [], orders: [], helpRequests: [], evidence: new Map(), bookings: [],
    security: { mfa: { totp: false, sms: true, smsPhone: '•••• 0142', recoveryCodesLeft: 8, required: false, mayDisable: true, mayRemoveTotp: false, mayRemoveSms: true },
      passkeys: [], trustedBrowsers: [], sessions: [{ id: 'sess-web', clientName: 'AfroTransact web · this browser', signedInAt: iso(daysAgo(0)), lastActiveAt: iso(new Date()), current: true }],
      devices: [], linkedAccounts: { accounts: [], hasPassword: true } },
    prefs: { email: true, sms: true, push: true, marketingOptIn: false },
    checkouts: new Map(), setupIntents: new Set(), pendingTotp: false, pendingSms: false
  };
  if (userId !== 'u_adaeze') return empty;
  const braids = SERVICES[0] as ServiceDetail;
  const tax = SERVICES[4] as ServiceDetail;
  return {
    ...empty,
    addresses: [
      { id: 'ad1', name: 'Adaeze Nwosu', phone: '+17135550142', line1: '9800 Westheimer Rd', line2: '', city: 'Houston', state: 'TX', zip: '77042', isDefault: true, verified: true },
      { id: 'ad2', name: 'Adaeze Nwosu (work)', phone: '+17185550199', line1: '1250 Grand Concourse', line2: 'Apt 4B', city: 'Bronx', state: 'NY', zip: '10456', isDefault: false, verified: true }
    ],
    paymentMethods: [
      { id: 'pm_visa4242', type: 'card', brand: 'visa', last4: '4242', label: 'Visa ending in 4242', expires: '08/29', isDefault: true },
      { id: 'pm_mc4444', type: 'card', brand: 'mastercard', last4: '4444', label: 'Mastercard ending in 4444', expires: '11/27', isDefault: false },
      { id: 'pm_cashapp', type: 'cashapp', brand: null, last4: null, label: 'Cash App Pay', expires: null, isDefault: false }
    ],
    orders: [
      deliveredOrder('AT-2026-104221', daysAgo(6), [{ id: 'p15', qty: 1 }, { id: 'p1', qty: 1 }], 'Shipped by AfroTransact from Houston FC', 'Tamale Shea Co-op'),
      deliveredOrder('AT-2026-100873', daysAgo(19), [{ id: 'p17', qty: 1 }], 'Shipped by Diaspora Books from Atlanta, GA', 'Diaspora Books')
    ],
    helpRequests: [
      { id: 'help-9001', reference: 'CLM-9001', orderNumber: 'AT-2026-100873', type: 'DAMAGED_ITEM', status: 'RESOLVED', statusLabel: 'Resolved',
        description: 'The cover was torn and two pages were missing when the book arrived.', evidenceCount: 2, claimedMinor: 1899, currency: 'USD',
        resolution: { outcome: 'REFUNDED', note: 'Thank you for the photos. We have refunded the book to your original payment method.', refundMinor: 1899, decidedAt: iso(daysAgo(10)) },
        createdAt: iso(daysAgo(14)), updatedAt: iso(daysAgo(10)) }
    ],
    bookings: [
      bookingFor(braids, 'bk-1001', 'BK-7Q2X', 'CONFIRMED', new Date(daysAhead(5).setUTCHours(15, 0, 0, 0))),
      bookingFor(tax, 'bk-0907', 'BK-3M8C', 'COMPLETED', new Date(daysAgo(12).setUTCHours(17, 0, 0, 0)))
    ],
    security: {
      ...empty.security,
      mfa: { totp: true, sms: true, smsPhone: '•••• 0142', recoveryCodesLeft: 8, required: false, mayDisable: true, mayRemoveTotp: true, mayRemoveSms: true },
      passkeys: [{ credentialId: 'cred-macbook', label: 'MacBook Touch ID', createdAt: iso(daysAgo(40)), lastUsedAt: iso(daysAgo(2)) }],
      sessions: [
        { id: 'sess-web', clientName: 'AfroTransact web · this browser', signedInAt: iso(daysAgo(0)), lastActiveAt: iso(new Date()), current: true },
        { id: 'sess-app', clientName: 'AfroTransact app · iPhone', signedInAt: iso(daysAgo(9)), lastActiveAt: iso(daysAgo(1)), current: false }
      ],
      devices: [{ id: 'dev-iphone', platform: 'ios', deviceName: 'Adaeze’s iPhone', lastSeenAt: iso(daysAgo(1)) }],
      linkedAccounts: { accounts: [{ provider: 'google', email: 'adaeze@example.com', canUnlink: true }], hasPassword: true }
    }
  };
}

type Holder = { shopper?: ShopperState };

export function shopperState(): ShopperState {
  const db = getDb() as unknown as Holder;
  db.shopper ??= { accounts: new Map(), seq: 1000 };
  return db.shopper;
}

export function account(userId: string): AccountState {
  const s = shopperState();
  let a = s.accounts.get(userId);
  if (!a) {
    a = seed(userId);
    s.accounts.set(userId, a);
  }
  return a;
}

export function seq(): number {
  const s = shopperState();
  s.seq += 1;
  return s.seq;
}

/** Account-menu badges from the mock state. */
export function accountCounts(userId: string) {
  const a = account(userId);
  return {
    orders: a.orders.length,
    addresses: a.addresses.length,
    paymentMethods: a.paymentMethods.length
  };
}
