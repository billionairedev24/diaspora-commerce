/* Pre-orders module (S274): access from the staff access model, design copy, formatting and pill styles. */
import { type AccessLevel } from '@afrotransact/api-client';
import { dateAtNoon, type PreorderOrderStatus, type PreorderStatus } from '@afrotransact/api-client/staff-preorders';

export type PreorderRole = 'manager' | 'cs' | 'analyst' | 'viewer';

export interface PreorderAccess {
  role: PreorderRole;
  /** Open, edit, publish, pause, upload creatives. */
  canEdit: boolean;
  /** Master switch, areas, fallback, placements, payment & fees (Full level only). */
  canSettings: boolean;
  canExport: boolean;
  canLate: boolean;
  roleNote: string;
}

const ROLE_NOTE: Record<PreorderRole, string> = {
  manager: 'Pre-order manager: can open, edit, pause and publish pre-orders, upload creatives, export data and add late orders.',
  cs: 'Customer service: can view pre-orders and add late orders for customers who call or chat. Can’t export or edit.',
  analyst: 'Analyst: view only, plus exports and analytics.',
  viewer: 'Viewer: read-only.'
};

/**
 * The Pre-orders column of the staff access model: Full / Edit = pre-order manager (settings only with Full), Act
 * (APPROVE) = customer service (view + late add), View = view; finance and auditor also hold preorders:export
 * (analyst). The BFF checks every write again.
 */
export function preorderAccess(level: AccessLevel | null, has: (permission: string) => boolean): PreorderAccess {
  const manager = (level === 'FULL' || level === 'EDIT') && has('preorders:manage');
  const role: PreorderRole = manager ? 'manager' : level === 'APPROVE' || has('preorders:late-add') ? 'cs' : has('preorders:export') ? 'analyst' : 'viewer';
  return {
    role,
    canEdit: manager,
    canSettings: level === 'FULL',
    canExport: has('preorders:export'),
    canLate: has('preorders:late-add'),
    roleNote: ROLE_NOTE[role]
  };
}

export const NO_EDIT_TITLE = 'Only pre-order managers can open a pre-order';
export const NO_SETTINGS_TITLE = 'Only full Pre-orders access can change pre-order settings';

/* Pills: status colours from the design's PILL map (background, text). */
export const STATUS_PILL: Record<PreorderStatus, string> = {
  OPEN: 'bg-green-50 text-green-800',
  UPCOMING: 'bg-ink-100 text-ink-800',
  CLOSED: 'bg-gold-100 text-gold-900',
  DELIVERED: 'bg-ink-100 text-ink-600',
  DRAFT: 'bg-ink-50 text-ink-600',
  PAUSED: 'bg-red-50 text-red-700'
};
export const AREA_OFF_PILL = 'bg-red-50 text-red-700';
export const ORDER_PILL: Record<PreorderOrderStatus, string> = { PAID: 'bg-green-50 text-green-800', LATE_ADD: 'bg-gold-100 text-gold-900', CANCELLED: 'bg-red-50 text-red-700' };

/* Dates, as the design prints them (en-US: "Oct 6", "Sun, Oct 25", "Tue, Oct 6, 9:00 AM"). */
const parse = (v: string) => (v.length === 10 ? dateAtNoon(v) : Date.parse(v));
export const fS = (v: string | number) => new Date(typeof v === 'number' ? v : parse(v)).toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
export const fD = (v: string | number) => new Date(typeof v === 'number' ? v : parse(v)).toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' });
export const fDT = (v: string) => `${fD(v)}, ${new Date(parse(v)).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' })}`;
export const fTime = (v: string) => new Date(parse(v)).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' });

export const num = (n: number) => n.toLocaleString('en-US');
/** Whole currency units ("$12,345") as the list and KPIs show revenue. */
export const wholeMoney = (minor: number, currency = 'USD') =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency, maximumFractionDigits: 0, minimumFractionDigits: 0 }).format(Math.round(minor / 100));
export const money2 = (minor: number, currency = 'USD') => new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(minor / 100);

/** Minor units as an editable amount ("7.99"). */
export const amountText = (minor: number | null): string => (minor === null ? '' : `${Math.floor(minor / 100)}.${String(minor % 100).padStart(2, '0')}`);

/** An amount typed by people ("7.99", "$7", "") → minor units; null when blank, NaN when not an amount. */
export function parseAmount(text: string): number | null {
  const t = text.trim();
  if (!t) return null;
  const m = /^\$?\s*(\d{1,6})(?:\.(\d{0,2}))?$/.exec(t);
  if (!m) return Number.NaN;
  return Number(m[1]) * 100 + Number((m[2] ?? '').padEnd(2, '0'));
}

/** ISO instant ↔ datetime-local input value (browser time zone). */
export function toLocalInput(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}
export const fromLocalInput = (v: string): string => {
  const t = new Date(v).getTime();
  return Number.isNaN(t) ? '' : new Date(t).toISOString();
};

export const href = {
  list: (tab?: string) => (tab && tab !== 'all' ? `/preorders?status=${tab}` : '/preorders'),
  window: (id: string, tab?: string) => `/preorders/${encodeURIComponent(id)}${tab && tab !== 'overview' ? `?tab=${tab}` : ''}`,
  edit: (id: string) => `/preorders/${encodeURIComponent(id)}/edit`,
  create: () => '/preorders/new',
  settings: () => '/preorders/settings'
};

/** The late-add pay-now option (settings and the late-add dialog): the SMS opens AfroTransact's own payment page,
 *  where the customer pays with Stripe Elements; customers never get a Stripe-hosted page. */
export const PAY_NOW_LINK_LABEL = 'Send a pay-now link by SMS (opens the AfroTransact payment page)';
