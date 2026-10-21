/* In-memory state of the mock staff BFF (stored on globalThis so the route handler, server components and tests share
   it). Seeded from the Backoffice prototype (backoffice-data.js, backoffice-config.js, at-site.js) relative to today. */
import { type ExportJob } from '../../schemas/exports';
import { type ActivityEntry, type ResourceKey } from '../../schemas/staff';
import { HELP_ARTICLES } from '../fixtures/help';
import { type MockDispatch, seedDispatch } from './dispatch';
import { type MockFeeRatesState, seedFeeRates } from './fee-rates';
import { feeRecords, type MockFeeSchedule, seedFeeSchedules } from './fees';
import { type MockMarketsState, seedMarkets } from './markets';
import { type MockTemplateRow, type MockTemplateSample, seedTemplateRows, seedTemplateSamples } from './notification-templates';
import { type MockPlansState, seedPlans } from './plans';
import { type MockPreorderState, seedPreorders } from './preorders';

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
      requestedBy: 'Owner',
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
      unitLandedCostMinor: 180 + i * 35
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
    rec('sos-1', { kind: 'Breakdown', driverId: 'drv-1', routeId: 'rte-1000', where: '29.70510, -95.55120', note: 'Flat tyre on Bellaire Blvd', raisedAt: ago(0, 1), status: 'ALERT' }),
    rec('sos-2', { kind: 'Unsafe', driverId: 'drv-2', routeId: 'rte-1001', where: '29.61980, -95.63540', note: 'Aggressive dog at stop 4', raisedAt: ago(0, 3), status: 'ACKNOWLEDGED' })
  ];
  const vehicleDefects = [
    rec('def-1', { vehicleId: 'VAN-12', driverId: 'drv-2', routeId: 'rte-1002', failed: 'Brakes', note: 'Soft brake pedal at pre-trip', reportedAt: ago(0, 2), status: 'BLOCKED' }),
    rec('def-2', { vehicleId: 'VAN-14', driverId: 'drv-1', routeId: 'rte-1003', failed: 'Wipers', note: 'Passenger wiper torn', reportedAt: ago(1), status: 'OPEN' })
  ];
  const driverEvents = ['session signed in', 'inspection submitted', 'loadout completed', 'route started', 'stop arrived', 'delivery completed', 'delivery problem', 'pickup confirmed'].map((type, i) =>
    rec(`ev-${100 + i}`, { capturedAt: ago(0, 8 - i), type: type.charAt(0).toUpperCase() + type.slice(1), driverId: i % 2 ? 'drv-2' : 'drv-1', routeId: `rte-${1000 + (i % 2)}`, stopId: i > 3 ? `st-${i}` : null, message: type === 'delivery problem' ? 'Customer not home: left a card' : null, outcome: type === 'delivery problem' ? 'REJECTED' : 'APPLIED' })
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
    ['seller-terms', 'Seller & Provider Terms', '2.4', day(40)],
    ['prohibited-items', 'Prohibited & Restricted Items', '1.6', day(88)],
    ['accessibility', 'Accessibility Statement', '1.2', day(52)],
    ['sms-terms', 'SMS & Notification Terms', '1.0', day(1)]
  ].map(([key, title, version, effective]) => rec(key as string, { key: key as string, title: title as string, version: version as string, effectiveDate: effective as string, draftBanner: true, href: `/legal/${key as string}` }));

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
    rec('GENERAL_GOODS', { code: 'GENERAL_GOODS', label: 'General taxable goods', category: 'GENERAL', providerCode: 'txcd_99999999', state: 'ACTIVE' }),
    rec('CLOTHING', { code: 'CLOTHING', label: 'Clothing and accessories', category: 'GENERAL', providerCode: null, state: 'ACTIVE' }),
    rec('PACKAGED_FOOD', { code: 'PACKAGED_FOOD', label: 'Packaged food (shelf-stable)', category: 'GROCERY', providerCode: null, state: 'ACTIVE' }),
    rec('PREPARED_FOOD', { code: 'PREPARED_FOOD', label: 'Hot or ready-to-eat food', category: 'PREPARED_FOOD', providerCode: null, state: 'ACTIVE' })
  ];
  const taxRules = [
    rec('dress||', { rowId: 'dress||', productType: 'dress', attrKey: null, attrValue: null, taxCode: 'CLOTHING', kind: 'TYPE', note: 'Clothing' }),
    rec('grocery|tax_class|Packaged, shelf-stable', { rowId: 'grocery|tax_class|Packaged, shelf-stable', productType: 'grocery', attrKey: 'tax_class', attrValue: 'Packaged, shelf-stable', taxCode: 'PACKAGED_FOOD', kind: 'ANSWER', note: 'Food for home consumption' }),
    rec('grocery|tax_class|Hot or ready-to-eat', { rowId: 'grocery|tax_class|Hot or ready-to-eat', productType: 'grocery', attrKey: 'tax_class', attrValue: 'Hot or ready-to-eat', taxCode: 'PREPARED_FOOD', kind: 'ANSWER', note: 'Prepared food' })
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
      users, roles, applications, organisations: [], 'connect-accounts': [], orders, products, moderation, claims, returns, payouts, inventory, 'purchase-orders': purchaseOrders, routes,
      'support-cases': supportCases, audit, markets, 'site-settings': siteSettings, 'legal-documents': legal, 'help-articles': helpArticles, 'privacy-requests': privacyRequests, announcements, placements,
      'identity-providers': providers, 'fee-schedules': fees, 'tax-rates': taxRates, 'tax-codes': taxCodes, 'tax-rules': taxRules, 'tax-review': taxReview, coupons,
      'dispatch-shipments': dispatchShipments, 'sos-alerts': sosAlerts, 'vehicle-defects': vehicleDefects, 'driver-events': driverEvents,
      'pickup-locations': pickupLocations
    },
    notifications,
    exports: [],
    audit: [],
    feeSchedules,
    dispatch: seedDispatch(now),
    templates: { rows: seedTemplateRows(), samples: seedTemplateSamples() },
    preorders: seedPreorders(now),
    markets: seedMarkets(),
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
