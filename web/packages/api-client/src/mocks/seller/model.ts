/* Mock BFF domain rules for the Seller Center & Provider Hub, mirroring go/services/gateway-bff/internal/domain
   (seeded module matrix, role-aware navigation, capability cards, cancellation policy text, status labels), so the
   app behaves the same against MSW and the real BFF. */
import {
  type AccessLevel,
  type BookingAction,
  type BookingStatus,
  type CancellationPolicy,
  type CancellationPolicyInput,
  type ModuleKey,
  type NavGroup,
  type NavModule,
  type OrgKind,
  type RoleCard,
  type ShipmentStatus
} from '../../schemas/seller';

type Level = AccessLevel | 'NONE';

interface ModuleDef {
  identity: string;
  key: ModuleKey;
  label: string;
  group: string;
  detail: string;
}

const GROUP_LABELS: Record<string, string> = {
  overview: 'Overview', commerce: 'Commerce', bookings: 'Bookings', setup: 'Set up', growth: 'Growth', finance: 'Finance', money: 'Money',
  trust: 'Trust & compliance', settings: 'Settings'
};
const GROUP_ORDER = ['overview', 'commerce', 'bookings', 'setup', 'growth', 'finance', 'money', 'trust', 'settings'];

const m = (identity: string, key: ModuleKey, label: string, group: string, detail: string): ModuleDef => ({ identity, key, label, group, detail });

export const SELLER_MODULES: ModuleDef[] = [
  m('sHome', 'home', 'Home', 'overview', 'the store overview'),
  m('sOrders', 'orders', 'Orders', 'commerce', 'orders, shipping labels and refunds'),
  m('sOrders', 'returns', 'Returns', 'commerce', 'customer returns and their refunds'),
  m('sOrders', 'disputes', 'Disputes', 'commerce', 'disputes and A-to-z claims against you'),
  m('sListings', 'listings', 'Listings', 'commerce', 'product listings'),
  m('sListings', 'reviews', 'Reviews', 'commerce', 'reviews of your products and your replies'),
  m('sInventory', 'inventory', 'Inventory & FBA', 'commerce', 'stock levels'),
  m('sAds', 'coupons', 'Coupons', 'growth', 'promotional codes for your listings'),
  m('sPayouts', 'payouts', 'Payouts', 'finance', 'payouts and balances'),
  m('sPayouts', 'fees', 'Fees & commission', 'finance', 'commission and fees'),
  m('sBilling', 'billing', 'Plan & billing', 'finance', 'the plan and invoices'),
  m('sCompliance', 'account-health', 'Account health', 'trust', 'Stripe verification requirements'),
  m('sTeam', 'team', 'Team & roles', 'settings', 'team members and their roles')
];

export const PROVIDER_MODULES: ModuleDef[] = [
  m('pHome', 'home', 'Home', 'overview', 'today’s appointments and alerts'),
  m('pCalendar', 'calendar', 'Calendar', 'bookings', 'the appointment calendar — block time, reschedule, cancel'),
  m('pBookings', 'requests', 'Requests', 'bookings', 'booking requests — accept, decline, mark no-show'),
  m('pAvail', 'hours', 'Working hours', 'setup', 'working hours, breaks and time off'),
  m('pServices', 'services', 'Services & prices', 'setup', 'services, prices and deposits'),
  m('pPolicy', 'policy', 'Cancellation policy', 'setup', 'the cancellation and no-show policy'),
  m('pPayouts', 'earnings', 'Earnings', 'money', 'earnings per booking'),
  m('pPayouts', 'payouts', 'Payouts', 'money', 'payouts and balances'),
  m('pPayouts', 'fees', 'Fees & commission', 'money', 'commission and fees'),
  m('pBilling', 'billing', 'Plan & billing', 'money', 'the Provider Hub plan and invoices'),
  m('pCompliance', 'account-health', 'Account health', 'trust', 'Stripe verification requirements'),
  m('pTeam', 'team', 'Team & roles', 'settings', 'team members and their roles')
];

interface RoleDef {
  key: string;
  label: string;
  summary: string;
  note: string | null;
}

export const SELLER_ROLES: RoleDef[] = [
  { key: 'seller.owner', label: 'Owner', summary: 'Everything in Seller Center, including billing and team', note: 'The owner can do everything, including transferring ownership and closing the store.' },
  { key: 'seller.admin', label: 'Admin', summary: 'Everything except plan and billing changes', note: 'Admins run the store day to day but cannot change the plan or payout bank account.' },
  { key: 'seller.catalog', label: 'Catalog manager', summary: 'Listings, inventory and storefront content', note: null },
  { key: 'seller.fulfilment', label: 'Fulfilment', summary: 'Orders, shipping and inventory', note: 'Fulfilment can buy labels and confirm, pack and mark orders ready — never refunds or payouts.' },
  { key: 'seller.finance', label: 'Finance', summary: 'Payouts, billing and compliance', note: 'Finance can see money but cannot change listings or orders.' },
  { key: 'seller.ads', label: 'Marketing', summary: 'Advertising and storefront', note: null },
  { key: 'seller.support', label: 'Support agent', summary: 'Customer messages, orders and returns', note: null }
];

export const PROVIDER_ROLES: RoleDef[] = [
  { key: 'provider.owner', label: 'Owner', summary: 'Everything in Provider Hub, including billing and team', note: 'The owner can do everything, including transferring ownership and closing the business.' },
  { key: 'provider.manager', label: 'Manager', summary: 'Calendar, services, page and team', note: 'Managers run day-to-day operations but cannot change the plan, payout bank account or the owner.' },
  { key: 'provider.practitioner', label: 'Practitioner', summary: 'Own calendar and working hours', note: 'Practitioners only see and change their own calendar, hours and bookings — never other staff’s.' },
  { key: 'provider.finance', label: 'Finance', summary: 'Payouts, billing and compliance', note: 'Finance can see money but cannot change bookings, services or the page.' }
];

const matrix = (rows: Record<string, string>, roles: string[]): Record<string, Record<string, Level>> =>
  Object.fromEntries(
    Object.entries(rows).map(([module, cells]) => [module, Object.fromEntries(cells.split(',').map((lv, i) => [roles[i] ?? '', lv as Level]))])
  );

/** identity's seeded matrices (V2__seed_rbac.sql). */
export const SELLER_MATRIX = matrix(
  {
    sHome: 'FULL,FULL,VIEW,VIEW,VIEW,VIEW,VIEW', sOrders: 'FULL,FULL,VIEW,FULL,VIEW,NONE,EDIT', sListings: 'FULL,FULL,FULL,VIEW,NONE,VIEW,VIEW',
    sInventory: 'FULL,FULL,EDIT,FULL,NONE,NONE,NONE', sAds: 'FULL,FULL,NONE,NONE,VIEW,FULL,NONE', sStorefront: 'FULL,FULL,EDIT,NONE,NONE,FULL,NONE', sPayouts: 'FULL,VIEW,NONE,NONE,FULL,NONE,NONE',
    sBilling: 'FULL,VIEW,NONE,NONE,FULL,NONE,NONE', sTeam: 'FULL,FULL,NONE,NONE,NONE,NONE,NONE', sCompliance: 'FULL,FULL,NONE,NONE,EDIT,NONE,NONE'
  },
  SELLER_ROLES.map((r) => r.key)
);
export const PROVIDER_MATRIX = matrix(
  {
    pHome: 'FULL,FULL,VIEW,VIEW', pCalendar: 'FULL,FULL,EDIT,VIEW', pBookings: 'FULL,FULL,EDIT,VIEW', pAvail: 'FULL,FULL,EDIT,NONE', pServices: 'FULL,FULL,VIEW,VIEW',
    pPolicy: 'FULL,EDIT,VIEW,VIEW', pPage: 'FULL,FULL,NONE,NONE', pPayouts: 'FULL,VIEW,NONE,FULL', pBilling: 'FULL,VIEW,NONE,FULL', pTeam: 'FULL,EDIT,NONE,NONE',
    pCompliance: 'FULL,EDIT,NONE,EDIT'
  },
  PROVIDER_ROLES.map((r) => r.key)
);

const RANK: Record<Level, number> = { NONE: 0, VIEW: 1, EDIT: 2, APPROVE: 3, FULL: 4 };

export const modulesFor = (kind: OrgKind): ModuleDef[] => (kind === 'PROVIDER' ? PROVIDER_MODULES : SELLER_MODULES);
export const rolesFor = (kind: OrgKind): RoleDef[] => (kind === 'PROVIDER' ? PROVIDER_ROLES : SELLER_ROLES);

export function levelOf(kind: OrgKind, role: string, key: ModuleKey): Level {
  const def = modulesFor(kind).find((d) => d.key === key);
  if (!def) return 'NONE';
  const mx = kind === 'PROVIDER' ? PROVIDER_MATRIX : SELLER_MATRIX;
  return mx[def.identity]?.[role] ?? 'NONE';
}

export const allows = (kind: OrgKind, role: string, key: ModuleKey, min: AccessLevel): boolean => RANK[levelOf(kind, role, key)] >= RANK[min];

export function roleLabel(key: string): string {
  return [...SELLER_ROLES, ...PROVIDER_ROLES].find((r) => r.key === key)?.label ?? key;
}

export function navigation(kind: OrgKind, role: string, approved: boolean, badges: Partial<Record<ModuleKey, number>>): NavGroup[] {
  if (!approved) return [{ key: 'onboarding', label: 'Onboarding', modules: [{ key: 'onboarding', label: 'Onboarding', level: 'EDIT', badge: null }] }];
  const groups = new Map<string, NavModule[]>();
  for (const d of modulesFor(kind)) {
    const lv = levelOf(kind, role, d.key);
    if (lv === 'NONE') continue;
    const n = badges[d.key];
    const list = groups.get(d.group) ?? [];
    list.push({ key: d.key, label: d.label, level: lv, badge: n && n > 0 ? n : null });
    groups.set(d.group, list);
  }
  return GROUP_ORDER.filter((g) => groups.has(g)).map((g) => ({ key: g, label: GROUP_LABELS[g] ?? g, modules: groups.get(g) ?? [] }));
}

export function roleCards(kind: OrgKind): RoleCard[] {
  return rolesFor(kind).map((r) => {
    const card: RoleCard = { key: r.key, label: r.label, summary: r.summary, note: r.note, can: [], view: [], cannot: [] };
    for (const d of modulesFor(kind)) {
      const lv = levelOf(kind, r.key, d.key);
      if (RANK[lv] >= RANK.EDIT) card.can.push(d.detail);
      else if (lv === 'VIEW') card.view.push(d.detail);
      else card.cannot.push(d.detail);
    }
    return card;
  });
}

export const SHIPMENT_LABELS: Record<ShipmentStatus, string> = {
  PENDING_PAYMENT: 'Awaiting payment', PLACED: 'New', CONFIRMED: 'Confirmed', PACKED: 'Packed', READY: 'Ready to ship', PICKED_UP: 'Picked up',
  IN_TRANSIT: 'In transit', OUT_FOR_DELIVERY: 'Out for delivery', DELIVERY_FAILED: 'Delivery failed', DELIVERED: 'Delivered', RETURNED: 'Returned',
  CANCELLED: 'Cancelled'
};

export const BOOKING_LABELS: Record<BookingStatus, string> = {
  PENDING_PAYMENT: 'Awaiting payment', REQUESTED: 'Awaiting provider', CONFIRMED: 'Confirmed', RESCHEDULE_REQUESTED: 'New time requested',
  RESCHEDULE_PROPOSED: 'Provider proposed a new time', COMPLETED: 'Completed', NO_SHOW: 'No-show', DECLINED: 'Declined by provider',
  EXPIRED: 'Expired · not confirmed', CANCELLED_CUSTOMER: 'Cancelled by customer', CANCELLED_PROVIDER: 'Cancelled by provider'
};

export function allowedBookingActions(status: BookingStatus, startAt: string, now: Date): BookingAction[] {
  if (status === 'REQUESTED' || status === 'RESCHEDULE_REQUESTED') return ['CONFIRM', 'DECLINE', 'PROPOSE'];
  if (status === 'CONFIRMED') return new Date(startAt) < now ? ['COMPLETE', 'NO_SHOW'] : ['PROPOSE', 'CANCEL'];
  if (status === 'RESCHEDULE_PROPOSED') return ['CANCEL'];
  return [];
}

const hoursLabel = (h: number): string => (h >= 48 && h % 24 === 0 ? `${h / 24} days` : h === 1 ? '1 hour' : `${h} hours`);

type FullPolicy = Required<CancellationPolicyInput>;

export function presetPolicy(kind: CancellationPolicyInput['kind']): FullPolicy | null {
  switch (kind) {
    case 'FLEXIBLE':
      return { kind, freeHours: 24, partialRefund: false, partialFromHours: 0, partialPercent: 1, noShow: 'DEPOSIT', rescheduleHours: 24, maxReschedules: 2 };
    case 'MODERATE':
      return { kind, freeHours: 48, partialRefund: true, partialFromHours: 24, partialPercent: 50, noShow: 'DEPOSIT', rescheduleHours: 48, maxReschedules: 2 };
    case 'STRICT':
      return { kind, freeHours: 168, partialRefund: false, partialFromHours: 0, partialPercent: 1, noShow: 'DEPOSIT', rescheduleHours: 168, maxReschedules: 2 };
    default:
      return null;
  }
}

export function describePolicy(input: FullPolicy): CancellationPolicy {
  const p = presetPolicy(input.kind) ?? input;
  const label = { FLEXIBLE: 'Flexible', MODERATE: 'Moderate', STRICT: 'Strict', CUSTOM: 'Custom' }[p.kind];
  const partial = p.partialRefund && p.partialPercent > 0 && p.partialFromHours < p.freeHours;
  let summary = `Free cancellation up to ${hoursLabel(p.freeHours)} before.`;
  const timeline = [{ label: `More than ${hoursLabel(p.freeHours)} before`, refundPercent: 100 }];
  if (partial) {
    summary += ` ${hoursLabel(p.partialFromHours)}–${hoursLabel(p.freeHours)} before: ${p.partialPercent}% of the deposit back.`;
    timeline.push({ label: `${hoursLabel(p.partialFromHours)}–${hoursLabel(p.freeHours)} before`, refundPercent: p.partialPercent });
    timeline.push({ label: `Under ${hoursLabel(p.partialFromHours)} before`, refundPercent: 0 });
  } else {
    timeline.push({ label: `Under ${hoursLabel(p.freeHours)} before`, refundPercent: 0 });
  }
  summary += ' Later than that the deposit is kept.';
  if (p.noShow === 'FULL') summary += ' No-shows are charged the full price.';
  summary += ` Reschedule free up to ${hoursLabel(p.rescheduleHours)} before (max ${p.maxReschedules}).`;
  return { ...p, label, summary, timeline };
}

/** Take-home after AfroTransact's commission (10% on services) and Stripe card fees (2.9% + 30¢). */
export function takeHome(priceMinor: number) {
  const commissionMinor = Math.round(priceMinor * 0.1);
  const cardFeesMinor = Math.round(priceMinor * 0.029) + 30;
  return { commissionPercent: 10, commissionMinor, cardFeesMinor, takeHomeMinor: priceMinor - commissionMinor - cardFeesMinor };
}
