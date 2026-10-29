/* In-memory state of the mock staff BFF (stored on globalThis so the route handler, server components and tests share
   it). Seeded from the Backoffice prototype (backoffice-data.js, backoffice-config.js, at-site.js) relative to today. */
import { type ExportJob } from '../../schemas/exports';
import { legalGroupTitle } from '../../schemas/legal';
import { type ActivityEntry, type ResourceKey } from '../../schemas/staff';
import { HELP_ARTICLES } from '../fixtures/help';
import { type MockContentState, seedContent } from './content-pages';
import { type MockDispatch, seedDispatch } from './dispatch';
import { seedExchangeRates } from './exchange-rates';
import { type MockFeeRatesState, seedFeeRates } from './fee-rates';
import { feeRecords, type MockFeeSchedule, seedFeeSchedules } from './fees';
import { seedIdentityAdmin } from './identity';
import { type MockMarketsState, seedMarkets } from './markets';
import { tierBenefitDetails } from './membership-tiers';
import { type MockTemplateRow, type MockTemplateSample, seedTemplateRows, seedTemplateSamples } from './notification-templates';
import { type MockPlansState, seedPlans } from './plans';
import { type MockPreorderState, seedPreorders } from './preorders';
import { type MockTaxonomyState, seedCategoryRequests, seedTaxonomy } from './taxonomy';

export type Scalar = string | number | boolean | null;

export interface MockRecord {
  id: string;
  fields: Record<string, Scalar>;
  activity: ActivityEntry[];
  /** Nested rows shown as a table on the sheet (order shipments, PO lines, route stops…). */
  lines?: Record<string, Scalar>[];
}

export interface MockNotification {
  id: string;
  recipientId: string;
  channel: string;
  templateKey: string;
  subject: string | null;
  status: string;
  address: string;
  createdAt: string;
}

export interface MockStaffDb {
  now: Date;
  /** Messages sent to Lola today. */
  lolaUsed: number;
  user: { id: string; firstName: string; lastName: string; email: string };
  records: Record<ResourceKey, MockRecord[]>;
  notifications: MockNotification[];
  exports: (ExportJob & { owner: string; body: string })[];
  audit: { at: string; actor: string; action: string; summary: string; entityId: string }[];
  /** payments' fee-schedule versions (the fee-schedules table is derived from them). */
  feeSchedules: MockFeeSchedule[];
  /** fulfilment's seller pickups (AfroTransact Delivery from the seller's store, S255). */
  dispatch: MockDispatch;
  /** notifications' versioned templates and their sample data (S268). */
  templates: { rows: MockTemplateRow[]; samples: Record<string, MockTemplateSample> };
  /** delivery-runs' pre-order windows, orders, coverage and settings (S274). */
  preorders: MockPreorderState;
  /** content's draft markets: countries → states → cities (Markets & locations). */
  markets: MockMarketsState;
  /** content's legal and help authoring: versions, revisions and working drafts (Settings → Published pages). */
  content: MockContentState;
  /** catalog's category tree, product-type details and the audit of changes (Catalog → Categories). */
  taxonomy: MockTaxonomyState;
  /** seller's plan catalogue, trial campaigns, reward rules, grants and per-seller billing (S292–S297). */
  plans: MockPlansState;
  /** payments' negotiated commission / connection-fee rates and organisations' plans. */
  feeRates: MockFeeRatesState;
  seq: number;
}

const KEY = '__afrotransactStaffMockDb';
type G = typeof globalThis & { [KEY]?: MockStaffDb };

const PEOPLE = ['Amara Nwosu', 'Kwame Mensah', 'Fatou Diallo', 'Chidi Okeke', 'Abena Owusu', 'Tunde Bakare', 'Zainab Sesay', 'Yaw Boateng', 'Ngozi Eze', 'Kofi Annan'];
const SELLERS = ["Ade's Kitchen", 'Kente Naturals', "Mama Tee's Pantry", 'Lagos Threads', 'Accra Spice Co.', 'Nile Beauty'];
const CATEGORIES = ['Groceries & spices', 'Fashion & fabrics', 'Beauty & hair', 'Home & art', 'Books & media', 'Health'];

function seed(now: Date): MockStaffDb {
  let s = 97;
  const rnd = () => (s = (s * 16807) % 2147483647) / 2147483647;
  const pick = <T>(a: readonly T[]): T => a[Math.floor(rnd() * a.length)] as T;
  const ago = (days: number, hours = 0) => new Date(now.getTime() - days * 86_400_000 - hours * 3_600_000).toISOString();
  const ahead = (days: number) => new Date(now.getTime() + days * 86_400_000).toISOString();
  const day = (days: number) => ago(days).slice(0, 10);
  const act = (at: string, actor: string, text: string): ActivityEntry => ({ at, actor, text });
  const rec = (id: string, fields: Record<string, Scalar>, activity: ActivityEntry[] = [], lines?: Record<string, Scalar>[]): MockRecord => ({ id, fields, activity, lines });

  const customers = PEOPLE.map((name, i) => ({ id: `cus-${1000 + i}`, name, email: `${name.split(' ')[0]?.toLowerCase()}@example.com` }));

  const users: MockRecord[] = [
    ...customers.map((c, i) =>
      rec(c.id, { name: c.name, email: c.email, principalType: 'CUSTOMER', roles: 'account.customer', enabled: i !== 7, status: i !== 7 ? 'ACTIVE' : 'DISABLED', emailVerified: i % 4 !== 3, driverAppAccess: false }, [
        act(ago(120 - i * 9), c.name, 'Account created')
      ])
    ),
    ...[
      ['stf-1', 'Ifeoma Okafor', 'ifeoma@afrotransact.com', 'platform.super_admin'],
      ['stf-2', 'Sara Tesfaye', 'sara@afrotransact.com', 'platform.trust_safety'],
      ['stf-3', 'Daniel Mensah', 'daniel@afrotransact.com', 'platform.operations_manager'],
      ['stf-4', 'Grace Adjei', 'grace@afrotransact.com', 'platform.customer_support'],
      ['stf-5', 'Elena Petrova', 'elena@afrotransact.com', 'platform.developer'],
      ['stf-6', 'Musa Bello', 'musa@afrotransact.com', 'platform.finance']
    ].map(([id, name, email, role], i) =>
      rec(id as string, { name: name as string, email: email as string, principalType: 'STAFF', roles: role as string, enabled: true, status: 'ACTIVE', emailVerified: true, driverAppAccess: false }, [
        act(ago(300 - i * 20), 'Ifeoma Okafor', `Assigned role ${role as string}`)
      ])
    )
  ];

  const roles: MockRecord[] = [
    ['platform.super_admin', 'Super Admin', 'Everything, including access control'],
    ['platform.operations_manager', 'Operations Manager', 'Orders, applications, fulfilment and delivery operations'],
    ['platform.trust_safety', 'Trust & Safety', 'Application review, listing moderation, disputes and payout holds'],
    ['platform.finance', 'Finance', 'Payouts, ledger, fees and refunds'],
    ['platform.customer_support', 'Customer Support', 'Customer lookups, orders, refunds and returns'],
    ['platform.marketing', 'Marketing', 'Ads, announcements, coupons and site content'],
    ['platform.auditor', 'Auditor (read-only)', 'Read-only access everywhere, including access control']
  ].map(([key, name, description]) => rec(key as string, { key: key as string, name: name as string, portal: 'PLATFORM', description: description as string }));

  const appStatus = ['PENDING_REVIEW', 'PENDING_REVIEW', 'FLAGGED', 'AWAITING_FINAL_APPROVAL', 'INFO_REQUESTED', 'APPROVED', 'REJECTED', 'PENDING_REVIEW'];
  const applications = appStatus.map((status, i) => {
    const name = [...SELLERS, "Ama's Braiding Studio", 'Jollof Republic Catering'][i] ?? `Applicant ${i}`;
    return rec(
      `app-${200 + i}`,
      {
        reference: `APP-${4410 + i}`,
        displayName: name,
        legalName: `${name} LLC`,
        kind: i === 6 || i === 7 ? 'PROVIDER' : 'SELLER',
        entityType: pick(['SINGLE_MEMBER_LLC', 'SOLE_PROPRIETOR', 'CORPORATION']),
        state: pick(['TX', 'NJ', 'GA', 'MD', 'MN']),
        planCode: pick(['STARTER', 'GROWTH', 'PRO']),
        status,
        reviewStep: status === 'AWAITING_FINAL_APPROVAL' ? 2 : 1,
        reviewerId: status === 'AWAITING_FINAL_APPROVAL' ? 'stf-2' : null,
        reviewerName: status === 'AWAITING_FINAL_APPROVAL' ? 'Sara Tesfaye' : null,
        failedChecks: status === 'FLAGGED' ? 'TIN_MISMATCH' : null,
        taxIdMasked: `***-**-${String(1000 + i * 37).slice(-4)}`,
        contactEmail: `owner${i}@example.com`,
        submittedAt: ago(9 - i, 3)
      },
      [act(ago(9 - i, 3), name, 'Application submitted'), ...(status === 'AWAITING_FINAL_APPROVAL' ? [act(ago(1), 'Sara Tesfaye', 'Step 1 approved — awaiting final approval')] : [])]
    );
  });

  const shipStatus = ['PLACED', 'CONFIRMED', 'PACKED', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'DELIVERED', 'DELIVERED', 'CANCELLED', 'DELIVERY_FAILED'];
  const orders = Array.from({ length: 42 }, (_, i) => {
    const c = customers[i % customers.length] ?? { id: 'cus-1000', name: 'Amara Nwosu' };
    const status = shipStatus[i % shipStatus.length] ?? 'PLACED';
    const items = 1800 + Math.round(rnd() * 14_000);
    const shipping = i % 3 === 0 ? 0 : 599;
    const tax = Math.round(items * 0.0825);
    const placed = ago(Math.floor(i * 0.7), i % 12);
    return rec(
      `AT-${(200400 + i * 7).toString()}`,
      {
        orderNumber: `AT-${(200400 + i * 7).toString()}`,
        customerId: c.id,
        customer: c.name,
        status: status === 'CANCELLED' ? 'CANCELLED' : status === 'DELIVERED' ? 'COMPLETED' : 'OPEN',
        shipmentStatus: status,
        seller: pick(SELLERS),
        department: pick(CATEGORIES),
        itemsMinor: items,
        shippingMinor: shipping,
        taxMinor: tax,
        totalMinor: items + shipping + tax,
        currency: 'USD',
        shipTo: pick(['Houston, TX 77042', 'Edison, NJ 08817', 'Atlanta, GA 30303', 'Silver Spring, MD 20910']),
        placedAt: placed
      },
      [act(placed, c.name, 'Order placed'), ...(status !== 'PLACED' ? [act(ago(Math.floor(i * 0.7) - 0.2), 'Seller', `Shipment ${status.toLowerCase().replace(/_/g, ' ')}`)] : [])],
      [
        { id: `shp-${i}-1`, label: 'Package 1', status, carrier: status === 'PLACED' ? null : 'UPS', trackingNumber: status === 'PLACED' ? null : `1Z${String(9_000_000 + i * 13)}`, cancellable: ['PLACED', 'CONFIRMED'].includes(status), totalMinor: items + shipping + tax }
      ]
    );
  });

  const products = Array.from({ length: 14 }, (_, i) =>
    rec(`prd-${300 + i}`, {
      atin: `AT${String(80_000_000 + i * 977)}`,
      title: pick(['Jollof seasoning 200 g', 'Kente print tote', 'Shea butter, raw 500 g', 'Ankara headwrap', 'Suya spice 100 g', 'Black soap bar', 'Palm oil 1 L', 'Adire throw pillow']),
      seller: pick(SELLERS),
      productType: pick(['SPICE_BLEND', 'BAG', 'SKIN_CARE', 'APPAREL_ACCESSORY']),
      status: (['IN_REVIEW', 'IN_REVIEW', 'LIVE', 'LIVE', 'LIVE', 'REJECTED', 'TAKEN_DOWN', 'PAUSED'] as const)[i % 8] ?? 'LIVE',
      moderationNote: i % 8 === 5 ? 'Image shows a competitor logo' : null,
      createdAt: ago(20 - i)
    })
  );

  const moderation = Array.from({ length: 9 }, (_, i) =>
    rec(`mod-${400 + i}`, {
      title: pick(['Herbal tea "cures malaria"', 'Shea butter, raw 500 g', 'Kente print tote', 'Bitter kola 50 g', 'Hair growth oil']),
      seller: pick(SELLERS),
      type: i % 3 === 0 ? 'LIVE_LISTING' : 'NEW_LISTING',
      priority: i % 4 === 0 ? 'HIGH' : 'NORMAL',
      status: (['PENDING', 'PENDING', 'ESCALATED', 'PENDING', 'APPROVED', 'REJECTED', 'PENDING', 'PENDING', 'PENDING'] as const)[i] ?? 'PENDING',
      aiFlag: i % 2 === 0 ? 'UNAPPROVED_HEALTH_CLAIM' : 'IMAGE_POLICY',
      assignee: i % 3 === 1 ? 'Sara Tesfaye' : null,
      slaState: i % 5 === 0 ? 'AT_RISK' : 'ON_TRACK',
      dueAt: ahead(1 - i * 0.1),
      enqueuedAt: ago(1, i)
    })
  );

  /* Verified-purchase product reviews (S324): staff see people and products by name, never by id. */
  const productReviews = Array.from({ length: 8 }, (_, i) =>
    rec(`rev-${700 + i}`, {
      atin: `AT${String(80_000_000 + i * 977)}`,
      productTitle: pick(['Shea butter, raw 500 g', 'Kente print tote', 'Black soap bar', 'Palm oil 1 L']),
      seller: pick(SELLERS),
      reviewerName: pick(['Amara O.', 'Kofi B.', 'Zainab K.', 'Chidi E.']),
      rating: (['5', '4', '1', '5', '3', '2', '5', '4'] as const).map(Number)[i] ?? 5,
      title: pick(['Smooth and pure', 'Slow delivery', 'Not as described', null]),
      text: pick(['Absorbs fast, no smell.', 'Arrived late but fine.', 'Not what the photos showed.']),
      status: (['PUBLISHED', 'PUBLISHED', 'PENDING', 'PUBLISHED', 'PUBLISHED', 'REMOVED', 'PUBLISHED', 'REJECTED'] as const)[i] ?? 'PUBLISHED',
      reportCount: i === 2 ? 2 : 0,
      createdAt: ago(10 - i)
    })
  );

  const claimStatus = ['UNDER_REVIEW', 'AWAITING_SELLER', 'ESCALATED', 'UNDER_REVIEW', 'RESOLVED_REFUND', 'RESOLVED_DENIED', 'UNDER_REVIEW'];
  const claims = claimStatus.map((status, i) => {
    const o = orders[i * 3];
    const amount = 1500 + i * 2300;
    return rec(
      `clm-${500 + i}`,
      {
        reference: `CLM-${88_100 + i}`,
        type: pick(['ITEM_NOT_RECEIVED', 'DAMAGED_ITEM', 'NOT_AS_DESCRIBED', 'WRONG_ITEM']),
        status,
        orderNumber: o?.id ?? 'AT-200400',
        customerId: (o?.fields.customerId as string) ?? 'cus-1000',
        seller: (o?.fields.seller as string) ?? SELLERS[0] ?? '',
        amountMinor: amount,
        refundMinor: status === 'RESOLVED_REFUND' ? amount : null,
        description: 'Parcel arrived crushed; jar leaked.',
        slaState: i === 2 ? 'BREACHED' : 'ON_TRACK',
        openedAt: ago(6 - i)
      },
      [act(ago(6 - i), 'Customer', 'Claim opened'), ...(status === 'ESCALATED' ? [act(ago(2), 'Grace Adjei', 'Escalated: seller unresponsive')] : [])]
    );
  });

  const returns = Array.from({ length: 7 }, (_, i) => {
    const o = orders[i * 4 + 1];
    return rec(`rma-${600 + i}`, {
      number: `RMA-${String(51_000_000 + i * 731)}`,
      orderNumber: o?.id ?? 'AT-200400',
      customerId: (o?.fields.customerId as string) ?? 'cus-1000',
      method: pick(['DROP_OFF', 'DRIVER_PICKUP']),
      status: (['REQUESTED', 'APPROVED', 'IN_TRANSIT', 'RECEIVED', 'REFUNDED', 'REJECTED', 'REQUESTED'] as const)[i] ?? 'REQUESTED',
      refundState: i === 4 ? 'REFUNDED' : 'PENDING',
      estimateMinor: 1200 + i * 800,
      reason: pick(['DAMAGED', 'WRONG_ITEM', 'NO_LONGER_NEEDED']),
      createdAt: ago(5 - i * 0.5)
    });
  });

  const payouts = Array.from({ length: 10 }, (_, i) =>
    rec(`pay-${700 + i}`, {
      org: pick(SELLERS),
      amountMinor: 24_000 + Math.round(rnd() * 180_000),
      currency: 'USD',
      status: (['REQUESTED', 'REQUESTED', 'ON_HOLD', 'APPROVED', 'PAID', 'PAID', 'PAID', 'FAILED', 'REQUESTED', 'PAID'] as const)[i] ?? 'PAID',
      holdReason: i === 2 ? 'Stripe requirement past due' : null,
      requestedBy: ['Ada Obi', 'Kofi Mensah', 'Zainab Bello'][i % 3] ?? 'Ada Obi',
      requestedAt: ago(i * 2)
    })
  );

  const inventory = Array.from({ length: 12 }, (_, i) => {
    const onHand = 20 + Math.round(rnd() * 400);
    const reserved = Math.round(onHand * rnd() * 0.3);
    return rec(`lot-${800 + i}`, {
      sku: pick(['AT-JOL-200', 'AT-SHEA-500', 'AT-PALM-1L', 'AT-SUYA-100', 'AT-GARRI-2KG']),
      lotNo: `L${day(30 + i).replace(/-/g, '')}-${i}`,
      node: pick(['Houston FC', 'Edison FC', 'Atlanta FC']),
      bin: `A-${10 + i}-${(i % 4) + 1}`,
      qtyOnHand: onHand,
      qtyReserved: reserved,
      qtyAvailable: onHand - reserved,
      expDate: day(-90 + i * 9),
      status: i === 3 ? 'QUARANTINED' : 'SELLABLE',
      // Every fourth lot was sourced in Mexico and paid in pesos: the cost as paid, the rate and the USD value.
      ...(i % 4 === 1
        ? { unitLandedCostMinor: 12_000, currency: 'MXN', fxRateToUsd: 0.0577, unitCostUsdMinor: 692, costPaid: 'MXN 120.00 @ 0.0577' }
        : { unitLandedCostMinor: 180 + i * 35, currency: 'USD', fxRateToUsd: 1, unitCostUsdMinor: 180 + i * 35, costPaid: `USD ${((180 + i * 35) / 100).toFixed(2)}` })
    });
  });

  const purchaseOrders = Array.from({ length: 8 }, (_, i) =>
    rec(
      `po-${900 + i}`,
      {
        number: `PO-${26_000 + i}`,
        supplier: pick(['Olam Foods (Lagos)', 'Accra Organics', 'Kumasi Weavers', 'Dangote Retail']),
        status: (['DRAFT', 'PENDING_APPROVAL', 'PENDING_APPROVAL', 'APPROVED', 'SENT', 'CONFIRMED', 'RECEIVED', 'CANCELLED'] as const)[i] ?? 'DRAFT',
        destination: pick(['Houston FC', 'Edison FC']),
        incoterm: 'FOB',
        expectedDate: day(-14 - i),
        totalMinor: 400_000 + i * 125_000,
        currency: 'USD',
        createdBy: 'Retail buyer'
      },
      [act(ago(12 - i), 'Retail buyer', 'PO raised')],
      [
        { sku: 'AT-JOL-200', title: 'Jollof seasoning 200 g', quantity: 1200, unitCostMinor: 180 },
        { sku: 'AT-SUYA-100', title: 'Suya spice 100 g', quantity: 800, unitCostMinor: 140 }
      ]
    )
  );

  const routes = Array.from({ length: 6 }, (_, i) =>
    rec(
      `rte-${1000 + i}`,
      {
        name: `HOU-${['W', 'SW', 'N', 'E', 'C', 'S'][i] ?? 'W'}-${day(0).slice(5)}`,
        zone: pick(['Alief', 'Sugar Land', 'Katy', 'Pearland', 'Midtown']),
        serviceDate: day(0),
        window: i % 2 ? '2pm–6pm' : '9am–1pm',
        driver: i < 4 ? pick(['Tunde Adeyemi', 'Chinedu Okafor']) : null,
        vehicle: i < 4 ? `VAN-${10 + i}` : null,
        status: (['IN_PROGRESS', 'IN_PROGRESS', 'ASSIGNED', 'ASSIGNED', 'PLANNED', 'PLANNED'] as const)[i] ?? 'PLANNED',
        stops: 8 + i * 3,
        perishable: i % 2 === 0
      },
      [act(ago(0, 6), 'Dispatcher', 'Route built from the pool')],
      Array.from({ length: 3 }, (__, k) => ({ seq: k + 1, address: `${100 + k * 17} Bellaire Blvd, Houston`, status: k === 0 && i < 2 ? 'DELIVERED' : 'PENDING', packages: 1 + (k % 2) }))
    )
  );

  /* Dispatch board (S281): shipments waiting for a route, and what the driver app raised. */
  const dispatchShipments = Array.from({ length: 5 }, (_, i) =>
    rec(`shp-${2000 + i}`, {
      orderNumber: `AT-${String(410_200 + i * 7)}`, sellerName: i % 2 ? 'AfroTransact Retail' : 'Kente Naturals', origin: i % 2 ? 'FC' : 'SELLER',
      place: pick(['Houston, TX 77036', 'Sugar Land, TX 77478', 'Katy, TX 77449', 'Pearland, TX 77584']), units: 1 + (i % 3), perishable: i % 2 === 0 ? 'Yes' : 'No',
      promisedBy: day(-1 - (i % 2)), status: 'AWAITING_ROUTE'
    })
  );
  const sosAlerts = [
    rec('sos-1', { kind: 'Breakdown', driverId: 'drv-1', driverName: 'Tunde Adeyemi', routeId: 'rte-1000', routeName: 'HOU West AM', where: '29.70510, -95.55120', note: 'Flat tyre on Bellaire Blvd', raisedAt: ago(0, 1), status: 'ALERT' }),
    rec('sos-2', { kind: 'Unsafe', driverId: 'drv-2', driverName: 'Chinedu Okafor', routeId: 'rte-1001', routeName: 'HOU Southwest PM', where: '29.61980, -95.63540', note: 'Aggressive dog at stop 4', raisedAt: ago(0, 3), status: 'ACKNOWLEDGED' })
  ];
  const vehicleDefects = [
    rec('def-1', { vehicleId: 'VAN-12', driverId: 'drv-2', driverName: 'Chinedu Okafor', routeId: 'rte-1002', routeName: 'HOU Central AM', failed: 'Brakes', note: 'Soft brake pedal at pre-trip', reportedAt: ago(0, 2), status: 'BLOCKED' }),
    rec('def-2', { vehicleId: 'VAN-14', driverId: 'drv-1', driverName: 'Tunde Adeyemi', routeId: 'rte-1003', routeName: 'HOU North PM', failed: 'Wipers', note: 'Passenger wiper torn', reportedAt: ago(1), status: 'OPEN' })
  ];
  const driverEvents = ['session signed in', 'inspection submitted', 'loadout completed', 'route started', 'stop arrived', 'delivery completed', 'delivery problem', 'pickup confirmed'].map((type, i) =>
    rec(`ev-${100 + i}`, { capturedAt: ago(0, 8 - i), type: type.charAt(0).toUpperCase() + type.slice(1), driverId: i % 2 ? 'drv-2' : 'drv-1', driverName: i % 2 ? 'Chinedu Okafor' : 'Tunde Adeyemi', routeId: `rte-${1000 + (i % 2)}`, routeName: i % 2 ? 'HOU Southwest PM' : 'HOU West AM', stopId: i > 3 ? `st-${i}` : null, stopName: i > 3 ? `Stop ${i} · Bellaire Blvd` : null, message: type === 'delivery problem' ? 'Customer not home: left a card' : null, outcome: type === 'delivery problem' ? 'REJECTED' : 'APPLIED' })
  );

  const supportCases = Array.from({ length: 6 }, (_, i) => {
    const c = customers[i] ?? { id: 'cus-1000', name: 'Amara Nwosu' };
    const o = orders.find((x) => x.fields.customerId === c.id);
    return rec(
      `case-${1100 + i}`,
      {
        reference: `SUP-${7700 + i}`,
        source: 'LOLA_HANDOFF',
        customerId: c.id,
        customer: c.name,
        orderNumber: o?.id ?? null,
        reason: pick(['Where is my order?', 'Refund request', 'Damaged item', 'Change delivery address']),
        summary: `Lola: ${c.name.split(' ')[0] ?? ''} asked about order ${o?.id ?? ''}; tracking shows a delay. Customer wants a refund for the late delivery fee.`,
        status: (['OPEN', 'OPEN', 'IN_PROGRESS', 'OPEN', 'RESOLVED', 'IN_PROGRESS'] as const)[i] ?? 'OPEN',
        assignee: i === 2 || i === 5 ? 'Grace Adjei' : null,
        slaState: i === 0 ? 'AT_RISK' : 'ON_TRACK',
        openedAt: ago(0, 2 + i * 3)
      },
      [act(ago(0, 2 + i * 3), 'Lola', 'Handed off to human support')]
    );
  });

  const audit = Array.from({ length: 16 }, (_, i) =>
    rec(`aud-${1200 + i}`, {
      seq: 90_000 - i,
      action: pick(['claim.decided', 'payout.approved', 'listing.rejected', 'role.assigned', 'application.reviewed', 'refund.issued']),
      actor: pick(['Sara Tesfaye', 'Grace Adjei', 'Musa Bello', 'Ifeoma Okafor']),
      entityType: pick(['claim', 'payout', 'product', 'user', 'application']),
      entityId: `ent-${i}`,
      summary: 'Recorded from the event stream',
      occurredAt: ago(i * 0.3),
      hash: `${(0xabc123 + i * 7919).toString(16)}…`
    })
  );

  const markets = [
    ['US', 'United States', 'LIVE', 50, 44],
    ['CA', 'Canada', 'COMING_SOON', 13, 0],
    ['GB', 'United Kingdom', 'PLANNED', 4, 0],
    ['NG', 'Nigeria', 'PLANNED', 37, 0],
    ['GH', 'Ghana', 'PAUSED', 16, 0]
  ].map(([code, name, status, regions, live]) =>
    rec(code as string, { code: code as string, name: name as string, status: status as string, regionCount: regions as number, liveRegions: live as number, launchNote: status === 'COMING_SOON' ? 'Spring 2027' : null, onboarding: status === 'LIVE' })
  );

  const siteSettings = [
    rec('site', {
      revision: 14,
      supportPhone: '+18324300000',
      supportPhoneDisplay: '(832) 430-0000',
      supportEmail: 'help@afrotransact.com',
      smsProgramName: 'AfroTransact Alerts',
      instagram: 'https://instagram.com/afrotransact',
      facebook: 'https://facebook.com/afrotransact',
      tiktok: 'https://tiktok.com/@afrotransact',
      updatedBy: 'Marketing',
      updatedAt: ago(3)
    })
  ];

  const legal = [
    ['conditions-of-use', 'Conditions of Use', '3.2', day(40)],
    ['privacy', 'Privacy Notice', '4.0', day(12)],
    ['cookies', 'Cookie Notice', '2.1', day(40)],
    ['privacy-choices', 'Your Privacy Choices', '1.3', day(40)],
    ['returns', 'Returns & Refunds', '2.0', day(130)],
    ['seller-terms', 'Seller Terms', '2.4', day(40)],
    ['prohibited-items', 'Prohibited & Restricted Items', '1.6', day(88)],
    ['accessibility', 'Accessibility Statement', '1.2', day(52)],
    ['sms-terms', 'SMS & Notification Terms', '1.0', day(1)],
    ['gift-card-terms', 'Gift Card Terms', '1.0', day(28)],
    ['store-credit-terms', 'Store Credit Terms', '1.0', day(28)],
    ['membership-terms', 'Plus Membership Terms', '1.0', day(28)],
    ['referral-terms', 'Referral Program Terms', '1.0', day(28)],
    ['preorder-delivery-terms', 'Pre-order and Delivery Run Terms', '1.0', day(28)],
    ['community-guidelines', 'Community Guidelines', '1.0', day(28)],
    ['ip-policy', 'Intellectual Property and Copyright Policy', '1.0', day(28)],
    ['provider-terms', 'Service Provider Terms', '1.0', day(28)],
    ['payments-terms', 'Payment Terms for Sellers and Providers', '1.0', day(28)],
    ['subprocessors', 'Sub-processors and Service Providers', '1.0', day(28)],
    ['data-processing', 'Data Processing Terms', '1.0', day(28)],
    ['seller-fees', 'Seller Fees Summary', '1.0', day(28)]
  ].map(([key, title, version, effective]) => rec(key as string, {
    key: key as string, title: title as string, group: legalGroupTitle(key as string), version: version as string, effectiveDate: effective as string,
    draftBanner: true, state: 'Draft for counsel', href: `/legal/${key as string}`
  }));

  const helpArticles = HELP_ARTICLES.map((a) => rec(a.slug, {
    slug: a.slug, title: a.title, summary: a.summary, topic: a.topic, topicTitle: a.topicTitle, status: 'PUBLISHED', updatedAt: a.updatedAt,
    sections: a.sections.map((x) => `## ${x.heading}\n\n${x.body}`).join('\n\n'), href: a.slug === 'help-center' ? '/help' : `/help/${a.slug}`
  }));

  const privacyRequests = [
    ['PR-100004', 'delete', 'adaeze.n@example.com', 'TX', 'RECEIVED', 2, 'Please close my account and delete my order history where allowed.'],
    ['PR-100003', 'optout', 'kwame.m@example.com', 'CA', 'RECEIVED', 5, null],
    ['PR-100002', 'access', 'tigist.a@example.com', 'NJ', 'COMPLETED', 20, 'Copy of all orders and messages'],
    ['PR-100001', 'correct', 'olumide@example.com', 'GA', 'DENIED', 50, 'Change the name on order AT-1001']
  ].map(([ref, type, email, state, status, daysAgo, details]) => rec(ref as string, {
    reference: ref as string, type: type as string, email: email as string, state: state as string, status: status as string, details: details as string | null,
    submittedAt: ago(daysAgo as number), dueAt: ahead(45 - (daysAgo as number)), overdue: false, locale: 'en',
    resolutionNote: status === 'DENIED' ? 'We could not verify the request from the account email.' : status === 'COMPLETED' ? 'Export emailed to the account address.' : null
  }));

  const placement = (id: string, slot: string, name: string, status: string, paid: boolean, advertiser: string | null, text: string, daysLeft: number) =>
    rec(id, { name, slot, status, paid, advertiser, text, priority: 10, startsAt: ago(3), endsAt: ahead(daysLeft), version: 1 }, [act(ago(4), 'Marketing', 'Draft created')]);
  const announcements = [
    placement('plc-1', 'announcement_bar', 'Free delivery over $49', 'LIVE', false, null, 'Free delivery on orders over $49 in Houston', 20),
    placement('plc-2', 'announcement_bar', 'Detty December pre-orders', 'IN_REVIEW', false, null, 'Pre-order party supplies for Detty December', 40),
    placement('plc-3', 'announcement_bar', 'Diwali savings', 'DRAFT', false, null, 'Diwali: 5% off sweets with DIWALI5', 30)
  ];
  const placements = [
    ...announcements,
    placement('plc-4', 'hero_carousel', 'Taste of home — groceries', 'LIVE', false, null, 'Taste of home, delivered', 25),
    placement('plc-5', 'display_banner', "Mama Tee's Pantry — sponsored", 'SCHEDULED', true, "Mama Tee's Pantry", 'Fresh egusi this week', 14),
    placement('plc-6', 'home_product_row', 'Kente Naturals — sponsored row', 'LIVE', true, 'Kente Naturals', 'Shea and black soap', 10)
  ];

  const providers = [
    ['google', 'Google', true],
    ['apple', 'Apple', true],
    ['facebook', 'Facebook', false],
    ['microsoft', 'Microsoft', false]
  ].map(([id, name, enabled]) => rec(id as string, { displayName: name as string, enabled: enabled as boolean, status: enabled ? 'ENABLED' : 'DISABLED', configured: id !== 'microsoft', updatedBy: 'Elena Petrova', updatedAt: ago(30) }));

  const feeSchedules = seedFeeSchedules(now);
  const fees = feeRecords(feeSchedules, now);

  const coupons = [
    rec('cp-welcome10', { code: 'WELCOME10', description: '10% off first order', type: 'PERCENT', value: 10, scope: 'CART', status: 'ACTIVE', funding: 'PLATFORM', redeemedCount: 128, version: 2 }),
    rec('cp-freeship', { code: 'FREESHIP', description: 'Free shipping over $35', type: 'FREE_SHIPPING', value: 0, scope: 'CART', status: 'ACTIVE', funding: 'PLATFORM', redeemedCount: 44, version: 1 }),
    rec('cp-pause', { code: 'SUMMER24', description: 'Summer sale (paused)', type: 'PERCENT', value: 15, scope: 'CART', status: 'PAUSED', funding: 'PLATFORM', redeemedCount: 902, version: 4 })
  ];

  const taxRates = [
    rec('state:TX', { kind: 'STATE', country: 'US', state: 'TX', zipPrefix: null, name: 'Texas', generalRate: 0.0625, groceryRate: 0, preparedFoodRate: 0.0625, shippingTaxable: true, facilitator: true, version: 3 }),
    rec('state:NJ', { kind: 'STATE', country: 'US', state: 'NJ', zipPrefix: null, name: 'New Jersey', generalRate: 0.06625, groceryRate: 0, preparedFoodRate: 0.06625, shippingTaxable: true, facilitator: true, version: 2 }),
    rec('state:GA', { kind: 'STATE', country: 'US', state: 'GA', zipPrefix: null, name: 'Georgia', generalRate: 0.04, groceryRate: 0, preparedFoodRate: 0.04, shippingTaxable: true, facilitator: true, version: 1 }),
    rec('local:TX:770', { kind: 'LOCAL', country: 'US', state: 'TX', zipPrefix: '770', name: 'Houston', generalRate: 0.02, groceryRate: null, preparedFoodRate: null, shippingTaxable: null, facilitator: null, version: null })
  ];

  const taxCodes = [
    rec('GENERAL_GOODS', { code: 'GENERAL_GOODS', label: 'General taxable goods', category: 'GENERAL', providerCode: 'txcd_99999999', taxableLabel: 'Yes', exemptStates: '', state: 'ACTIVE' }),
    rec('CLOTHING', { code: 'CLOTHING', label: 'Clothing and accessories', category: 'GENERAL', providerCode: null, taxableLabel: 'Yes', exemptStates: '', state: 'ACTIVE' }),
    rec('NON_TAXABLE', { code: 'NON_TAXABLE', label: 'Not taxable (gift cards, donations, exempt items)', category: 'GENERAL', providerCode: null, taxableLabel: 'Never', exemptStates: '', state: 'ACTIVE' }),
    rec('PACKAGED_FOOD', { code: 'PACKAGED_FOOD', label: 'Packaged food (shelf-stable)', category: 'GROCERY', providerCode: null, taxableLabel: 'Yes', exemptStates: 'NJ,TX', state: 'ACTIVE' }),
    rec('PREPARED_FOOD', { code: 'PREPARED_FOOD', label: 'Hot or ready-to-eat food', category: 'PREPARED_FOOD', providerCode: null, taxableLabel: 'Yes', exemptStates: '', state: 'ACTIVE' })
  ];
  const taxRules = [
    rec('dress||', { rowId: 'dress||', productType: 'dress', attrKey: null, attrValue: null, taxCode: 'CLOTHING', kind: 'TYPE', note: 'Clothing' }),
    rec('grocery|tax_class|Packaged, shelf-stable', { rowId: 'grocery|tax_class|Packaged, shelf-stable', productType: 'grocery', attrKey: 'tax_class', attrValue: 'Packaged, shelf-stable', taxCode: 'PACKAGED_FOOD', kind: 'ANSWER', note: 'Food for home consumption' }),
    rec('grocery|tax_class|Hot or ready-to-eat', { rowId: 'grocery|tax_class|Hot or ready-to-eat', productType: 'grocery', attrKey: 'tax_class', attrValue: 'Hot or ready-to-eat', taxCode: 'PREPARED_FOOD', kind: 'ANSWER', note: 'Prepared food' })
  ];
  const reconciliation = [
    rec('rec-1', { stripeRef: 'ch_3Q8xK2', windowStart: ago(1, 0), ledgerMinor: 10_000, stripeMinor: 9_750, differenceMinor: 250, status: 'OPEN', note: null, resolvedByName: null, resolvedAt: null,
      meaning: 'The ledger shows $2.50 more than Stripe reports for this object.' }),
    rec('rec-2', { stripeRef: 're_3Q8yL4', windowStart: ago(1, 0), ledgerMinor: -4_200, stripeMinor: -4_200 - 120, differenceMinor: 120, status: 'EXPLAINED', note: 'Stripe settles this fee the next day (timing)', resolvedByName: 'Platform Admin', resolvedAt: ago(0, 5),
      meaning: 'The ledger shows $1.20 more than Stripe reports for this object.' }),
    rec('rec-3', { stripeRef: 'tr_1Q9aB7', windowStart: ago(2, 0), ledgerMinor: 55_000, stripeMinor: 55_000, differenceMinor: 0, status: 'ADJUSTED', note: null, resolvedByName: 'Finance Lead', resolvedAt: ago(1, 2),
      meaning: 'The ledger and Stripe agree.' })
  ];
  const reconciliationRuns = [
    rec('run-1', { windowStart: ago(1, 0), ledgerNetMinor: 5_830_000, stripeNetMinor: 5_829_630, driftMinor: 370, differences: 2, state: 'DIFFERENCES', createdAt: ago(0, 8) }),
    rec('run-2', { windowStart: ago(2, 0), ledgerNetMinor: 4_411_000, stripeNetMinor: 4_411_000, driftMinor: 0, differences: 0, state: 'RECONCILED', createdAt: ago(1, 8) })
  ];
  const ledgerAdjustments = [
    rec('adj-1', { reason: 'Stripe fee booked twice for the charge', amountMinor: 250, stripeRef: 'ch_3Q8xK2', proposedByName: 'Finance Lead', decidedByName: null, status: 'PROPOSED', proposedAt: ago(0, 4), decidedAt: null, decisionNote: null, journalId: null }),
    rec('adj-2', { reason: 'Reclass a mis-booked chargeback fee to chargeback losses', amountMinor: 1_500, stripeRef: 'dp_1Q7cD9', proposedByName: 'Finance Lead', decidedByName: 'Platform Admin', status: 'POSTED', proposedAt: ago(3, 0), decidedAt: ago(2, 6), decisionNote: 'Checked against the Stripe dispute', journalId: 'a1b2c3d4' })
  ];
  const recoveries = [
    rec('rcv-1', { orgName: 'Kente Naturals', orderNumber: 'AT-000412', amountMinor: 1_840, outstandingMinor: 1_840, recoveredMinor: 0, resolvedMinor: 0, stripeCode: 'balance_insufficient',
      reason: 'Insufficient funds in the connected account: its balance was already paid out.', waitingFor: '', proposedByName: null, decidedByName: null, decidedAt: null, decisionNote: null, resolutionRef: null, status: 'OPEN', createdAt: ago(1, 2) }),
    rec('rcv-2', { orgName: 'Lagos Spice Co', orderNumber: 'AT-000398', amountMinor: 6_250, outstandingMinor: 6_250, recoveredMinor: 0, resolvedMinor: 0, stripeCode: 'balance_insufficient',
      reason: 'Insufficient funds in the connected account: its balance was already paid out.', waitingFor: 'Approval of a write-off', proposedByName: 'Finance Lead', decidedByName: null, decidedAt: null, decisionNote: null, resolutionRef: null, status: 'OPEN', createdAt: ago(3, 0) }),
    rec('rcv-3', { orgName: 'Accra Fabrics', orderNumber: 'AT-000377', amountMinor: 2_100, outstandingMinor: 0, recoveredMinor: 2_100, resolvedMinor: 0, stripeCode: 'balance_insufficient',
      reason: 'Insufficient funds in the connected account: its balance was already paid out.', waitingFor: '', proposedByName: null, decidedByName: null, decidedAt: null, decisionNote: null, resolutionRef: null, status: 'RECOVERED', createdAt: ago(6, 0) })
  ];
  const stripeLookup: MockRecord[] = [];
  const creditAdjustments = [
    rec('cadj-1', { customerId: customers[0]?.id ?? 'cus-1000', customerName: customers[0]?.name ?? 'Amara Nwosu', amountMinor: 25_000, source: 'STAFF', reason: 'Goodwill for a parcel that arrived a week late', proposedByName: 'Finance Lead', decidedByName: null, status: 'PROPOSED', proposedAt: ago(0, 3), decidedAt: null, decisionNote: null, balanceMinor: 2_500 }),
    rec('cadj-2', { customerId: customers[1]?.id ?? 'cus-1001', customerName: customers[1]?.name ?? 'Kofi Mensah', amountMinor: 1_500, source: 'PROMO', reason: 'Apology for a delayed pre-order delivery', proposedByName: 'Finance Lead', decidedByName: 'Finance Lead', status: 'POSTED', proposedAt: ago(4, 0), decidedAt: ago(4, 0), decisionNote: null, balanceMinor: 1_500 })
  ];
  const giftCards = [
    rec('gc-1', { code: '•••• K3M9', codeLast4: 'K3M9', amountMinor: 5_000, status: 'ACTIVE', origin: 'PURCHASE', purchaserId: customers[0]?.id ?? 'cus-1000', purchaserName: customers[0]?.name ?? 'Amara Nwosu', recipient: 'Ama Boateng', recipientName: 'Ama Boateng', recipientEmail: 'ama@example.com', senderName: 'Amara', message: 'Happy birthday!', locale: 'en', sendAt: ago(2, 0), deliveryStatus: 'SENT', deliveryCount: 1, sentAt: ago(2, 0), issueReason: null, issuedByName: null, redeemedByName: null, redeemedAt: null, voidedByName: null, voidedAt: null, voidReason: null, refundedToCard: 'No', createdAt: ago(3, 0) }),
    rec('gc-2', { code: '•••• P7QX', codeLast4: 'P7QX', amountMinor: 2_500, status: 'REDEEMED', origin: 'STAFF', purchaserId: null, purchaserName: null, recipient: 'kwame@example.com', recipientName: null, recipientEmail: 'kwame@example.com', senderName: null, message: null, locale: 'en', sendAt: ago(9, 0), deliveryStatus: 'SENT', deliveryCount: 1, sentAt: ago(9, 0), issueReason: 'Goodwill after a lost parcel', issuedByName: 'Finance Lead', redeemedByName: customers[2]?.name ?? 'Kwame Asante', redeemedAt: ago(8, 0), voidedByName: null, voidedAt: null, voidReason: null, refundedToCard: 'No', createdAt: ago(10, 0) }),
    rec('gc-3', { code: '•••• D2ZR', codeLast4: 'D2ZR', amountMinor: 10_000, status: 'ACTIVE', origin: 'PURCHASE', purchaserId: customers[1]?.id ?? 'cus-1001', purchaserName: customers[1]?.name ?? 'Kofi Mensah', recipient: 'Efua Owusu', recipientName: 'Efua Owusu', recipientEmail: 'efua@example.com', senderName: 'Kofi', message: 'Congratulations!', locale: 'en', sendAt: ahead(14), deliveryStatus: 'SCHEDULED', deliveryCount: 0, sentAt: null, issueReason: null, issuedByName: null, redeemedByName: null, redeemedAt: null, voidedByName: null, voidedAt: null, voidReason: null, refundedToCard: 'No', createdAt: ago(1, 0) })
  ];
  const creditLiabilities = [
    rec('credit-outstanding', { item: 'Credit owed to customers', amountMinor: 12_500, count: 4, account: '2600', note: 'Customer balances today. Credit never expires and cannot be paid out.' }),
    rec('credit-open-orders', { item: 'Credit spent on orders not shipped yet', amountMinor: 2_500, count: null, account: '2600', note: 'Held in 2600 until each order ships, then it becomes revenue.' }),
    rec('giftcards-outstanding', { item: 'Gift cards sold or issued, not yet redeemed', amountMinor: 15_000, count: 2, account: '2650', note: 'Owed until the recipient redeems the card into credit.' }),
    rec('giftcards-redeemed', { item: 'Gift cards redeemed (all time)', amountMinor: 2_500, count: null, account: '', note: "Moved into customers' credit." }),
    rec('giftcards-voided', { item: 'Gift cards voided (all time)', amountMinor: 0, count: null, account: '', note: 'Ended by staff; purchased cards were refunded to the buyer\'s card.' }),
    rec('ledger-2600', { item: 'Ledger: customer credit', amountMinor: 15_000, count: null, account: '2600', note: 'Customer balances plus credit spent on orders not shipped yet.' }),
    rec('ledger-2650', { item: 'Ledger: gift card liability', amountMinor: 15_000, count: null, account: '2650', note: 'Should equal the gift cards not yet redeemed.' })
  ];
  const moneySettings = [
    rec('money', { giftCardMinMinor: 1_000, giftCardMaxMinor: 50_000, creditApprovalLimitMinor: 10_000, presetsText: '$25.00, $50.00, $100.00, $200.00', updatedByName: 'Platform Admin', updatedAt: ago(20, 0), version: 3, termsEn: 'Gift cards never expire.', termsFr: '', termsEs: '', termsAr: '', termsYo: '' }, [], [
      { id: 'mh-1', changedAt: ago(20, 0), changedByName: 'Platform Admin', reason: 'Launch limits: $10 to $500' }
    ])
  ];
  const referrals = [
    rec('ref-1', { referrerId: customers[0]?.id ?? 'cus-1000', referrerName: customers[0]?.name ?? 'Amara Nwosu', referredId: customers[1]?.id ?? 'cus-1001', referredName: customers[1]?.name ?? 'Kofi Mensah', status: 'IN_REVIEW', flagsText: 'Same device', rewardsText: 'Referrer $10.00 credit (pending) · Friend $10.00 credit (pending)', costMinor: 0, signedUpAt: ago(20, 0), expiresAt: ahead(70), qualifyingOrderId: 'AT-000412', qualifyingMinor: 4_200, deliveredAt: ago(5, 0), eligibleAt: ahead(25), decision: null, decisionReason: null, decidedByName: null, decidedAt: null }, [], [
      { id: 'REFERRER', who: customers[0]?.name ?? 'Amara Nwosu', side: 'Referrer', reward: '$10.00 credit', status: 'PENDING', grantedAt: null },
      { id: 'REFERRED', who: customers[1]?.name ?? 'Kofi Mensah', side: 'Referred', reward: '$10.00 credit', status: 'PENDING', grantedAt: null }
    ]),
    rec('ref-2', { referrerId: customers[0]?.id ?? 'cus-1000', referrerName: customers[0]?.name ?? 'Amara Nwosu', referredId: customers[2]?.id ?? 'cus-1002', referredName: customers[2]?.name ?? 'Kwame Asante', status: 'REWARDED', flagsText: '', rewardsText: 'Referrer $10.00 credit · Friend $10.00 credit', costMinor: 2_000, signedUpAt: ago(60, 0), expiresAt: ahead(30), qualifyingOrderId: 'AT-000377', qualifyingMinor: 5_500, deliveredAt: ago(45, 0), eligibleAt: ago(15, 0), decision: null, decisionReason: null, decidedByName: null, decidedAt: null }, [], [
      { id: 'REFERRER', who: customers[0]?.name ?? 'Amara Nwosu', side: 'Referrer', reward: '$10.00 credit', status: 'GRANTED', grantedAt: ago(15, 0) },
      { id: 'REFERRED', who: customers[2]?.name ?? 'Kwame Asante', side: 'Referred', reward: '$10.00 credit', status: 'GRANTED', grantedAt: ago(15, 0) }
    ]),
    rec('ref-3', { referrerId: customers[1]?.id ?? 'cus-1001', referrerName: customers[1]?.name ?? 'Kofi Mensah', referredId: customers[3]?.id ?? 'cus-1003', referredName: customers[3]?.name ?? 'Efua Owusu', status: 'WAITING', flagsText: '', rewardsText: 'Referrer $10.00 credit (pending) · Friend 14 membership days (deferred)', costMinor: 0, signedUpAt: ago(12, 0), expiresAt: ahead(78), qualifyingOrderId: 'AT-000430', qualifyingMinor: 3_600, deliveredAt: ago(3, 0), eligibleAt: ahead(27), decision: null, decisionReason: null, decidedByName: null, decidedAt: null }, [], [
      { id: 'REFERRER', who: customers[1]?.name ?? 'Kofi Mensah', side: 'Referrer', reward: '$10.00 credit', status: 'PENDING', grantedAt: null },
      { id: 'REFERRED', who: customers[3]?.name ?? 'Efua Owusu', side: 'Referred', reward: '14 membership days', status: 'DEFERRED', grantedAt: null }
    ])
  ];
  const referralSettings = [
    rec('referral', { stateText: 'On', enabled: true, referrerType: 'CREDIT', referrerCreditMinor: 1_000, referrerDays: 0, referredType: 'CREDIT', referredCreditMinor: 1_000, referredDays: 0, referrerText: '$10.00 credit', referredText: '$10.00 credit', qualifyingEvent: 'FIRST_ORDER_DELIVERED', minOrderMinor: 3_000, returnWindowDays: 30, maxRewardedPerReferrer: 20, maxCreditPerCustomerMinor: 50_000, validityDays: 90, costMinor: 2_000, pendingMinor: 2_000, membershipDaysGranted: 0, countsText: 'In review: 1 · Rewarded: 1 · Waiting: 1', updatedByName: 'Platform Admin', updatedAt: ago(14, 0), version: 2 }, [], [
      { id: 'rh-1', changedAt: ago(14, 0), changedByName: 'Platform Admin', reason: 'Launch offer: $10 each after a $30 first order' }
    ])
  ];
  const benefitFlags = { freeDelivery: false, freeDeliveryMin: 0, freeDeliveryCap: 0, freeShipping: false, freeShippingMin: 0, freeShippingCap: 0, cashback: false, cashbackPercent: 0, cashbackMin: 0, cashbackCap: 0,
    memberPrices: false, earlyAccess: false, earlyAccessHours: 0, prioritySupport: false, boostedRewards: false, boostedPercent: 0, monthlyCredit: false, monthlyCreditAmount: 0 };
  const membershipTiers = [
    rec('plus', { code: 'plus', name: 'Plus', description: 'Free delivery and shipping on orders from $25.00, cashback and priority support.', status: 'PUBLISHED', sortOrder: 10, monthlyPriceMinor: 999, yearlyPriceMinor: 9900, trialDays: 14, members: 12, trialing: 3,
      ...benefitFlags, freeDelivery: true, freeDeliveryMin: 25, freeShipping: true, freeShippingMin: 25, freeShippingCap: 15, cashback: true, cashbackPercent: 2, prioritySupport: true,
      benefitsText: 'Free AfroTransact delivery, Free carrier shipping, Cashback as credit, Priority support', updatedByName: 'Platform Admin', updatedAt: ago(10, 0), version: 4 }, [], [
      { id: 'th-2', changedAt: ago(10, 0), changedByName: 'Platform Admin', action: 'Published', reason: 'Open Plus to every shopper' },
      { id: 'th-1', changedAt: ago(14, 0), changedByName: 'Platform Admin', action: 'Created', reason: 'The first membership tier' }
    ]),
    rec('premium', { code: 'premium', name: 'Premium', description: 'Everything on every order, bigger cashback and early access.', status: 'DRAFT', sortOrder: 20, monthlyPriceMinor: 1999, yearlyPriceMinor: 19_900, trialDays: 14, members: 0, trialing: 0,
      ...benefitFlags, freeDelivery: true, freeShipping: true, cashback: true, cashbackPercent: 5, earlyAccess: true, earlyAccessHours: 24, prioritySupport: true,
      benefitsText: 'Free AfroTransact delivery, Free carrier shipping, Cashback as credit, Early access to pre-orders and runs, Priority support', updatedByName: 'Platform Admin', updatedAt: ago(2, 0), version: 1 }, [], [
      { id: 'th-3', changedAt: ago(2, 0), changedByName: 'Platform Admin', action: 'Created', reason: 'A bigger tier for large households' }
    ])
  ];
  membershipTiers.forEach((r) => { r.fields.benefitDetails = tierBenefitDetails(r.fields); });
  const memberships = [
    rec('mem-1', { customerId: customers[0]?.id ?? 'cus-1000', customerName: customers[0]?.name ?? 'Amara Nwosu', tierCode: 'plus', tierName: 'Plus', status: 'ACTIVE', interval: 'Monthly', priceMinor: 999, source: 'Paid', startedAt: ago(40, 0), periodEnd: ago(-5, 0), cancelAtPeriodEnd: 'No', refundPossible: 'No', refundWhy: 'Window passed' }, [], [
      { id: 'mc-1', createdAt: ago(40, 0), kind: 'Join', amountMinor: 999, status: 'SUCCEEDED', refundedMinor: 0 },
      { id: 'mc-2', createdAt: ago(10, 0), kind: 'Renewal', amountMinor: 999, status: 'SUCCEEDED', refundedMinor: 0 }
    ]),
    rec('mem-2', { customerId: customers[1]?.id ?? 'cus-1001', customerName: customers[1]?.name ?? 'Kofi Mensah', tierCode: 'plus', tierName: 'Plus', status: 'TRIALING', interval: 'Yearly', priceMinor: 9900, source: 'Paid', startedAt: ago(3, 0), periodEnd: ago(-11, 0), cancelAtPeriodEnd: 'No', refundPossible: 'No', refundWhy: 'Not paid' }, [], [])
  ];
  const membershipSettings = [
    rec('membership', { refundWindowDays: 7, retryText: '1, 3, 5', updatedByName: 'Platform Admin', updatedAt: ago(20, 0), version: 2 }, [], [
      { id: 'sh-1', changedAt: ago(20, 0), changedByName: 'Platform Admin', reason: 'Launch rules: seven day refund window' }
    ])
  ];
  const membershipReport = [
    rec('members', { item: 'Members now', amountMinor: 0, count: 15, note: 'Trialing, active or past due.' }),
    rec('trialing', { item: 'On a free trial', amountMinor: 0, count: 3, note: 'They pay nothing until the trial ends and get no credit benefits.' }),
    rec('mrr', { item: 'Monthly recurring revenue', amountMinor: 11_988, count: null, note: 'What paying members pay per month (a yearly fee divided by twelve).' }),
    rec('arr', { item: 'Annual recurring revenue', amountMinor: 143_856, count: null, note: 'Monthly recurring revenue times twelve.' }),
    rec('charged', { item: 'Fees charged', amountMinor: 21_978, count: null, note: 'Last 30 days. Joining, renewals and upgrades.' }),
    rec('refunded', { item: 'Fees refunded', amountMinor: 999, count: 1, note: 'Last 30 days. Refunded to the card.' }),
    rec('benefit-cost', { item: 'Cost of the benefits', amountMinor: 5_400, count: null, note: 'Last 30 days. Credit granted plus delivery and shipping fees waived.' }),
    rec('credit-granted', { item: 'Credit granted to members', amountMinor: 1_200, count: null, note: 'Last 30 days. Cashback and monthly credit; store credit only buys products and services.' }),
    rec('fees-waived', { item: 'Delivery and shipping fees waived', amountMinor: 4_200, count: null, note: 'Last 30 days. Fees AfroTransact did not charge members.' }),
    rec('churn', { item: 'Churn', amountMinor: 0, count: null, note: 'Last 30 days. 30% of the 10 members at the start left (3 memberships ended).' }),
    rec('tier-plus', { item: 'Plus tier', amountMinor: 11_988, count: 15, note: '3 trialing, 11 active, 1 past due, 2 canceling. Joined 8, canceled 2, lapsed 1. Churn 30%. Monthly recurring revenue shown.' })
  ];
  const paymentSettings = [
    rec('stripe.mode', { label: 'Stripe', value: 'Test (Stripe test mode)', source: 'STRIPE_SECRET_KEY', help: 'Which Stripe account money moves through. Test mode never moves real money.' }),
    rec('settlement.hold', { label: 'Hold before a seller can be paid', value: '7 days after delivery', source: 'SETTLEMENT_HOLD', help: "A seller's net for an order is held until this long after it is delivered, then transferred to their Stripe account." }),
    rec('payout.minimum', { label: 'Smallest payout a seller can request', value: '$25.00', source: 'PAYOUT_MINIMUM_MINOR', help: 'Requests below this are refused.' }),
    rec('payout.daily', { label: 'Payout requests', value: 'One per seller per day (America/Chicago time), then approved by Finance', source: 'built in', help: 'Each request is approved, held or released by Finance staff.' })
  ];
  const taxSettings = [rec('method', { setting: 'Who calculates sales tax', method: 'RULES', methodLabel: 'Our own rules', stripeLabel: 'Not set up yet', changedByName: 'Platform Admin', changedAt: ago(3, 0), configuredDefault: 'RULES' })];
  const maintenance = [rec('mode', { setting: 'Maintenance mode', state: 'OFF', stateLabel: 'Off: the platform is open', scope: 'SHOPPERS', title: '', message: '', updatedByName: 'Platform Admin', updatedAt: ago(9, 0) })];
  const taxFilings = [
    rec('2026-09|US-TX|USD', { month: '2026-09', jurisdiction: 'US-TX', currency: 'USD', dueMinor: 133_600, paidMinor: null, outstandingMinor: 133_600, filedOn: null, confirmationRef: null, state: 'NOT_FILED', statusLabel: 'Not filed', remittanceId: null }),
    rec('2026-08|US-TX|USD', { month: '2026-08', jurisdiction: 'US-TX', currency: 'USD', dueMinor: 100_000, paidMinor: 100_000, outstandingMinor: 0, filedOn: '2026-09-15', confirmationRef: 'TX-CONF-1', state: 'SETTLED', statusLabel: 'Settled', remittanceId: 'rem-1' }),
    rec('2026-07|US-TX|USD', { month: '2026-07', jurisdiction: 'US-TX', currency: 'USD', dueMinor: 80_000, paidMinor: 85_000, outstandingMinor: -5_000, filedOn: '2026-08-14', confirmationRef: 'TX-CONF-0', state: 'OVERPAID', statusLabel: 'Overpaid', remittanceId: 'rem-0' })
  ];
  const taxNexus = [
    rec('US-TX', { jurisdiction: 'US-TX', name: 'Texas', facilitator: true, facilitatorLabel: 'Yes', salesMinor: 12_000_000, salesThresholdMinor: 10_000_000, orders: 340, percent: 120, percentLabel: '120%', status: 'EXCEEDED', registered: false, registrationNumber: null }),
    rec('US-NY', { jurisdiction: 'US-NY', name: 'New York', facilitator: true, facilitatorLabel: 'Yes', salesMinor: 8_500_000, salesThresholdMinor: 10_000_000, orders: 210, percent: 85, percentLabel: '85%', status: 'APPROACHING', registered: false, registrationNumber: null }),
    rec('US-GA', { jurisdiction: 'US-GA', name: 'Georgia', facilitator: true, facilitatorLabel: 'Yes', salesMinor: 11_000_000, salesThresholdMinor: 10_000_000, orders: 300, percent: 110, percentLabel: '110%', status: 'REGISTERED', registered: true, registrationNumber: 'GA-4411-22' }),
    rec('US-NJ', { jurisdiction: 'US-NJ', name: 'New Jersey', facilitator: true, facilitatorLabel: 'Yes', salesMinor: 900_000, salesThresholdMinor: 10_000_000, orders: 30, percent: 9, percentLabel: '9%', status: 'BELOW', registered: false, registrationNumber: null })
  ];
  const taxReview = [
    rec('grocery|abc123', { rowId: 'grocery|abc123', productType: 'grocery', attrHash: 'abc123', answer: null, taxCode: 'GENERAL_GOODS', count: 4, sampleAtin: 'AT10000001', lastSeen: ago(0, 3), state: 'OPEN', reviewedBy: null, reviewNote: null })
  ];

  const notifications: MockNotification[] = customers.flatMap((c, i) => [
    { id: `ntf-${i}-1`, recipientId: c.id, channel: 'email', templateKey: 'order.shipped', subject: 'Your order is on its way', status: 'DELIVERED', address: c.email, createdAt: ago(i, 2) },
    { id: `ntf-${i}-2`, recipientId: c.id, channel: 'sms', templateKey: 'delivery.otp', subject: null, status: i % 3 === 0 ? 'FAILED' : 'SENT', address: '+1832555010' + String(i), createdAt: ago(i, 1) }
  ]);

  const pickupLocations: MockRecord[] = [
    ['pu-hou-fc', 'Houston FC — Will Call', 'PLATFORM_COUNTER', '12000 Bissonnet St', 'Houston', 'TX', '77099', 'MON_SAT', '08:00', '20:00', 2, 'ACTIVE'],
    ['pu-hou-lock', 'AfroTransact Locker · Bellaire', 'LOCKER', '7100 Bellaire Blvd', 'Houston', 'TX', '77074', 'EVERY_DAY', '00:00', '23:59', 18, 'ACTIVE'],
    ['pu-edi-fc', 'Edison FC — Will Call', 'PLATFORM_COUNTER', '300 Raritan Center Pkwy', 'Edison', 'NJ', '08837', 'MON_SAT', '08:00', '20:00', 2, 'ACTIVE'],
    ['pu-atl-fc', 'Atlanta FC — Will Call', 'PLATFORM_COUNTER', '1600 Murphy Ave SW', 'Atlanta', 'GA', '30310', 'MON_SAT', '08:00', '18:00', 3, 'PAUSED']
  ].map(([id, name, kind, line1, city, st, zip, days, opens, closes, ready, state]) =>
    rec(String(id), { name: String(name), kind: String(kind), kindLabel: kind === 'LOCKER' ? 'AfroTransact locker' : 'AfroTransact counter', line1: String(line1), city: String(city),
      stateCode: String(st), postalCode: String(zip), days: String(days), opens: String(opens), closes: String(closes), readyHours: Number(ready),
      place: `${line1}, ${city}, ${st} ${zip}`, hoursLabel: `${days === 'EVERY_DAY' ? 'Every day' : 'Monday to Saturday'} ${opens}–${closes}`, state: String(state) }));

  return {
    now,
    lolaUsed: 0,
    user: { id: 'stf-1', firstName: 'Ifeoma', lastName: 'Okafor', email: 'ifeoma@afrotransact.com' },
    records: {
      users, roles, applications, organisations: [], 'connect-accounts': [], orders, products, 'category-requests': seedCategoryRequests(now), moderation, 'product-reviews': productReviews, claims, returns, payouts, inventory, 'purchase-orders': purchaseOrders, routes,
      'support-cases': supportCases, audit, markets, 'site-settings': siteSettings, 'legal-documents': legal, 'help-articles': helpArticles, 'privacy-requests': privacyRequests, announcements, placements,
      'identity-providers': providers, 'fee-schedules': fees, 'tax-rates': taxRates, 'tax-codes': taxCodes, 'tax-rules': taxRules, 'tax-review': taxReview, coupons, reconciliation, 'reconciliation-runs': reconciliationRuns, 'ledger-adjustments': ledgerAdjustments, recoveries, 'credit-adjustments': creditAdjustments, 'gift-cards': giftCards, 'credit-liabilities': creditLiabilities, 'money-settings': moneySettings, referrals, 'referral-settings': referralSettings, 'membership-tiers': membershipTiers, memberships, 'membership-settings': membershipSettings, 'membership-report': membershipReport, 'stripe-lookup': stripeLookup, 'payment-settings': paymentSettings, 'tax-settings': taxSettings, maintenance, 'tax-filings': taxFilings, 'tax-nexus': taxNexus,
      'dispatch-shipments': dispatchShipments, 'sos-alerts': sosAlerts, 'vehicle-defects': vehicleDefects, 'driver-events': driverEvents,
      'pickup-locations': pickupLocations, 'exchange-rates': seedExchangeRates(now),
      ...seedIdentityAdmin(now)
    },
    notifications,
    exports: [],
    audit: [],
    feeSchedules,
    dispatch: seedDispatch(now),
    templates: { rows: seedTemplateRows(), samples: seedTemplateSamples() },
    preorders: seedPreorders(now),
    markets: seedMarkets(),
    content: seedContent(now, helpArticles.map((r) => ({ slug: r.id, title: String(r.fields.title), summary: String(r.fields.summary), topic: String(r.fields.topic), status: String(r.fields.status), body: String(r.fields.sections), updatedAt: String(r.fields.updatedAt) }))),
    taxonomy: seedTaxonomy(),
    plans: seedPlans(now),
    feeRates: seedFeeRates(now),
    seq: 1
  };
}

export function getStaffDb(): MockStaffDb {
  const g = globalThis as G;
  g[KEY] ??= seed(new Date());
  // A database seeded before pre-orders existed (dev server kept across reloads) gets them on first use.
  g[KEY].preorders ??= seedPreorders(g[KEY].now);
  g[KEY].markets ??= seedMarkets();
  g[KEY].content ??= seedContent(g[KEY].now, []);
  g[KEY].taxonomy ??= seedTaxonomy();
  g[KEY].plans ??= seedPlans(g[KEY].now);
  g[KEY].feeRates ??= seedFeeRates(g[KEY].now);
  return g[KEY];
}

/** Re-seeds the mock (tests pin the clock). */
export function resetStaffDb(now: Date = new Date()): MockStaffDb {
  const g = globalThis as G;
  g[KEY] = seed(now);
  return g[KEY];
}
