import { type ResourceKey, type StaffModuleKey } from '@afrotransact/api-client';
import { type Icon } from '@phosphor-icons/react';
import {
  Bank,
  BellRinging,
  CalendarCheck,
  Code,
  CreditCard,
  DownloadSimple,
  GlobeHemisphereWest,
  Headset,
  IdentificationCard,
  Key,
  ListMagnifyingGlass,
  Megaphone,
  Package,
  Path,
  Percent,
  Receipt,
  Scales,
  ShieldCheck,
  SquaresFour,
  Stack,
  UsersThree
} from '@phosphor-icons/react/ssr';

/** Sidebar icon of each module (the Backoffice prototype's Phosphor icons). */
export const MODULE_ICONS: Record<StaffModuleKey, Icon> = {
  dash: SquaresFour, exports: DownloadSimple, orders: Receipt, catalog: ShieldCheck, inventory: Stack, retail: Package, dispatch: Path,
  orgs: UsersThree, apps: IdentificationCard, support: Headset, rbac: UsersThree, payouts: Bank, fees: Percent, marketing: Megaphone, disputes: Scales,
  audit: ListMagnifyingGlass, markets: GlobeHemisphereWest, siteCfg: Scales, dev: Code, identity: Key, notifications: BellRinging, preorders: CalendarCheck,
  plans: CreditCard
};

/** URL path of each module. */
export const MODULE_PATHS: Record<StaffModuleKey, string> = {
  dash: '/', exports: '/exports', orders: '/orders', catalog: '/moderation', inventory: '/inventory', retail: '/retail', dispatch: '/dispatch',
  orgs: '/organisations', apps: '/applications', support: '/support', rbac: '/users', payouts: '/payouts', fees: '/fees', marketing: '/marketing', disputes: '/disputes',
  audit: '/audit', markets: '/markets', siteCfg: '/site-content', dev: '/developers', identity: '/identity', notifications: '/notifications', preorders: '/preorders',
  plans: '/plans'
};

export const hrefOf = (key: StaffModuleKey): string => MODULE_PATHS[key];

/**
 * Where a record opens in the console: the module page that shows its table (on the right tab when the module has several)
 * with the record's sheet open (?open=<id>). Null for a table no module shows.
 */
export function recordHref(resource: string, id: string): string | null {
  for (const [module, tables] of Object.entries(MODULE_TABLES) as [StaffModuleKey, { resource: ResourceKey }[]][]) {
    if (!tables.some((t) => t.resource === resource)) continue;
    const params = new URLSearchParams();
    if (tables.length > 1) params.set('tab', resource);
    params.set('open', id);
    return `${hrefOf(module)}?${params.toString()}`;
  }
  return null;
}

/** Module that owns a pathname (null for unknown paths). */
export function moduleOf(pathname: string): StaffModuleKey | null {
  const seg = `/${pathname.split('/').filter(Boolean)[0] ?? ''}`;
  if (seg === '/') return 'dash';
  const hit = (Object.entries(MODULE_PATHS) as [StaffModuleKey, string][]).find(([, p]) => p === seg);
  return hit ? hit[0] : null;
}

/** The server-driven tables each module shows (one tab per table). */
export const MODULE_TABLES: Partial<Record<StaffModuleKey, { resource: ResourceKey; label: string }[]>> = {
  orders: [{ resource: 'orders', label: 'Orders' }],
  catalog: [
    { resource: 'moderation', label: 'Moderation queue' },
    { resource: 'products', label: 'Products' }
  ],
  inventory: [{ resource: 'inventory', label: 'Lots' }],
  retail: [{ resource: 'purchase-orders', label: 'Purchase orders' }],
  dispatch: [{ resource: 'routes', label: 'Routes' }],
  orgs: [
    { resource: 'organisations', label: 'Organisations' },
    { resource: 'connect-accounts', label: 'Stripe Connect' }
  ],
  apps: [{ resource: 'applications', label: 'Applications' }],
  rbac: [
    { resource: 'users', label: 'People' },
    { resource: 'roles', label: 'Roles' }
  ],
  payouts: [
    { resource: 'payouts', label: 'Payout requests' },
    { resource: 'reconciliation', label: 'Differences' },
    { resource: 'ledger-adjustments', label: 'Ledger adjustments' },
    { resource: 'reconciliation-runs', label: 'Daily runs' },
    { resource: 'stripe-lookup', label: 'Stripe lookup' }
  ],
  fees: [
    { resource: 'fee-schedules', label: 'Fee schedules' },
    { resource: 'tax-rates', label: 'Tax rates' }
  ],
  marketing: [
    { resource: 'announcements', label: 'Announcements' },
    { resource: 'placements', label: 'Placements & ads' },
    { resource: 'coupons', label: 'Platform coupons' }
  ],
  disputes: [
    { resource: 'claims', label: 'A-to-z claims' },
    { resource: 'returns', label: 'Returns' }
  ],
  audit: [{ resource: 'audit', label: 'Audit log' }],
  markets: [{ resource: 'markets', label: 'Countries' }],
  siteCfg: [
    { resource: 'maintenance', label: 'Maintenance mode' },
    { resource: 'site-settings', label: 'Contacts & social links' },
    { resource: 'legal-documents', label: 'Legal documents' },
    { resource: 'help-articles', label: 'Help center' },
    { resource: 'privacy-requests', label: 'Privacy requests' }
  ],
  dev: [{ resource: 'identity-providers', label: 'Identity providers' }],
  // Identity & access (S291) has its own screen (tabs, plus the token inspector); the list lets recordHref find the right tab.
  identity: [
    { resource: 'oauth-clients', label: 'OAuth clients' },
    { resource: 'identity-sessions', label: 'Sessions & tokens' },
    { resource: 'signing-keys', label: 'Signing keys' },
    { resource: 'auth-settings', label: 'Settings' }
  ]
};

/** Every console module key (URL segments are validated against these). */
export const MODULE_KEYS = Object.keys(MODULE_PATHS) as StaffModuleKey[];
