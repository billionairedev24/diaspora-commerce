import { type ModuleKey } from '@afrotransact/api-client';

/** Route of each sidebar module. */
export function hrefOf(key: ModuleKey): string {
  return key === 'home' ? '/' : `/${key}`;
}

/** Module that owns a pathname (sub-pages belong to their module, e.g. /listings/new → listings). */
export function moduleOf(pathname: string): ModuleKey | null {
  const seg = pathname.split('/').filter(Boolean)[0];
  if (!seg) return 'home';
  const known: ModuleKey[] = ['onboarding', 'orders', 'listings', 'inventory', 'storefront', 'calendar', 'requests', 'hours', 'services', 'policy', 'public-page',
    'earnings', 'payouts', 'fees', 'billing', 'account-health', 'team'];
  return known.find((k) => k === seg) ?? null;
}
