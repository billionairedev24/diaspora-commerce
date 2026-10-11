/* In-memory state of the mock Seller Center BFF (stored on globalThis so route handlers, server components and tests
   share it). Seeded from the design prototype (backoffice-data.js, at-bookings.js, at-plans.js) relative to today. */
import { type ExportJob } from '../../schemas/exports';
import {
  type Availability,
  type BillingPaymentMethod,
  type BookingCard,
  type BookingStatus,
  type CancellationPolicyInput,
  type CategoryNode,
  type CategoryRequest,
  type EarningsRow,
  type Invoice,
  type ListingRow,
  type OrgKind,
  type OrgStatus,
  type Payout,
  type Plan,
  type ProductTypeSchema,
  type PublicPage,
  type SellerOffer,
  type SellerOrderDetail,
  type ServiceOffer,
  type ShipFromAddress,
  type ShipmentStatus,
  type Subscription,
  type TeamMember
} from '../../schemas/seller';
import { allowedBookingActions, BOOKING_LABELS, SHIPMENT_LABELS, takeHome } from './model';
import { type MockApplication } from './onboarding';

export interface MockOrg {
  id: string;
  kind: OrgKind;
  name: string;
  status: OrgStatus;
  role: string;
}

export interface MockSellerDb {
  user: { id: string; firstName: string; lastName: string; email: string };
  orgs: MockOrg[];
  activeOrgId: string;
  orders: SellerOrderDetail[];
  labels: Record<string, string>;
  listings: (ListingRow & { variants: { sku: string; quantity: number }[] })[];
  categoryRequests: CategoryRequest[];
  payouts: Payout[];
  availableMinor: number;
  /** Payments compliance hold on the seller org (past-due Stripe requirement; Seller Center shows a banner). */
  payoutsPaused: boolean;
  subscription: Record<OrgKind, Subscription>;
  paymentMethods: Record<OrgKind, BillingPaymentMethod[]>;
  invoices: Invoice[];
  team: Record<string, TeamMember[]>;
  bookings: BookingCard[];
  availability: Availability;
  services: ServiceOffer[];
  policy: Required<CancellationPolicyInput>;
  earnings: EarningsRow[];
  pages: Record<string, PublicPage>;
  /** The application per organisation (S286, S289): answers (prefilled from the account on start), Stripe, submission. */
  onboarding: Record<string, MockApplication>;
  /** Saved ship-from addresses per organisation (seller service /v1/orgs/{orgId}/ship-from-addresses). */
  shipFrom: Record<string, ShipFromAddress[]>;
  /** offers-service offers of the listings' variants: the fulfilment choice (S255). */
  offers: SellerOffer[];
  /** Export jobs (the file body is CSV text in the mock). */
  exports: (ExportJob & { owner: string; orgId: string; body: string })[];
  seq: number;
}

export const SELLER_ORG = 'org-ade-kitchen';
export const PROVIDER_ORG = 'org-ama-braiding';
export const PENDING_ORG = 'org-bonwire';

const DAY = 86_400_000;
const iso = (d: Date) => d.toISOString();

/** Deterministic pseudo-random sequence (Park–Miller) so fixtures are stable across reloads. */
function rng(seed: number) {
  let s = seed;
  return () => (s = (s * 16807) % 2147483647) / 2147483647;
}

const BUYERS = ['Adaeze Nwosu', 'Kwame Asante', 'Hanna Tesfaye', 'Priya Shah', 'Marisol García', 'Jean-Pierre Laurent', 'Fatima Hassan', 'Tunde Bakare', 'Grace Wanjiru', 'Chidi Eze'];
const CITIES = [['Houston', 'TX', '77042'], ['Edison', 'NJ', '08817'], ['Atlanta', 'GA', '30303'], ['Brooklyn', 'NY', '11216'], ['Silver Spring', 'MD', '20910'], ['Minneapolis', 'MN', '55454']] as const;
const ITEMS: [string, number][] = [['Ofada Rice, Destoned · 5 lb', 2499], ['Jollof Seasoning · 6 oz', 599], ['Shito Hot Pepper Sauce', 899], ['Berbere Spice · 8 oz', 1150], ['Palm Oil · 1 L', 1299], ['Suya Spice', 799], ['Bissap (Hibiscus) · 1 lb', 950]];

function seedOrders(now: Date): SellerOrderDetail[] {
  const r = rng(11);
  const flow: ShipmentStatus[] = ['PLACED', 'PLACED', 'CONFIRMED', 'PACKED', 'READY', 'IN_TRANSIT', 'DELIVERED', 'DELIVERED', 'CANCELLED'];
  return Array.from({ length: 36 }, (_, i) => {
    const buyer = BUYERS[i % BUYERS.length] ?? 'Customer';
    const [city, state, zip] = CITIES[i % CITIES.length] ?? CITIES[0];
    const [title, price] = ITEMS[i % ITEMS.length] ?? ['Item', 999];
    const qty = 1 + Math.floor(r() * 3);
    const status = flow[i % flow.length] ?? 'PLACED';
    const fba = i % 5 === 4;
    // AfroTransact Delivery from the store (S255): the seller packs, a driver collects; the seller fee comes off the payout.
    const local = !fba && i % 7 === 3;
    const roadMiles = 3.2 + (i % 5) * 1.7;
    const chargeMinor = Math.max(599, Math.min(2499, 499 + Math.floor(85 * roadMiles + 0.5)));
    const itemsMinor = qty * price;
    const shippingMinor = local ? chargeMinor : fba || itemsMinor >= 4900 ? 0 : 599;
    const placed = new Date(now.getTime() - (i * 7 + 2) * 3_600_000);
    const tracking = ['IN_TRANSIT', 'DELIVERED'].includes(status) ? `9400 1118 9922 ${String(3344556600 + i)}` : null;
    const commission = Math.floor((itemsMinor * 800 + 5000) / 10_000);
    const stripe = Math.floor(((itemsMinor + shippingMinor) * 290 + 5000) / 10_000) + 30;
    const deliveryFee = local ? 200 : 0;
    const keptShipping = local || fba ? 0 : shippingMinor;
    const next = fba ? [] : status === 'PLACED' ? ['CONFIRMED' as const] : status === 'CONFIRMED' ? ['PACKED' as const] : status === 'PACKED' ? ['READY' as const] : [];
    const total = itemsMinor + shippingMinor;
    return {
      orderNumber: `AT-2026-${String(400123 + i * 37).padStart(6, '0')}`,
      status: status === 'DELIVERED' ? 'COMPLETED' : status === 'CANCELLED' ? 'CANCELLED' : 'IN_PROGRESS',
      placedAt: iso(placed),
      currency: 'USD',
      buyerName: buyer,
      shippingAddress: { name: buyer, phone: '+1 713 555 0142', line1: `${9800 + i} Westheimer Rd`, line2: i % 3 === 0 ? 'Apt 12' : null, city, state, postalCode: zip, country: 'US' },
      totalMinor: total,
      shipments: [
        {
          shipmentId: `sh-${1000 + i}`,
          label: `Shipment 1 of 1 · ${fba ? 'Fulfilled by AfroTransact' : local ? 'AfroTransact Delivery from your store' : 'Ships from Ade’s Kitchen'}`,
          status,
          statusLabel: SHIPMENT_LABELS[status],
          fulfilment: fba ? 'AFROTRANSACT' : local ? 'AFROTRANSACT_DELIVERY' : 'SELLER',
          perishable: false,
          lines: [{ title, quantity: qty, unitPriceMinor: price, itemsMinor, variantAtin: `ATADEV${String(i).padStart(4, '0')}`, imageUrl: null }],
          itemsMinor,
          shippingMinor,
          totalMinor: total,
          carrier: tracking ? 'usps' : null,
          trackingNumber: tracking,
          nextActions: next,
          canBuyLabel: !fba && !local && !tracking && ['CONFIRMED', 'PACKED', 'READY'].includes(status),
          timeline: [{ status: 'PLACED', at: iso(placed), actor: 'customer' }],
          localDelivery: local
            ? { pickupLocationId: 'sfa-store', roadMiles: Math.round(roadMiles * 10) / 10, durationMinutes: Math.round(roadMiles * 2.6), customerChargeMinor: chargeMinor, sellerFeeMinor: deliveryFee }
            : null
        }
      ],
      earnings:
        status === 'CANCELLED'
          ? null
          : {
              itemsMinor,
              shippingMinor: keptShipping,
              commissionMinor: commission,
              fbaFeesMinor: fba ? 325 * qty : 0,
              deliveryFeesMinor: deliveryFee,
              stripeFeeMinor: stripe,
              netMinor: itemsMinor + keptShipping - commission - (fba ? 325 * qty : 0) - deliveryFee - stripe
            }
    };
  });
}

const PRODUCT_TITLES = [
  'Ofada Rice, Destoned · 5 lb', 'Jollof Seasoning · 6 oz', 'Shito Hot Pepper Sauce · 12 oz', 'Berbere Spice Blend · 8 oz', 'Red Palm Oil · 1 L',
  'Suya Spice · 4 oz', 'Bissap Hibiscus Flowers · 1 lb', 'Ijebu Garri · 2 lb', 'Egusi Seeds, Ground · 1 lb', 'Ogbono, Ground · 8 oz',
  'Plantain Chips, Sweet · 6-pack', 'Chin Chin · 1 lb', 'Uziza Seeds · 2 oz', 'Crayfish, Ground · 8 oz', 'Teff Flour · 2 lb', 'Shea Butter, Raw · 16 oz',
  'Black Soap · 1 lb', 'Ankara Wrap Dress with Puff Sleeves', 'Kente Graduation Stole', 'Adire Indigo Scarf', 'Bitter Leaf, Dried · 2 oz',
  'Yam Flour (Elubo) · 4 lb', 'Pounded Yam Flour · 4 lb', 'Honey Beans · 4 lb', 'Ugu Leaves, Frozen · 1 lb', 'Locust Beans (Iru) · 4 oz'
];

function seedListings(now: Date) {
  const r = rng(29);
  const statuses = ['LIVE', 'LIVE', 'LIVE', 'LIVE', 'IN_REVIEW', 'PAUSED', 'REJECTED', 'PENDING_CATEGORY_APPROVAL'] as const;
  return PRODUCT_TITLES.map((title, i) => {
    const status = statuses[i % statuses.length] ?? 'LIVE';
    const variants = [{ sku: `ADE-${String(100 + i)}-A`, quantity: Math.floor(r() * 80) }, ...(i % 3 === 0 ? [{ sku: `ADE-${String(100 + i)}-B`, quantity: Math.floor(r() * 30) }] : [])];
    return {
      productId: `prod-${i + 1}`,
      atin: status === 'LIVE' || status === 'PAUSED' ? `ATADE${String(10000 + i)}` : null,
      title,
      sku: variants[0]?.sku ?? '',
      variantCount: variants.length,
      priceMinor: 499 + Math.floor(r() * 40) * 50,
      currency: 'USD',
      stock: variants.reduce((a, v) => a + v.quantity, 0),
      fulfilment: i % 4 === 0 ? ('AFROTRANSACT' as const) : i % 4 === 2 ? ('AFROTRANSACT_DELIVERY' as const) : ('SELLER' as const),
      status,
      statusLabel: { LIVE: 'Live', IN_REVIEW: 'In review', PAUSED: 'Paused', TAKEN_DOWN: 'Taken down', REJECTED: 'Rejected', PENDING_CATEGORY_APPROVAL: 'Waiting for category approval' }[status],
      moderationNote: status === 'REJECTED' ? 'Add the full ingredient list and allergens (FALCPA).' : null,
      imageUrl: null,
      updatedAt: iso(new Date(now.getTime() - i * DAY)),
      variants
    };
  });
}

const atTime = (base: Date, dayOffset: number, hour: number, minute = 0) => {
  const d = new Date(base);
  d.setDate(d.getDate() + dayOffset);
  d.setHours(hour, minute, 0, 0);
  return d;
};

export const PROVIDER_SERVICES = [
  { id: 's1a', name: 'Knotless box braids · medium', description: 'Hair included · mid-back length', durationMinutes: 300, priceMinor: 18000, depositMinor: 4000 },
  { id: 's1b', name: 'Fulani braids', description: 'Hair included', durationMinutes: 240, priceMinor: 16000, depositMinor: 4000 },
  { id: 's1c', name: 'Cornrows · 8–10 rows', description: 'Kids welcome', durationMinutes: 120, priceMinor: 7500, depositMinor: 2000 },
  { id: 's1d', name: 'Braid takedown & wash', description: '', durationMinutes: 90, priceMinor: 5500, depositMinor: 1500 }
];

function booking(id: number, svc: (typeof PROVIDER_SERVICES)[number], start: Date, customer: string, status: BookingStatus, now: Date, note: string | null = null): BookingCard {
  const end = new Date(start.getTime() + svc.durationMinutes * 60_000);
  return {
    id: String(id), reference: `BK-${id}`, customerName: customer, serviceName: svc.name, startAt: iso(start), endAt: iso(end), durationMinutes: svc.durationMinutes,
    status, statusLabel: BOOKING_LABELS[status], priceMinor: svc.priceMinor, depositMinor: svc.depositMinor, currency: 'USD',
    depositState: status === 'REQUESTED' ? 'held' : status === 'COMPLETED' || status === 'CONFIRMED' ? 'captured' : status === 'DECLINED' || status === 'EXPIRED' ? 'released' : 'none',
    note, practitioner: 'Ama O.', expiresAt: status === 'REQUESTED' ? iso(new Date(now.getTime() + 3 * 3_600_000)) : null, proposedStartAt: null,
    allowedActions: allowedBookingActions(status, iso(start), now)
  };
}

function seedBookings(now: Date): BookingCard[] {
  const [a, b, c, d] = PROVIDER_SERVICES as [(typeof PROVIDER_SERVICES)[number], (typeof PROVIDER_SERVICES)[number], (typeof PROVIDER_SERVICES)[number], (typeof PROVIDER_SERVICES)[number]];
  const people = ['Adaeze Nwosu', 'Folake Bello', 'Chioma Eze', 'Ama Kusi', 'Zainab Bello', 'Efua Mensah', 'Grace Wanjiru'];
  let id = 24000;
  const list: BookingCard[] = [];
  for (let off = -10; off <= 14; off++) {
    const day = atTime(now, off, 0);
    if (day.getDay() === 1) continue; // closed Mondays
    const svc = [a, b, c, d][(off + 20) % 4] ?? c;
    const status: BookingStatus = off === -9 || off === -4 ? 'CANCELLED_PROVIDER' : off < 0 ? (off % 5 === 0 ? 'NO_SHOW' : off % 7 === 0 ? 'CANCELLED_CUSTOMER' : 'COMPLETED') : off <= 1 ? 'CONFIRMED' : off % 3 === 0 ? 'REQUESTED' : 'CONFIRMED';
    list.push(booking(++id, svc, atTime(now, off, 10 + (off % 2)), people[(off + 21) % people.length] ?? 'Customer', status, now, off % 4 === 0 ? 'Prefers no edges; bring own hair' : null));
    if (off % 2 === 0) list.push(booking(++id, c, atTime(now, off, 15, 30), people[(off + 23) % people.length] ?? 'Customer', off >= 0 && off % 4 === 0 ? 'REQUESTED' : off < 0 ? 'COMPLETED' : 'CONFIRMED', now));
  }
  return list;
}

const PLAN_CATALOG: Record<OrgKind, { code: string; name: string; tagline: string; month: number; trial: number; features: string[] }[]> = {
  SELLER: [
    { code: 'seller_essentials', name: 'Seller Essentials', tagline: 'Start selling', month: 0, trial: 0, features: ['Up to 50 listings', '15% referral fee', 'Basic storefront', '1 user'] },
    { code: 'seller_growth', name: 'Seller Growth', tagline: 'Most popular', month: 39, trial: 30, features: ['Up to 1,000 listings', '8–12% referral fee by category', 'Storefront themes and AI listing writer', '3 users'] },
    { code: 'seller_pro', name: 'Seller Pro', tagline: 'For established brands', month: 129, trial: 14, features: ['Unlimited listings', '6–10% referral fee', 'Fulfilled by AfroTransact priority, ad credits, API', '10 users'] }
  ],
  PROVIDER: [
    { code: 'provider_solo', name: 'Provider Solo', tagline: 'Independent pros', month: 19, trial: 30, features: ['1 practitioner', '10% booking fee', 'Online booking and deposits', 'Public page with 6 photos'] },
    { code: 'provider_studio', name: 'Provider Studio', tagline: 'Most popular', month: 49, trial: 30, features: ['Up to 5 practitioners', '8% booking fee', 'Waitlist and reminders by SMS', 'Public page with 12 photos'] },
    { code: 'provider_business', name: 'Provider Business', tagline: 'Multi-location', month: 99, trial: 14, features: ['Unlimited staff', '6% booking fee', 'Multiple locations and API', 'Public page with 24 photos'] }
  ]
};

const usd = (minor: number) => `$${(minor / 100).toLocaleString('en-US', { minimumFractionDigits: minor % 100 ? 2 : 0, maximumFractionDigits: 2 })}`;

/** Plans with monthly, quarterly (5% off) and annual (2 months free) prices, as the seller service computes them. */
export function plansFor(kind: OrgKind, currentCode: string): Plan[] {
  return PLAN_CATALOG[kind].map((p) => {
    const m = p.month * 100;
    const prices = (
      [
        ['MONTHLY', 1, m],
        ['QUARTERLY', 3, Math.round(m * 3 * 0.95)],
        ['ANNUAL', 12, m * 10]
      ] as const
    ).map(([cadence, months, total]) => {
      const saving = m * months - total;
      return {
        cadence, months, totalMinor: total, perMonthMinor: Math.round(total / months), savingMinor: saving,
        perMonthLabel: m === 0 ? 'Free' : `${usd(Math.round(total / months))}/mo`,
        billedLabel: m === 0 ? 'No subscription fee' : `Billed ${usd(total)} ${months === 1 ? 'every month' : months === 3 ? 'every 3 months' : 'every year'}`,
        savingLabel: saving > 0 ? `Save ${usd(saving)} vs monthly` : ''
      };
    });
    return { code: p.code, name: p.name, tagline: p.tagline, monthlyMinor: m, trialDays: p.trial, features: p.features, prices, current: p.code === currentCode };
  });
}

export const CATEGORY_TREE: CategoryNode[] = [
  { id: 'dep-groceries', name: 'Groceries', level: 'DEPARTMENT', productType: null, path: 'Groceries', children: [
    { id: 'cat-pantry', name: 'Pantry', level: 'CATEGORY', productType: null, path: 'Groceries › Pantry', children: [
      { id: 'pt-grains', name: 'Rice, grains & flours', level: 'PRODUCT_TYPE', productType: 'grocery', path: 'Groceries › Pantry › Rice, grains & flours', children: [] },
      { id: 'pt-spices', name: 'Spices & seasonings', level: 'PRODUCT_TYPE', productType: 'grocery', path: 'Groceries › Pantry › Spices & seasonings', children: [] }
    ] },
    { id: 'cat-frozen', name: 'Frozen & chilled', level: 'CATEGORY', productType: null, path: 'Groceries › Frozen & chilled', children: [
      { id: 'pt-frozen', name: 'Frozen vegetables & fish', level: 'PRODUCT_TYPE', productType: 'perishable', path: 'Groceries › Frozen & chilled › Frozen vegetables & fish', children: [] }
    ] }
  ] },
  { id: 'dep-fashion', name: 'Fashion', level: 'DEPARTMENT', productType: null, path: 'Fashion', children: [
    { id: 'cat-women', name: 'Women', level: 'CATEGORY', productType: null, path: 'Fashion › Women', children: [
      { id: 'pt-dresses', name: 'Dresses', level: 'PRODUCT_TYPE', productType: 'dress', path: 'Fashion › Women › Dresses', children: [] }
    ] }
  ] },
  { id: 'dep-beauty', name: 'Beauty', level: 'DEPARTMENT', productType: null, path: 'Beauty', children: [
    { id: 'cat-skin', name: 'Skin & body', level: 'CATEGORY', productType: null, path: 'Beauty › Skin & body', children: [
      { id: 'pt-butters', name: 'Butters & oils', level: 'PRODUCT_TYPE', productType: 'beauty', path: 'Beauty › Skin & body › Butters & oils', children: [] }
    ] }
  ] },
  { id: 'dep-books', name: 'Books', level: 'DEPARTMENT', productType: null, path: 'Books', children: [
    { id: 'cat-cookbooks', name: 'Cookbooks', level: 'CATEGORY', productType: null, path: 'Books › Cookbooks', children: [
      { id: 'pt-cookbooks', name: 'African & Caribbean cooking', level: 'PRODUCT_TYPE', productType: 'book', path: 'Books › Cookbooks › African & Caribbean cooking', children: [] }
    ] }
  ] }
];

const COLORS = ['Black', 'White', 'Gold', 'Royal blue', 'Emerald', 'Wine'];

export const PRODUCT_TYPES: Record<string, ProductTypeSchema> = {
  grocery: { code: 'grocery', label: 'packaged food', identifierRule: 'gtin', attributes: [
    { key: 'brand', label: 'Brand', options: [], required: true, multiline: false },
    { key: 'ingredients', label: 'Ingredients', options: [], required: true, multiline: true },
    { key: 'allergens', label: 'Allergens (FALCPA)', options: ['None', 'Contains peanuts', 'Contains shellfish', 'Contains fish', 'Contains wheat', 'Contains soy'], required: true, multiline: false },
    { key: 'storage', label: 'Storage', options: ['Room temperature', 'Refrigerate after opening'], required: true, multiline: false },
    { key: 'origin', label: 'Country of origin', options: [], required: true, multiline: false }
  ], themes: [{ name: 'Net weight', dimensions: [{ name: 'Net weight', options: ['8 oz', '1 lb', '2 lb', '5 lb', '10 lb'] }] }, { name: 'Net weight × Pack', dimensions: [{ name: 'Net weight', options: ['1 lb', '5 lb'] }, { name: 'Pack', options: ['1', '2', '4'] }] }] },
  perishable: { code: 'perishable', label: 'frozen & perishable food', identifierRule: 'gtin', attributes: [
    { key: 'brand', label: 'Brand', options: [], required: true, multiline: false },
    { key: 'ingredients', label: 'Ingredients', options: [], required: true, multiline: true },
    { key: 'temp', label: 'Storage temperature', options: ['Frozen (≤ 0°F)', 'Chilled (33–40°F)'], required: true, multiline: false },
    { key: 'permit', label: 'Food facility / permit no.', options: [], required: true, multiline: false }
  ], themes: [{ name: 'Net weight', dimensions: [{ name: 'Net weight', options: ['1 lb', '2 lb', '5 lb'] }] }] },
  dress: { code: 'dress', label: 'women’s dresses', identifierRule: 'gtin', attributes: [
    { key: 'brand', label: 'Brand', options: [], required: true, multiline: false },
    { key: 'material', label: 'Material', options: ['Cotton wax print', 'Cotton', 'Lace', 'Silk', 'Polyester blend'], required: true, multiline: false },
    { key: 'fit', label: 'Fit', options: ['Regular', 'Fitted', 'Relaxed', 'Plus size'], required: true, multiline: false },
    { key: 'length', label: 'Length', options: ['Mini', 'Knee', 'Midi', 'Maxi'], required: true, multiline: false },
    { key: 'care', label: 'Care', options: [], required: true, multiline: false }
  ], themes: [{ name: 'Size × Color', dimensions: [{ name: 'Size', options: ['XS', 'S', 'M', 'L', 'XL', '2XL'] }, { name: 'Color', options: COLORS }] }, { name: 'Size', dimensions: [{ name: 'Size', options: ['XS', 'S', 'M', 'L', 'XL', '2XL'] }] }] },
  beauty: { code: 'beauty', label: 'beauty & personal care', identifierRule: 'gtin', attributes: [
    { key: 'brand', label: 'Brand', options: [], required: true, multiline: false },
    { key: 'inci', label: 'Ingredients (INCI)', options: [], required: true, multiline: true },
    { key: 'skin', label: 'Skin / hair type', options: ['All', 'Dry', 'Oily', 'Sensitive'], required: true, multiline: false },
    { key: 'mocra', label: 'MoCRA product listing no.', options: [], required: true, multiline: false }
  ], themes: [{ name: 'Size', dimensions: [{ name: 'Size', options: ['4 oz', '8 oz', '16 oz'] }] }] },
  book: { code: 'book', label: 'books', identifierRule: 'isbn', attributes: [
    { key: 'author', label: 'Author', options: [], required: true, multiline: false },
    { key: 'publisher', label: 'Publisher', options: [], required: true, multiline: false },
    { key: 'language', label: 'Language', options: ['English', 'French', 'Yoruba', 'Swahili', 'Amharic'], required: true, multiline: false }
  ], themes: [{ name: 'Format', dimensions: [{ name: 'Format', options: ['Hardcover', 'Paperback'] }] }] },
  custom: { code: 'custom', label: 'your requested category', identifierRule: 'gtin', attributes: [
    { key: 'brand', label: 'Brand', options: [], required: true, multiline: false },
    { key: 'material', label: 'Material / main ingredient', options: [], required: true, multiline: false },
    { key: 'details', label: 'Key details shoppers need', options: [], required: true, multiline: true }
  ], themes: [{ name: 'Option', dimensions: [{ name: 'Option', options: ['Standard', 'Small', 'Medium', 'Large'] }] }, { name: 'Size × Color', dimensions: [{ name: 'Size', options: ['S', 'M', 'L', 'XL'] }, { name: 'Color', options: ['Black', 'White', 'Gold', 'Multicolour'] }] }] }
};

/** One offer per listing variant (offers-service creates the seller's offers from catalog.product.created). */
function seedOffers(listings: MockSellerDb['listings']): SellerOffer[] {
  return listings.flatMap((l) =>
    l.variants.map((v, i) => ({
      offerId: `of-${l.productId}-${i + 1}`,
      atin: `${l.atin ?? 'ATPENDING'}${i ? `-${i + 1}` : ''}`,
      productId: l.productId,
      sellerSku: v.sku,
      condition: 'NEW',
      priceMinor: l.priceMinor,
      currency: 'USD',
      fulfilment: l.fulfilment,
      quantity: l.fulfilment === 'AFROTRANSACT' ? 0 : v.quantity,
      availableQty: v.quantity,
      shipFromLocationId: l.fulfilment === 'AFROTRANSACT_DELIVERY' ? 'sfa-store' : null,
      shipsFrom: l.fulfilment === 'AFROTRANSACT' ? null : 'Houston, TX',
      status: l.status === 'PAUSED' ? ('PAUSED' as const) : ('ACTIVE' as const),
      version: 1
    }))
  );
}

function freshDb(now: Date): MockSellerDb {
  const db: MockSellerDb = {
    user: { id: 'user-titilayo', firstName: 'Titilayo', lastName: 'Adebayo', email: 'titilayo@adeskitchen.com' },
    orgs: [
      { id: SELLER_ORG, kind: 'SELLER', name: 'Ade’s Kitchen', status: 'APPROVED', role: 'seller.owner' },
      { id: PROVIDER_ORG, kind: 'PROVIDER', name: 'Ama’s Braiding Studio', status: 'APPROVED', role: 'provider.owner' },
      { id: PENDING_ORG, kind: 'SELLER', name: 'Bonwire Weavers', status: 'INFO_REQUESTED', role: 'seller.owner' }
    ],
    activeOrgId: SELLER_ORG,
    onboarding: {},
    orders: seedOrders(now),
    labels: {},
    exports: [],
    listings: seedListings(now),
    categoryRequests: [{ id: 'cr-1', reference: 'REQ-4821', path: 'Fashion › Women › Bridal aso-ebi sets', status: 'PENDING_REVIEW', decisionNote: null, createdAt: iso(new Date(now.getTime() - 2 * DAY)) }],
    payouts: [
      { id: 'po_77012', amountMinor: 93210, currency: 'USD', status: 'PAID', requestedAt: iso(new Date(now.getTime() - 7 * DAY)), holdReason: null, failureMessage: null },
      { id: 'po_77005', amountMinor: 128800, currency: 'USD', status: 'PAID', requestedAt: iso(new Date(now.getTime() - 14 * DAY)), holdReason: null, failureMessage: null },
      { id: 'po_76998', amountMinor: 41500, currency: 'USD', status: 'HELD', requestedAt: iso(new Date(now.getTime() - 20 * DAY)), holdReason: 'Chargeback review on AT-2026-399870', failureMessage: null }
    ],
    availableMinor: 128455,
    payoutsPaused: true,
    subscription: {
      SELLER: { planCode: 'seller_growth', planName: 'Seller Growth', cadence: 'QUARTERLY', status: 'ACTIVE', priceLabel: '$37.05/mo', billedLabel: 'Billed $111.15 every 3 months',
        trialEndsAt: null, currentPeriodEnd: iso(new Date(now.getTime() + 92 * DAY)), nextChargeAt: iso(new Date(now.getTime() + 92 * DAY)), nextChargeMinor: 11115,
        nextChargeLabel: `$111.15 on ${new Date(now.getTime() + 92 * DAY).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })}`, cancelAtPeriodEnd: false, pendingChange: null, dunning: null },
      PROVIDER: { planCode: 'provider_studio', planName: 'Provider Studio', cadence: 'MONTHLY', status: 'TRIALING', priceLabel: '$49/mo', billedLabel: 'Billed $49 every month',
        trialEndsAt: iso(new Date(now.getTime() + 10 * DAY)), currentPeriodEnd: iso(new Date(now.getTime() + 10 * DAY)), nextChargeAt: iso(new Date(now.getTime() + 10 * DAY)), nextChargeMinor: 4900,
        nextChargeLabel: 'Free for 30 days. First charge of $49 when the trial ends', cancelAtPeriodEnd: false, pendingChange: null, dunning: null }
    },
    paymentMethods: {
      SELLER: [
        { id: 'pm_s1', kind: 'card', brand: 'visa', last4: '4242', expMonth: 8, expYear: 2028, bankName: null, accountType: null, verified: true, receivesPayouts: false, isDefault: true, label: 'Visa ending in 4242' },
        { id: 'pm_s2', kind: 'bank', brand: null, last4: '4417', expMonth: null, expYear: null, bankName: 'Chase', accountType: 'Business checking', verified: true, receivesPayouts: true, isDefault: false, label: 'Chase ••4417' }
      ],
      PROVIDER: [
        { id: 'pm_p1', kind: 'bank', brand: null, last4: '0931', expMonth: null, expYear: null, bankName: 'Navy Federal Credit Union', accountType: 'Checking', verified: true, receivesPayouts: true, isDefault: true, label: 'Navy Federal ••0931' },
        { id: 'pm_p2', kind: 'card', brand: 'mastercard', last4: '5454', expMonth: 3, expYear: 2027, bankName: null, accountType: null, verified: true, receivesPayouts: false, isDefault: false, label: 'Mastercard ending in 5454' }
      ]
    },
    invoices: [0, 1, 2].map((i) => ({
      id: `in_${3 - i}`, number: `AT-${String(1003 - i)}`, periodStart: iso(new Date(now.getTime() - (i + 1) * 92 * DAY)), periodEnd: iso(new Date(now.getTime() - i * 92 * DAY)),
      amountDueMinor: i === 2 ? 0 : 11115, amountPaidMinor: i === 2 ? 0 : 11115, currency: 'USD', status: 'PAID', pdfUrl: `https://invoices.afrotransact.dev/in_${3 - i}.pdf`,
      hostedUrl: `https://invoices.afrotransact.dev/in_${3 - i}`, createdAt: iso(new Date(now.getTime() - i * 92 * DAY))
    })),
    team: {
      [SELLER_ORG]: [
        { userId: 'user-titilayo', name: 'Titilayo Adebayo', email: 'titilayo@adeskitchen.com', roleKey: 'seller.owner', roleLabel: 'Owner', status: 'ACTIVE', isYou: true },
        { userId: 'user-kemi', name: 'Kemi Ade', email: 'kemi@adeskitchen.com', roleKey: 'seller.fulfilment', roleLabel: 'Fulfilment', status: 'ACTIVE', isYou: false },
        { userId: 'user-bayo', name: 'Bayo Ojo', email: 'bayo@adeskitchen.com', roleKey: 'seller.finance', roleLabel: 'Finance', status: 'ACTIVE', isYou: false },
        { userId: 'user-ife', name: 'Ife Bello', email: 'ife@adeskitchen.com', roleKey: 'seller.catalog', roleLabel: 'Catalog manager', status: 'ACTIVE', isYou: false },
        { userId: 'invite:6d0f3c52-1b7e-4b8a-9a51-0c2f4e7d9b11', name: 'tunde@adeskitchen.com', email: 'tunde@adeskitchen.com', roleKey: 'seller.fulfilment',
          roleLabel: 'Fulfilment', status: 'INVITED', isYou: false, invitationId: '6d0f3c52-1b7e-4b8a-9a51-0c2f4e7d9b11', expiresAt: iso(new Date(now.getTime() + 5 * DAY)) }
      ],
      [PROVIDER_ORG]: [
        { userId: 'user-titilayo', name: 'Titilayo Adebayo', email: 'titilayo@adeskitchen.com', roleKey: 'provider.owner', roleLabel: 'Owner', status: 'ACTIVE', isYou: true },
        { userId: 'user-ama', name: 'Ama Owusu', email: 'ama@amasbraids.com', roleKey: 'provider.manager', roleLabel: 'Manager', status: 'ACTIVE', isYou: false },
        { userId: 'user-efua', name: 'Efua Mensah', email: 'efua@amasbraids.com', roleKey: 'provider.practitioner', roleLabel: 'Practitioner', status: 'ACTIVE', isYou: false }
      ]
    },
    bookings: seedBookings(now),
    availability: {
      timezone: 'America/Chicago',
      weekly: [
        { day: 'MON', ranges: [] },
        { day: 'TUE', ranges: [{ start: '10:00', end: '18:00' }] },
        { day: 'WED', ranges: [{ start: '10:00', end: '18:00' }] },
        { day: 'THU', ranges: [{ start: '10:00', end: '14:00' }, { start: '14:30', end: '20:00' }] },
        { day: 'FRI', ranges: [{ start: '09:00', end: '20:00' }] },
        { day: 'SAT', ranges: [{ start: '08:00', end: '18:00' }] },
        { day: 'SUN', ranges: [{ start: '12:00', end: '17:00' }] }
      ],
      blockedDates: [{ date: atTime(now, 40, 12).toISOString().slice(0, 10), note: 'Thanksgiving' }],
      rules: { leadMinutes: 720, horizonDays: 30, bufferMinutes: 30, stepMinutes: 30, maxPerDay: 3, instantBooking: false, autoAcceptReschedule: true }
    },
    services: PROVIDER_SERVICES.map((s) => ({ ...s, currency: 'USD', active: true, takeHome: takeHome(s.priceMinor) })),
    policy: { kind: 'MODERATE', freeHours: 48, partialRefund: true, partialFromHours: 24, partialPercent: 50, noShow: 'DEPOSIT', rescheduleHours: 48, maxReschedules: 2 },
    earnings: [],
    shipFrom: {
      [SELLER_ORG]: [
        { id: 'sfa-store', label: 'Warehouse', name: 'Ade Okafor', company: 'Ade’s Kitchen', phone: '+17135550142', line1: '9800 Bissonnet St', line2: 'Unit 4',
          city: 'Houston', state: 'TX', postalCode: '77036', country: 'US', isDefault: true, version: 0,
          position: { lat: 29.6713, lng: -95.5559, precision: 'ADDRESS', geocodedAt: iso(new Date(now.getTime() - 40 * DAY)) },
          afrotransactPickup: { enabled: true, zoneName: 'Houston Metro', enabledAt: iso(new Date(now.getTime() - 20 * DAY)),
            hours: [{ day: 'MONDAY', opens: '09:00', closes: '17:00' }, { day: 'WEDNESDAY', opens: '09:00', closes: '17:00' }, { day: 'FRIDAY', opens: '09:00', closes: '15:00' }] },
          customerPickup: { enabled: true, readyHours: 3, hours: [{ day: 'TUESDAY', opens: '10:00', closes: '19:00' }, { day: 'SATURDAY', opens: '10:00', closes: '16:00' }] } },
        { id: 'sfa-katy', label: 'Katy kitchen', name: 'Ade Okafor', company: 'Ade’s Kitchen', phone: '+17135550142', line1: '23501 Cinco Ranch Blvd', line2: '',
          city: 'Katy', state: 'TX', postalCode: '77494', country: 'US', isDefault: false, version: 0,
          position: { lat: 29.7355, lng: -95.7739, precision: 'ADDRESS', geocodedAt: iso(new Date(now.getTime() - 5 * DAY)) },
          afrotransactPickup: { enabled: false, zoneName: null, enabledAt: null, hours: [] },
          customerPickup: { enabled: false, readyHours: 0, hours: [] } }
      ]
    },
    offers: [],
    pages: {
      [SELLER_ORG]: { kind: 'SELLER', slug: 'ades-kitchen', name: 'Ade’s Kitchen', tagline: 'Home-style Nigerian pantry, shipped from Houston', status: 'PUBLISHED',
        publishedAt: iso(new Date(now.getTime() - 30 * DAY)), primaryColor: '#0A8264', accentColor: '#FFD400', layout: 'CLASSIC', header: 'BAND', buttonShape: 'ROUNDED',
        gallerySize: 6, gallery: [], featured: [], social: [{ network: 'instagram', url: 'https://instagram.com/adeskitchen', status: 'APPROVED' }], publicPath: '/store/ades-kitchen' },
      [PROVIDER_ORG]: { kind: 'PROVIDER', slug: 'amas-braiding-studio', name: 'Ama’s Braiding Studio', tagline: 'Neat, long-lasting braids from a Dakar-trained stylist', status: 'DRAFT',
        publishedAt: null, primaryColor: '#FFD400', accentColor: '#067457', layout: 'GALLERY_FIRST', header: 'COVER', buttonShape: 'ROUNDED', gallerySize: 8, gallery: [],
        featured: [
          { id: 'po1', headline: 'Knotless braids that last 8 weeks', body: 'Pre-stretched, itch-free hair and a hot-water seal on every install.', serviceId: 's1a', published: true },
          { id: 'po2', headline: 'Back-to-school cornrows for kids', body: 'Gentle, neat rows in under 2 hours.', serviceId: 's1c', published: true }
        ],
        social: [{ network: 'instagram', url: 'https://instagram.com/amasbraids', status: 'APPROVED' }, { network: 'facebook', url: 'https://facebook.com/amasbraids', status: 'PENDING' }],
        publicPath: '/pro/amas-braiding-studio' }
    },
    seq: 1
  };
  db.offers = seedOffers(db.listings);
  return db;
}

const KEY = '__atSellerMockDb';
type G = typeof globalThis & { [KEY]?: MockSellerDb };

export function getSellerDb(): MockSellerDb {
  const g = globalThis as G;
  g[KEY] ??= freshDb(new Date());
  return g[KEY];
}

/** Resets the mock state (tests). */
export function resetSellerDb(now: Date = new Date()): MockSellerDb {
  const g = globalThis as G;
  g[KEY] = freshDb(now);
  return g[KEY];
}
