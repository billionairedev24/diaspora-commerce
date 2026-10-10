import { type ResourceKey, type StaffModuleKey } from '@afrotransact/api-client';
import { type Icon } from '@phosphor-icons/react';
import {
  Bank,
  BellRinging,
  Code,
  DownloadSimple,
  GlobeHemisphereWest,
  Headset,
  IdentificationCard,
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
  apps: IdentificationCard, support: Headset, rbac: UsersThree, payouts: Bank, fees: Percent, marketing: Megaphone, disputes: Scales,
  audit: ListMagnifyingGlass, markets: GlobeHemisphereWest, siteCfg: Scales, dev: Code, notifications: BellRinging
};

/** URL path of each module. */
export const MODULE_PATHS: Record<StaffModuleKey, string> = {
  dash: '/', exports: '/exports', orders: '/orders', catalog: '/moderation', inventory: '/inventory', retail: '/retail', dispatch: '/dispatch',
  apps: '/applications', support: '/support', rbac: '/users', payouts: '/payouts', fees: '/fees', marketing: '/marketing', disputes: '/disputes',
  audit: '/audit', markets: '/markets', siteCfg: '/site-content', dev: '/developers', notifications: '/notifications'
};

export const hrefOf = (key: StaffModuleKey): string => MODULE_PATHS[key];

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
  apps: [{ resource: 'applications', label: 'Applications' }],
  rbac: [
    { resource: 'users', label: 'People' },
    { resource: 'roles', label: 'Roles' }
  ],
  payouts: [{ resource: 'payouts', label: 'Payout requests' }],
  fees: [
    { resource: 'fee-schedules', label: 'Fee schedules' },
    { resource: 'tax-rates', label: 'Tax rates' }
  ],
  marketing: [
    { resource: 'announcements', label: 'Announcements' },
    { resource: 'placements', label: 'Placements & ads' }
  ],
  disputes: [
    { resource: 'claims', label: 'A-to-z claims' },
    { resource: 'returns', label: 'Returns' }
  ],
  audit: [{ resource: 'audit', label: 'Audit log' }],
  markets: [{ resource: 'markets', label: 'Countries' }],
  siteCfg: [
    { resource: 'site-settings', label: 'Contacts & social links' },
    { resource: 'legal-documents', label: 'Legal documents' }
  ],
  dev: [{ resource: 'identity-providers', label: 'Identity providers' }]
};

/** Every console module key (URL segments are validated against these). */
export const MODULE_KEYS = Object.keys(MODULE_PATHS) as StaffModuleKey[];
