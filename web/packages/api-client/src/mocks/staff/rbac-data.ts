/* Platform RBAC of the mock staff BFF — GENERATED from identity's seed migrations (V2__seed_rbac.sql and later
   role_permission inserts). Roles hold permissions; the module matrix gives each role a level per identity module
   (F full · A approve · E edit · V view · - none, one character per module in MATRIX_MODULES order). */

export const MATRIX_MODULES = ['dash', 'orders', 'catalog', 'inventory', 'retail', 'dispatch', 'apps', 'rbac', 'payouts', 'fees', 'marketing', 'disputes', 'audit', 'markets', 'siteCfg', 'dev'] as const;
export type MatrixModule = (typeof MATRIX_MODULES)[number];

export const ROLE_LABELS: Record<string, string> = {
  'platform.super_admin': 'Super Admin',
  'platform.operations_manager': 'Operations Manager',
  'platform.trust_safety': 'Trust & Safety',
  'platform.finance': 'Finance',
  'platform.customer_support': 'Customer Support',
  'platform.marketing': 'Marketing',
  'platform.catalog_manager': 'Catalog Manager',
  'platform.retail_buyer': 'Retail Buyer (1P)',
  'platform.fulfilment_ops': 'Fulfilment Ops',
  'platform.inventory_manager': 'Inventory Manager',
  'platform.dispatcher': 'Dispatcher',
  'platform.product_owner': 'Product Owner',
  'platform.developer': 'Developer',
  'platform.auditor': 'Auditor (read-only)',
  'platform.driver': 'Driver'
};

export const ROLE_MATRIX: Record<string, string> = {
  'platform.super_admin': 'FFFFFFFFFFFFFFFF',
  'platform.operations_manager': 'VVEVVEA-VVVAVVV-',
  'platform.trust_safety': 'VVA---A----AV-V-',
  'platform.finance': 'VV-V--V-AF-EV---',
  'platform.customer_support': 'VEV--VV-V--E--V-',
  'platform.marketing': 'V-V-------F---E-',
  'platform.catalog_manager': 'VVF-V-----------',
  'platform.retail_buyer': 'VVEEF-----------',
  'platform.fulfilment_ops': 'VV-FVF-----V-V--',
  'platform.inventory_manager': 'VV-FVV----------',
  'platform.dispatcher': 'VE-V-F----------',
  'platform.product_owner': 'VVVVVVVVVEVVVF-V',
  'platform.developer': 'VVVVVVVVVVEVVVVF',
  'platform.auditor': 'VVVVVVVVVVVVVV-V',
  'platform.driver': '-----V----------'
};

export const ROLE_PERMISSIONS: Record<string, string[]> = {
  'platform.super_admin': ['access:admin', 'access:read', 'ads:manage', 'advert:delete', 'ai:manage', 'ai:triage', 'ai:use', 'applications:approve', 'applications:pii', 'applications:review', 'applications:submit', 'audit:read', 'availability:manage', 'billing:manage', 'bookings:availability:write', 'bookings:manage', 'bookings:read', 'bookings:services:write', 'bookings:team:manage', 'catalog:moderate', 'catalog:retail', 'catalog:taxonomy', 'catalog:write', 'compliance:hold', 'compliance:manage', 'compliance:remind', 'config:edit', 'content:audit.view', 'content:careers.edit', 'content:careers.view', 'content:flags.edit', 'content:flags.view', 'content:legal.edit', 'content:legal.view', 'content:manage', 'content:markets.edit', 'content:markets.view', 'content:placements.approve', 'content:placements.edit', 'content:placements.view', 'content:site.edit', 'content:site.view', 'coupons:manage', 'delivery:dispatch', 'delivery:operate', 'developer:manage', 'disputes:manage', 'fees:manage', 'flags:manage', 'fulfilment:operate', 'geo:zones:write', 'inventory:adjust', 'inventory:read', 'inventory:receive', 'inventory:scan', 'labels:print', 'ledger:read', 'marketing:manage', 'markets:manage', 'mcp:staff', 'notifications:manage', 'notifications:read', 'offers:read', 'offers:write', 'orders:manage', 'orders:read', 'orders:update-status', 'orgs:manage', 'orgs:read', 'payments:configure', 'payments:read', 'payouts:approve', 'payouts:hold', 'payouts:read', 'pickup:manage', 'refunds:issue', 'reports:read', 'retail:finance', 'retail:items:write', 'retail:logistics', 'retail:po:approve', 'retail:po:write', 'retail:purchase', 'retail:read', 'retail:receive', 'retail:suppliers:write', 'returns:manage', 'reviews:respond', 'sellers:enforce', 'services:write', 'shipping:label', 'shipping:manage', 'shipping:rate', 'shipping:void', 'store:design', 'storefront:moderate', 'support:respond', 'tax:configure', 'tax:quote', 'tax:read', 'team:manage', 'users:read'],
  'platform.operations_manager': ['ai:triage', 'ai:use', 'applications:approve', 'applications:review', 'audit:read', 'catalog:moderate', 'compliance:hold', 'compliance:remind', 'content:flags.view', 'content:legal.view', 'content:markets.edit', 'content:markets.view', 'content:placements.approve', 'content:placements.view', 'content:site.view', 'delivery:dispatch', 'disputes:manage', 'fulfilment:operate', 'geo:zones:write', 'inventory:read', 'markets:manage', 'mcp:staff', 'notifications:manage', 'notifications:read', 'offers:read', 'offers:write', 'orders:manage', 'orders:read', 'orgs:read', 'payments:read', 'payouts:read', 'pickup:manage', 'reports:read', 'retail:po:approve', 'retail:read', 'returns:manage', 'sellers:enforce', 'shipping:label', 'shipping:manage', 'shipping:rate', 'shipping:void', 'users:read'],
  'platform.trust_safety': ['ai:triage', 'ai:use', 'applications:approve', 'applications:pii', 'applications:review', 'audit:read', 'catalog:moderate', 'compliance:hold', 'compliance:manage', 'content:legal.view', 'content:placements.view', 'disputes:manage', 'mcp:staff', 'offers:read', 'orders:read', 'orgs:read', 'payouts:hold', 'reports:read', 'sellers:enforce', 'storefront:moderate', 'users:read'],
  'platform.finance': ['ai:use', 'audit:read', 'compliance:hold', 'compliance:manage', 'compliance:remind', 'coupons:manage', 'fees:manage', 'inventory:read', 'ledger:read', 'mcp:staff', 'orders:read', 'orgs:read', 'payments:configure', 'payments:read', 'payouts:approve', 'payouts:hold', 'payouts:read', 'payouts:request', 'refunds:issue', 'reports:read', 'retail:finance', 'retail:po:approve', 'retail:read', 'tax:configure', 'tax:read'],
  'platform.customer_support': ['ai:triage', 'ai:use', 'bookings:manage', 'bookings:read', 'catalog:moderate', 'content:legal.view', 'content:site.view', 'delivery:dispatch', 'delivery:dispatch:read', 'disputes:manage', 'inventory:read', 'mcp:staff', 'notifications:read', 'offers:read', 'orders:read', 'orders:update-status', 'orgs:read', 'payments:read', 'pickup:manage', 'refunds:issue', 'reports:read', 'returns:manage', 'shipping:rate', 'support:respond', 'users:read'],
  'platform.marketing': ['ads:manage', 'advert:delete', 'ai:use', 'config:edit', 'content:careers.edit', 'content:careers.view', 'content:legal.view', 'content:manage', 'content:placements.edit', 'content:placements.view', 'content:site.edit', 'content:site.view', 'coupons:manage', 'marketing:manage', 'mcp:staff', 'notifications:manage', 'notifications:read', 'offers:read', 'orgs:read', 'reports:read', 'storefront:moderate'],
  'platform.catalog_manager': ['ai:use', 'catalog:moderate', 'catalog:taxonomy', 'catalog:write', 'config:edit', 'labels:print', 'mcp:staff', 'offers:read', 'offers:write', 'orders:read', 'orgs:read', 'reports:read', 'retail:items:write', 'retail:purchase', 'retail:read'],
  'platform.retail_buyer': ['ai:use', 'catalog:retail', 'catalog:write', 'inventory:read', 'inventory:receive', 'inventory:scan', 'labels:print', 'mcp:staff', 'offers:read', 'offers:write', 'orders:read', 'pickup:manage', 'reports:read', 'retail:items:write', 'retail:logistics', 'retail:po:write', 'retail:purchase', 'retail:read', 'retail:suppliers:write'],
  'platform.fulfilment_ops': ['ai:use', 'delivery:dispatch', 'fulfilment:operate', 'inventory:adjust', 'inventory:read', 'inventory:receive', 'inventory:scan', 'labels:print', 'mcp:staff', 'orders:manage', 'orders:read', 'pickup:manage', 'reports:read', 'retail:purchase', 'retail:read', 'retail:receive', 'shipping:label', 'shipping:rate', 'shipping:void'],
  'platform.inventory_manager': ['ai:use', 'inventory:adjust', 'inventory:read', 'inventory:receive', 'inventory:scan', 'labels:print', 'mcp:staff', 'orders:read', 'reports:read', 'retail:purchase', 'retail:read', 'retail:receive'],
  'platform.dispatcher': ['ai:use', 'delivery:dispatch', 'geo:zones:write', 'inventory:read', 'mcp:staff', 'orders:manage', 'orders:read', 'pickup:manage', 'reports:read', 'shipping:rate'],
  'platform.product_owner': ['access:read', 'ai:manage', 'ai:use', 'audit:read', 'catalog:moderate', 'config:edit', 'content:flags.edit', 'content:flags.view', 'content:manage', 'content:markets.edit', 'content:markets.view', 'content:placements.view', 'content:site.view', 'coupons:manage', 'fees:manage', 'flags:manage', 'inventory:read', 'markets:manage', 'mcp:staff', 'orders:read', 'orgs:read', 'payouts:read', 'reports:read'],
  'platform.developer': ['access:admin', 'access:read', 'ai:manage', 'ai:use', 'audit:read', 'config:edit', 'content:flags.edit', 'content:flags.view', 'coupons:manage', 'developer:manage', 'fees:manage', 'flags:manage', 'inventory:read', 'labels:print', 'markets:manage', 'mcp:staff', 'orders:read', 'orgs:read', 'pickup:manage', 'reports:read', 'shipping:manage', 'users:read'],
  'platform.auditor': ['access:read', 'ai:use', 'audit:read', 'content:audit.view', 'content:legal.view', 'inventory:read', 'ledger:read', 'mcp:staff', 'orders:read', 'orgs:read', 'payments:read', 'payouts:read', 'reports:read', 'retail:read', 'tax:read', 'users:read'],
  'platform.driver': ['delivery:operate']
};
