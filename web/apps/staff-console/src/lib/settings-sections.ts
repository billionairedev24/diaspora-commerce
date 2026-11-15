import { type AccessLevel, type StaffModuleKey, type StaffSession } from '@afrotransact/api-client';

import { type MessageKey, type Vars } from '@/i18n/t';

/**
 * Staff Settings, one section per page (the seller pattern, docs/engineering/staff-settings.md). Each setting lives in exactly one
 * section; the section's `module` is the console module that gates it (View opens it, Edit saves; the BFF and the service behind
 * it enforce the same), `permission` is a permission the staff member must also hold, and `module: null` is personal or
 * informational. A section with `own: false` is a row that opens a page elsewhere (the audit log, sales tax review settings,
 * the person's own language): it is listed here so there is one place to look, never duplicated.
 */
export type SettingsKey =
  | 'staff'
  | 'access'
  | 'security'
  | 'sign-in'
  | 'audit'
  | 'markets'
  | 'language'
  | 'notifications'
  | 'published-pages'
  | 'general'
  | 'ai'
  | 'application-review'
  | 'payments'
  | 'credit'
  | 'membership'
  | 'referrals'
  | 'fees'
  | 'shipping'
  | 'delivery-fees'
  | 'run-economics'
  | 'exchange-rates'
  | 'plans'
  | 'tax-review';

export type SettingsGroup = 'people' | 'platform' | 'money';

export interface SettingsSection {
  key: SettingsKey;
  href: string;
  group: SettingsGroup;
  module: StaffModuleKey | null;
  permission?: string;
  /** The section is a page of its own under /settings (false: the row links to a page elsewhere). */
  own: boolean;
}

const S = (key: SettingsKey, group: SettingsGroup, module: StaffModuleKey | null, extra: Partial<SettingsSection> = {}): SettingsSection => ({
  key,
  href: `/settings/${key}`,
  group,
  module,
  own: true,
  ...extra
});

/** In the order the list shows them. */
export const SETTINGS_SECTIONS: readonly SettingsSection[] = [
  S('staff', 'people', 'rbac', { permission: 'access:read' }),
  S('access', 'people', 'rbac', { permission: 'access:read' }),
  S('security', 'people', 'identity'),
  S('sign-in', 'people', 'dev'),
  S('audit', 'people', 'audit', { href: '/audit', own: false }),
  S('markets', 'platform', 'markets'),
  S('language', 'platform', null, { href: '/account/preferences', own: false }),
  S('notifications', 'platform', 'notifications'),
  S('ai', 'platform', null, { permission: 'ai:manage' }),
  S('published-pages', 'platform', 'siteCfg'),
  S('general', 'platform', 'siteCfg'),
  S('application-review', 'platform', 'apps'),
  S('payments', 'money', 'payouts'),
  S('credit', 'money', 'payouts'),
  S('membership', 'money', 'payouts'),
  S('referrals', 'money', 'payouts'),
  S('fees', 'money', 'fees'),
  S('shipping', 'money', 'fees'),
  S('delivery-fees', 'money', 'preorders'),
  S('run-economics', 'money', 'preorders'),
  S('exchange-rates', 'money', 'inventory'),
  S('plans', 'money', 'plans'),
  S('tax-review', 'money', 'tax', { href: '/tax?tab=tax-review-settings', own: false })
];

export const SETTINGS_GROUPS: readonly { key: SettingsGroup; labelKey: MessageKey }[] = [
  { key: 'people', labelKey: 'st.sx.group.people' },
  { key: 'platform', labelKey: 'st.sx.group.platform' },
  { key: 'money', labelKey: 'st.sx.group.money' }
];

/** Modules whose own sidebar entry moved into Settings (their old addresses redirect to the section). */
export const SETTINGS_HOSTED: StaffModuleKey[] = ['markets', 'siteCfg', 'dev', 'identity', 'notifications', 'fees', 'rbac'];

export const isSettingsPath = (pathname: string): boolean => pathname === '/settings' || pathname.startsWith('/settings/');

export const sectionOf = (key: SettingsKey): SettingsSection => SETTINGS_SECTIONS.find((s) => s.key === key) ?? SETTINGS_SECTIONS[0]!;

/** The section a pathname is in (null on the Settings home, outside Settings, or on a page such as the page editor). */
export function settingsSectionOf(pathname: string): SettingsSection | null {
  const m = /^\/settings\/([a-z-]+)\/?$/.exec(pathname);
  return SETTINGS_SECTIONS.find((s) => s.own && s.key === m?.[1]) ?? null;
}

/** Where a section's page is (the `section` of the old `/settings?section=` addresses may be an old key). */
export const settingsHref = (section: string): string => {
  const key = LEGACY[section] ?? section;
  return SETTINGS_SECTIONS.find((s) => s.key === key)?.href ?? '/settings';
};

/** Keys of the old `?section=` addresses that are called something else now. */
export const LEGACY: Record<string, SettingsKey> = { identity: 'security', signin: 'sign-in' };

const RANK: Record<AccessLevel, number> = { VIEW: 1, EDIT: 2, APPROVE: 3, FULL: 4 };

/** The staff member's level on the section's module (personal and informational sections: full). */
export function levelOn(session: StaffSession, s: SettingsSection): AccessLevel | null {
  if (s.module === null) return 'FULL';
  for (const g of session.navGroups) {
    const m = g.modules.find((x) => x.key === s.module);
    if (m) return m.level;
  }
  return null;
}

const holds = (session: StaffSession, s: SettingsSection): boolean => !s.permission || session.permissions.includes(s.permission);

/** Whether the staff member may open the section (View on its module, and the permission it names). */
export const canOpenSection = (session: StaffSession, s: SettingsSection): boolean => levelOn(session, s) !== null && holds(session, s);

/** Whether they may save in it (Edit on the module; the BFF checks the permission the change needs). */
export function canEditSection(session: StaffSession, s: SettingsSection): boolean {
  const lv = levelOn(session, s);
  return lv !== null && RANK[lv] >= RANK.EDIT && holds(session, s);
}

/** The sections this staff member may open. */
export const visibleSections = (session: StaffSession): SettingsSection[] => SETTINGS_SECTIONS.filter((s) => canOpenSection(session, s));

export interface SettingsStatus {
  key: MessageKey;
  vars?: Vars;
  tone: 'good' | 'warn' | 'neutral';
}
