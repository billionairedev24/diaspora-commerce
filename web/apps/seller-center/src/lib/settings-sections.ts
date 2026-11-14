import { type AccessLevel, type ComplianceSummary, type ModuleKey, type ShipFromAddress } from '@afrotransact/api-client';

import { type MessageKey, type Vars } from '@/i18n/t';

/**
 * Seller Center settings, one section per page. Each setting lives in exactly one section; the section's `module` is the
 * permission that gates it (the access matrix is unchanged: a section needs at least View on its module to open and Edit
 * to save), and `null` means a personal setting any signed-in member may change.
 */
export type SettingsKey = 'business' | 'locations' | 'shipping' | 'pickup' | 'payouts' | 'team' | 'notifications' | 'tax' | 'documents' | 'language';

export interface SettingsSection {
  key: SettingsKey;
  href: `/settings/${string}`;
  module: ModuleKey | null;
  /** Sections about shipping goods are for sellers only; providers go to people's homes or run an appointment calendar. */
  sellerOnly: boolean;
}

/** In the order the list and the sub-nav show them: the business, where it is, how orders reach customers, money, people, personal. */
export const SETTINGS_SECTIONS: readonly SettingsSection[] = [
  { key: 'business', href: '/settings/business', module: 'team', sellerOnly: false },
  { key: 'locations', href: '/settings/locations', module: 'orders', sellerOnly: true },
  { key: 'shipping', href: '/settings/shipping', module: 'orders', sellerOnly: true },
  { key: 'pickup', href: '/settings/pickup', module: 'orders', sellerOnly: true },
  { key: 'payouts', href: '/settings/payouts', module: 'payouts', sellerOnly: false },
  { key: 'team', href: '/settings/team', module: 'team', sellerOnly: false },
  { key: 'notifications', href: '/settings/notifications', module: null, sellerOnly: false },
  { key: 'tax', href: '/settings/tax', module: 'payouts', sellerOnly: false },
  { key: 'documents', href: '/settings/documents', module: 'account-health', sellerOnly: false },
  { key: 'language', href: '/settings/language', module: null, sellerOnly: false }
];

export const sectionOf = (key: SettingsKey): SettingsSection => SETTINGS_SECTIONS.find((s) => s.key === key) ?? SETTINGS_SECTIONS[0]!;

/** The section a pathname is in (null on the landing page or outside Settings). */
export function settingsSectionOf(pathname: string): SettingsKey | null {
  const m = /^\/settings\/([a-z-]+)\/?$/.exec(pathname);
  return SETTINGS_SECTIONS.find((s) => s.key === m?.[1])?.key ?? null;
}

const RANK: Record<AccessLevel, number> = { VIEW: 1, EDIT: 2, APPROVE: 3, FULL: 4 };

/** Sections this member may open: the ones for the organisation's kind whose module they can at least view. */
export function visibleSections(isProvider: boolean, level: (m: ModuleKey) => AccessLevel | null): SettingsSection[] {
  return SETTINGS_SECTIONS.filter((s) => (!isProvider || !s.sellerOnly) && (s.module === null || level(s.module) !== null));
}

/** Whether the member may change the section (personal sections always; others need Edit on the module). */
export function canEditSection(s: SettingsSection, level: (m: ModuleKey) => AccessLevel | null): boolean {
  if (s.module === null) return true;
  const lv = level(s.module);
  return lv !== null && RANK[lv] >= RANK.EDIT;
}

export interface SettingsStatus {
  key: MessageKey;
  vars?: Vars;
  tone: 'good' | 'warn' | 'neutral';
}

export function locationsStatus(addresses: readonly ShipFromAddress[]): SettingsStatus {
  return addresses.length === 0 ? { key: 'sc.settings.status.locationsNone', tone: 'warn' } : { key: 'sc.settings.status.locations', vars: { count: addresses.length }, tone: 'neutral' };
}

/** "Pickup: Off" until a seller turns it on at a location; never "On" from anything but the saved state. */
export function pickupStatus(addresses: readonly ShipFromAddress[]): SettingsStatus {
  const on = addresses.filter((a) => a.customerPickup?.enabled).length;
  if (addresses.length === 0) return { key: 'sc.settings.status.pickupNoLocation', tone: 'neutral' };
  return on === 0 ? { key: 'sc.settings.status.pickupOff', tone: 'neutral' } : { key: 'sc.settings.status.pickupOn', vars: { on, total: addresses.length }, tone: 'good' };
}

export function shippingStatus(addresses: readonly ShipFromAddress[]): SettingsStatus {
  const on = addresses.filter((a) => a.afrotransactPickup.enabled).length;
  return on === 0 ? { key: 'sc.settings.status.shippingSelf', tone: 'neutral' } : { key: 'sc.settings.status.shippingDriver', vars: { on, total: addresses.length }, tone: 'good' };
}

export function documentsStatus(c: Pick<ComplianceSummary, 'openItems' | 'payoutsPaused'>): SettingsStatus {
  return c.openItems === 0 ? { key: 'sc.settings.status.documentsOk', tone: 'good' } : { key: 'sc.settings.status.documentsOpen', vars: { count: c.openItems }, tone: 'warn' };
}

export function payoutsStatus(c: Pick<ComplianceSummary, 'payoutBank' | 'payoutsPaused'>): SettingsStatus {
  if (c.payoutsPaused) return { key: 'sc.settings.status.payoutsPaused', tone: 'warn' };
  return c.payoutBank?.last4 ? { key: 'sc.settings.status.payoutsBank', vars: { bank: c.payoutBank.bankName ?? '', last4: c.payoutBank.last4 }, tone: 'good' } : { key: 'sc.settings.status.payoutsNoBank', tone: 'warn' };
}

/** The business address saved at sign-up, as a ship-from address to start from (only when it is complete). */
export function addressFromBusiness(a: { line1?: string | null; line2?: string | null; city?: string | null; state?: string | null; postalCode?: string | null } | null | undefined): {
  line1: string;
  line2: string;
  city: string;
  state: string;
  postalCode: string;
} | null {
  if (!a?.line1 || !a.city || !a.state || !a.postalCode) return null;
  return { line1: a.line1, line2: a.line2 ?? '', city: a.city, state: a.state, postalCode: a.postalCode };
}
