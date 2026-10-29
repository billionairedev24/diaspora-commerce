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
  catalog: { label: 'Catalog', description: 'Listing moderation, products, the category tree with its product-type details, and seller category requests.' },
  inventory: { label: 'Inventory', description: 'AfroTransact-owned and FBA stock by fulfilment centre, lot by lot.' },
  retail: { label: 'AfroTransact Retail (1P)', description: 'Purchase orders for AfroTransact-owned and AfroTransact Brand inventory.' },
  dispatch: { label: 'Delivery dispatch', description: 'AfroTransact Delivery routes, drivers and internal delivery.' },
  orgs: { label: 'Organisations', description: 'Seller and provider organisations: status, Stripe Connect accounts and enforcement.' },
  apps: {
    label: 'Applications',
    description: 'Seller and service-provider onboarding queue. Every application goes through two people: a reviewer, then a different approver.'
  },
  support: { label: 'Customer support', description: 'Lola hand-offs, customer lookup, refunds within your limit, notifications and shipments.' },
  rbac: { label: 'Users & roles', description: 'Who can do what in every back office. Role changes are audited.' },
  payouts: { label: 'Payouts & ledger', description: 'Weekly Stripe Connect payouts. Holding or releasing money needs Approve.' },
  fees: { label: 'Fees, commissions & tax', description: 'Fee schedule versions and marketplace-facilitator tax rates. Changes apply to new orders only.' },
  plans: {
    label: 'Plans & billing',
    description: 'Seller and provider plans, trials, rewards and billing adjustments. AfroTransact owns them; Stripe executes the charges.'
  },
  marketing: { label: 'Marketing', description: 'Announcements, placements & ads, and platform checkout coupons.' },
  disputes: { label: 'Disputes & returns', description: 'A-to-z claims and returns with SLAs.' },
  audit: { label: 'Audit log', description: 'Immutable, hash-chained record of every privileged action.' },
  markets: { label: 'Markets & locations', description: 'Turn AfroTransact on or off by country and by state.' },
  siteCfg: { label: 'Site content & legal', description: 'Contacts, social links and legal document versions, published without a deployment.' },
  dev: { label: 'Developers', description: 'Identity providers: which social sign-in buttons appear.' },
  identity: {
    label: 'Identity & access',
    description: 'Developer tools for the identity service: OAuth clients, sessions and tokens, signing keys, the token inspector and sign-in settings.'
  },
  notifications: {
    label: 'Notifications',
    description: 'Email, SMS, push and WhatsApp templates: live preview with production-shaped sample data, version history and new versions.'
  },
  preorders: {
    label: 'Pre-orders',
    description: 'Open pre-order windows by city with opening, closing and delivery dates. Upload advertorials, watch every pre-order live, export and add late orders.'
  }
};

export const NAV: { key: StaffNavGroupKey; label: string; modules: StaffModuleKey[] }[] = [
  { key: 'overview', label: 'Overview', modules: ['dash', 'exports'] },
  { key: 'commerce', label: 'Commerce', modules: ['orders', 'catalog', 'inventory', 'retail', 'dispatch', 'preorders'] },
  { key: 'partners', label: 'Partners', modules: ['apps', 'support', 'rbac'] },
  { key: 'finance', label: 'Finance', modules: ['payouts', 'fees', 'plans'] },
  { key: 'growth', label: 'Growth', modules: ['marketing'] },
  { key: 'trust', label: 'Trust & compliance', modules: ['disputes', 'audit'] },
  { key: 'settings', label: 'Settings', modules: ['markets', 'siteCfg', 'dev', 'identity', 'notifications'] }
];

export const RESOURCE_MODULE: Record<ResourceKey, StaffModuleKey> = {
  users: 'rbac', roles: 'rbac', applications: 'apps', orders: 'orders', products: 'catalog', 'category-requests': 'catalog', moderation: 'catalog', 'product-reviews': 'catalog', claims: 'disputes', returns: 'disputes',
  payouts: 'payouts', inventory: 'inventory', 'purchase-orders': 'retail', routes: 'dispatch', 'support-cases': 'support', audit: 'audit', markets: 'markets',
  'site-settings': 'siteCfg', 'legal-documents': 'siteCfg', 'help-articles': 'siteCfg', 'privacy-requests': 'siteCfg', announcements: 'marketing', placements: 'marketing', 'identity-providers': 'dev',
  'fee-schedules': 'fees', 'tax-rates': 'fees', 'tax-codes': 'fees', 'tax-rules': 'fees', 'tax-review': 'fees', 'dispatch-shipments': 'dispatch', 'sos-alerts': 'dispatch', 'vehicle-defects': 'dispatch', 'driver-events': 'dispatch', 'pickup-locations': 'dispatch',
  coupons: 'marketing', organisations: 'orgs', 'connect-accounts': 'orgs',
  reconciliation: 'payouts', 'reconciliation-runs': 'payouts', 'ledger-adjustments': 'payouts', 'credit-adjustments': 'payouts', 'gift-cards': 'payouts', 'credit-liabilities': 'payouts', 'money-settings': 'payouts', 'membership-tiers': 'payouts', memberships: 'payouts', 'membership-settings': 'payouts', 'membership-report': 'payouts', referrals: 'payouts', 'referral-settings': 'payouts', 'stripe-lookup': 'payouts',
  'payment-settings': 'payouts', 'tax-settings': 'fees', maintenance: 'siteCfg', 'tax-filings': 'fees', 'tax-nexus': 'fees',
  'oauth-clients': 'identity', 'identity-sessions': 'identity', 'signing-keys': 'identity', 'auth-settings': 'identity',
  'exchange-rates': 'inventory'
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

/**
 * Grants identity's seeds do not carry yet (rbac-data.ts is generated from them): notifications:templates:write is
 * checked by notifications and the BFF (S268) but no platform role holds it until identity seeds it. The mock gives it
 * to the roles that edit customer messaging so the template editor can be tried.
 */
export const PENDING_PERMISSIONS: Record<string, string[]> = {
  'platform.super_admin': ['notifications:read', 'notifications:templates:write'],
  'platform.marketing': ['notifications:templates:write'],
  'platform.operations_manager': ['notifications:templates:write', 'preorders:manage', 'preorders:export', 'preorders:late-add'],
  'platform.customer_support': ['delivery-runs:read', 'preorders:late-add'],
  'platform.finance': ['delivery-runs:read', 'preorders:export'],
  'platform.auditor': ['delivery-runs:read', 'preorders:export'],
  'platform.product_owner': ['delivery-runs:read'],
  'platform.fulfilment_ops': ['delivery-runs:read'],
  'platform.retail_buyer': ['delivery-runs:read'],
  'platform.developer': ['delivery-runs:read', 'notifications:read', 'notifications:templates:write', 'preorders:manage', 'preorders:export', 'preorders:late-add', 'plans:manage', 'billing:adjust', 'seller:billing']
};

/* Pre-orders grants (identity migration V22, docs/spikes/preorder-design-implementation.md §6) until identity seeds them. */
const PREORDER_GRANTS = ['delivery-runs:read', 'preorders:manage', 'preorders:export', 'preorders:late-add'];
PENDING_PERMISSIONS['platform.super_admin']?.push(...PREORDER_GRANTS);
PENDING_PERMISSIONS['platform.marketing']?.push(...PREORDER_GRANTS);

/* Plans & billing grants (identity migration V25, S292–S297) until rbac-data.ts is regenerated from identity's seeds. */
PENDING_PERMISSIONS['platform.super_admin']?.push('plans:manage', 'billing:adjust', 'seller:billing');

/* Identity admin console (identity migration V31, S291): Super Admin only. */
PENDING_PERMISSIONS['platform.super_admin']?.push('identity:admin');

/* Ledger adjustments, credit corrections and gift card issue/void (identity migration V30): Super Admin and Finance. */
PENDING_PERMISSIONS['platform.super_admin']?.push('ledger:adjust', 'ledger:approve');
PENDING_PERMISSIONS['platform.finance']?.push('ledger:adjust', 'ledger:approve');
PENDING_PERMISSIONS['platform.finance']?.push('plans:manage', 'billing:adjust', 'seller:billing');
PENDING_PERMISSIONS['platform.product_owner']?.push('seller:billing');
PENDING_PERMISSIONS['platform.auditor']?.push('seller:billing');

/** The Pre-orders column of the Backoffice matrix (backoffice-config.js): F full · E edit · A act (approve) · V view. */
export const PREORDER_MATRIX: Record<string, string> = {
  'platform.super_admin': 'F',
  'platform.marketing': 'F',
  'platform.operations_manager': 'E',
  'platform.customer_support': 'A',
  'platform.finance': 'V',
  'platform.auditor': 'V',
  'platform.product_owner': 'V',
  'platform.fulfilment_ops': 'V',
  'platform.retail_buyer': 'V'
};

export function permissionsOf(roles: string[]): string[] {
  return [...new Set(roles.flatMap((r) => [...(ROLE_PERMISSIONS[r] ?? []), ...(PENDING_PERMISSIONS[r] ?? [])]))].sort();
}

function matrixLevel(roles: string[], module: MatrixModule | 'preorders'): AccessLevel | null {
  const idx = module === 'preorders' ? -1 : MATRIX_MODULES.indexOf(module);
  let best: AccessLevel | null = null;
  roles.forEach((r) => {
    const lv = CODE[(module === 'preorders' ? PREORDER_MATRIX[r] : ROLE_MATRIX[r]?.[idx]) ?? '-'] ?? null;
    if (lv && (!best || RANK[lv] > RANK[best])) best = lv;
  });
  return best;
}

/** The staff member's level on a console module: the best level over their roles (support, exports and notifications are derived). */
export function levelOf(roles: string[], module: StaffModuleKey): AccessLevel | null {
  if (module === 'exports') return matrixLevel(roles, 'dash');
  // Identity & access follows identity's own permission, not the module matrix.
  if (module === 'identity') return permissionsOf(roles).includes('identity:admin') ? 'EDIT' : null;
  if (module === 'support') {
    const perms = permissionsOf(roles);
    return perms.includes('support:respond') ? 'EDIT' : perms.includes('users:read') ? 'VIEW' : null;
  }
  if (module === 'notifications') {
    const perms = permissionsOf(roles);
    return perms.includes('notifications:templates:write') ? 'EDIT' : perms.includes('notifications:read') ? 'VIEW' : null;
  }
  if (module === 'plans') {
    // Derived from the seller service's permissions, as the BFF does (identity's matrix has no Plans & billing column).
    const perms = permissionsOf(roles);
    return perms.includes('plans:manage') || perms.includes('billing:adjust') ? 'EDIT' : perms.includes('seller:billing') || perms.includes('billing:usage') ? 'VIEW' : null;
  }
  if (module === 'orgs') {
    // Organisations follow the seller service's permissions (identity's matrix has no Organisations column).
    const perms = permissionsOf(roles);
    return perms.includes('sellers:enforce') || perms.includes('compliance:hold') ? 'EDIT' : perms.includes('compliance:remind') || perms.includes('applications:review') ? 'VIEW' : null;
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
