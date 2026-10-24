import { type StaffModuleKey, type StaffSession } from '@afrotransact/api-client';

/**
 * Settings: everything that sets how AfroTransact runs, in one place. Each section is hosted by an existing console
 * module (its tables and forms are unchanged, and the module's access level still decides who may see or change it).
 */
export interface SettingsSection {
  key: string;
  label: string;
  description: string;
  module: StaffModuleKey;
  group: 'platform' | 'money';
}

export const SETTINGS_SECTIONS: SettingsSection[] = [
  { key: 'general', label: 'General', description: 'Contact details, social links, help centre and the legal documents shown on the site.', module: 'siteCfg', group: 'platform' },
  { key: 'markets', label: 'Markets & locations', description: 'Turn AfroTransact on or off by country and by state.', module: 'markets', group: 'platform' },
  { key: 'notifications', label: 'Notifications', description: 'The email, SMS, push and WhatsApp messages the platform sends: preview, history and new versions.', module: 'notifications', group: 'platform' },
  { key: 'signin', label: 'Sign-in & integrations', description: 'Which social sign-in buttons appear on sign-in.', module: 'dev', group: 'platform' },
  { key: 'identity', label: 'Identity & access', description: 'OAuth clients, sign-ins and tokens, signing keys, the token inspector and the sign-in settings of the identity service.', module: 'identity', group: 'platform' },
  { key: 'payments', label: 'Payments & payouts', description: 'How long money is held, the smallest payout, and which Stripe the platform uses.', module: 'payouts', group: 'money' },
  { key: 'fees', label: 'Fees & commissions', description: 'Fee schedule versions, commission by category and plan, connection fees, negotiated rates and delivery pricing.', module: 'fees', group: 'money' },
  { key: 'tax', label: 'Sales tax', description: 'Who calculates sales tax, the state and local rates, and what each item is for tax.', module: 'fees', group: 'money' },
  { key: 'plans', label: 'Plans & billing', description: 'How sellers pay their plan fee.', module: 'plans', group: 'money' }
];

export const SETTINGS_GROUPS: { key: SettingsSection['group']; label: string }[] = [
  { key: 'platform', label: 'Platform' },
  { key: 'money', label: 'Money' }
];

/** Modules whose own sidebar entry moved into Settings (their old addresses redirect to the section). */
export const SETTINGS_HOSTED: StaffModuleKey[] = ['markets', 'siteCfg', 'dev', 'identity', 'notifications', 'fees'];

export const isSettingsPath = (pathname: string): boolean => pathname === '/settings' || pathname.startsWith('/settings/');

/** The sections this staff member's roles can open (the BFF returns only the modules they may open). */
export function visibleSections(session: StaffSession): SettingsSection[] {
  const open = new Set(session.navGroups.flatMap((g) => g.modules.map((m) => m.key)));
  return SETTINGS_SECTIONS.filter((s) => open.has(s.module));
}

export const settingsHref = (section: string): string => `/settings?section=${encodeURIComponent(section)}`;
