/* Staff console rules shared by the mock BFF: module levels from the platform matrix, the sidebar, module copy and
   refund limits per role. They mirror the Go BFF (internal/domain/staff.go) so mocks and the real BFF agree. */
import { type AccessLevel } from '../../schemas/seller';
import { type ResourceKey, type StaffModuleKey, type StaffNavGroup, type StaffNavGroupKey } from '../../schemas/staff';
import { MATRIX_MODULES, type MatrixModule, ROLE_LABELS, ROLE_MATRIX, ROLE_PERMISSIONS } from './rbac-data';

const RANK: Record<AccessLevel, number> = { VIEW: 1, EDIT: 2, APPROVE: 3, FULL: 4 };
const CODE: Record<string, AccessLevel | null> = { V: 'VIEW', E: 'EDIT', A: 'APPROVE', F: 'FULL', '-': null };

export const MODULE_COPY: Record<StaffModuleKey, { label: string; description: string }> = {
  dash: { label: 'Dashboard', description: 'Marketplace health for the USA market today.' },
  exports: { label: 'Exports', description: 'Large exports build on the server; download links last 24 hours.' },
  orders: { label: 'Orders & shipments', description: 'All marketplace and AfroTransact Retail orders.' },
  catalog: { label: 'Listing moderation', description: 'AI-flagged listings awaiting a human decision.' },
  inventory: { label: 'Inventory', description: 'AfroTransact-owned and FBA stock by fulfilment centre, lot by lot.' },
  retail: { label: 'AfroTransact Retail (1P)', description: 'Purchase orders for AfroTransact-owned and AfroTransact Brand inventory.' },
  dispatch: { label: 'Delivery dispatch', description: 'AfroTransact Delivery routes, drivers and internal delivery.' },
  apps: {
    label: 'Applications',
    description: 'Seller and service-provider onboarding queue. Every application goes through two people: a reviewer, then a different approver.'
  },
  support: { label: 'Customer support', description: 'Lola hand-offs, customer lookup, refunds within your limit, notifications and shipments.' },
  rbac: { label: 'Users & roles', description: 'Who can do what in every back office. Role changes are audited.' },
  payouts: { label: 'Payouts & ledger', description: 'Weekly Stripe Connect payouts. Holding or releasing money needs Approve.' },
  fees: { label: 'Fees, commissions & tax', description: 'Fee schedule versions and marketplace-facilitator tax rates. Changes apply to new orders only.' },
  marketing: { label: 'Ads & announcements', description: 'Announcement bar, hero carousel, display banners and sponsored placements.' },
  disputes: { label: 'Disputes & returns', description: 'A-to-z claims and returns with SLAs.' },
  audit: { label: 'Audit log', description: 'Immutable, hash-chained record of every privileged action.' },
  markets: { label: 'Markets & locations', description: 'Turn AfroTransact on or off by country and by state.' },
  siteCfg: { label: 'Site content & legal', description: 'Contacts, social links and legal document versions, published without a deployment.' },
  dev: { label: 'Developers', description: 'Identity providers: which social sign-in buttons appear.' }
};

export const NAV: { key: StaffNavGroupKey; label: string; modules: StaffModuleKey[] }[] = [
  { key: 'overview', label: 'Overview', modules: ['dash', 'exports'] },
  { key: 'commerce', label: 'Commerce', modules: ['orders', 'catalog', 'inventory', 'retail', 'dispatch'] },
  { key: 'partners', label: 'Partners', modules: ['apps', 'support', 'rbac'] },
  { key: 'finance', label: 'Finance', modules: ['payouts', 'fees'] },
  { key: 'growth', label: 'Growth', modules: ['marketing'] },
  { key: 'trust', label: 'Trust & compliance', modules: ['disputes', 'audit'] },
  { key: 'settings', label: 'Settings', modules: ['markets', 'siteCfg', 'dev'] }
];

export const RESOURCE_MODULE: Record<ResourceKey, StaffModuleKey> = {
  users: 'rbac', roles: 'rbac', applications: 'apps', orders: 'orders', products: 'catalog', moderation: 'catalog', claims: 'disputes', returns: 'disputes',
  payouts: 'payouts', inventory: 'inventory', 'purchase-orders': 'retail', routes: 'dispatch', 'support-cases': 'support', audit: 'audit', markets: 'markets',
  'site-settings': 'siteCfg', 'legal-documents': 'siteCfg', announcements: 'marketing', placements: 'marketing', 'identity-providers': 'dev',
  'fee-schedules': 'fees', 'tax-rates': 'fees'
};

/** Refund ceiling per platform role in minor units (null = no limit). Roles not listed may not refund. */
export const REFUND_LIMITS: Record<string, number | null> = {
  'platform.super_admin': null,
  'platform.finance': null,
  'platform.operations_manager': 100_000,
  'platform.trust_safety': 50_000,
  'platform.customer_support': 25_000
};

export const roleLabel = (role: string): string => ROLE_LABELS[role] ?? role;

export function permissionsOf(roles: string[]): string[] {
  return [...new Set(roles.flatMap((r) => ROLE_PERMISSIONS[r] ?? []))].sort();
}

function matrixLevel(roles: string[], module: MatrixModule): AccessLevel | null {
  const idx = MATRIX_MODULES.indexOf(module);
  let best: AccessLevel | null = null;
  roles.forEach((r) => {
    const lv = CODE[ROLE_MATRIX[r]?.[idx] ?? '-'] ?? null;
    if (lv && (!best || RANK[lv] > RANK[best])) best = lv;
  });
  return best;
}

/** The staff member's level on a console module: the best level over their roles (support and exports are derived). */
export function levelOf(roles: string[], module: StaffModuleKey): AccessLevel | null {
  if (module === 'exports') return matrixLevel(roles, 'dash');
  if (module === 'support') {
    const perms = permissionsOf(roles);
    return perms.includes('support:respond') ? 'EDIT' : perms.includes('users:read') ? 'VIEW' : null;
  }
  return matrixLevel(roles, module);
}

export function allows(roles: string[], module: StaffModuleKey, min: AccessLevel = 'VIEW'): boolean {
  const lv = levelOf(roles, module);
  return lv !== null && RANK[lv] >= RANK[min];
}

export function refundLimit(roles: string[]): number | null {
  let limit = 0;
  for (const r of roles) {
    if (!(r in REFUND_LIMITS)) continue;
    const l = REFUND_LIMITS[r];
    if (l === null || l === undefined) return null;
    limit = Math.max(limit, l);
  }
  return limit;
}

export function navigation(roles: string[], badges: Partial<Record<StaffModuleKey, number>> = {}): StaffNavGroup[] {
  return NAV.map((g) => ({
    key: g.key,
    label: g.label,
    modules: g.modules.flatMap((key) => {
      const level = levelOf(roles, key);
      return level ? [{ key, label: MODULE_COPY[key].label, description: MODULE_COPY[key].description, level, badge: badges[key] ?? null }] : [];
    })
  })).filter((g) => g.modules.length > 0);
}
