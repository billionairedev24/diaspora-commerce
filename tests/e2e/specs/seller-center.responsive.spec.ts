import { type Page, test } from '@playwright/test';

import { type Allowance, type AuditRoute,auditRoute } from '../support/responsive';
import { PROVIDER_ORG, SELLER_ORG, setActiveOrg } from '../support/seller';

/* Responsive audit of Seller Center and Provider Hub at 320–1440 px (see support/responsive.ts for the checks). */

const openMenu = async (page: Page) => {
  const menu = page.getByRole('button', { name: /^Menu/ });
  if (await menu.isVisible()) await menu.click();
};

const SELLER_ROUTES: AuditRoute[] = [
  { label: 'dashboard', path: '/', open: { label: 'menu', run: openMenu } },
  { label: 'listings', path: '/listings' },
  { label: 'new listing', path: '/listings/new' },
  { label: 'inventory', path: '/inventory' },
  { label: 'orders', path: '/orders', open: { label: 'order sheet', run: (p) => p.getByTestId('order-row').first().click() } },
  { label: 'ship from', path: '/orders/ship-from' },
  { label: 'payouts', path: '/payouts' },
  { label: 'billing', path: '/billing' },
  { label: 'fees', path: '/fees' },
  { label: 'account health', path: '/account-health' },
  { label: 'storefront', path: '/storefront' },
  { label: 'team', path: '/team' },
  { label: 'onboarding', path: '/onboarding' },
  { label: 'account', path: '/account' },
  { label: 'account security', path: '/account/security' },
  { label: 'account notifications', path: '/account/notifications' },
  { label: 'account preferences', path: '/account/preferences' }
];

const PROVIDER_ROUTES: AuditRoute[] = [
  { label: 'provider dashboard', path: '/' },
  { label: 'calendar', path: '/calendar' },
  { label: 'requests', path: '/requests' },
  { label: 'services', path: '/services' },
  { label: 'hours', path: '/hours' },
  { label: 'policy', path: '/policy' },
  { label: 'public page', path: '/public-page' },
  { label: 'earnings', path: '/earnings' }
];

const ALLOW: Allowance[] = [];

test.describe('seller center · responsive', () => {
  test.beforeAll(async ({ request, baseURL }) => {
    await setActiveOrg(request, baseURL ?? '', SELLER_ORG);
  });
  for (const route of SELLER_ROUTES) {
    test(`${route.label} (${route.path}) @responsive`, async ({ browser, baseURL }, testInfo) => {
      test.setTimeout(240_000);
      await auditRoute(browser, route, { app: 'seller-center', baseURL: baseURL ?? '', allow: ALLOW }, testInfo);
    });
  }
});

test.describe('provider hub · responsive', () => {
  test.beforeAll(async ({ request, baseURL }) => {
    await setActiveOrg(request, baseURL ?? '', PROVIDER_ORG);
  });
  test.afterAll(async ({ request, baseURL }) => {
    await setActiveOrg(request, baseURL ?? '', SELLER_ORG);
  });
  for (const route of PROVIDER_ROUTES) {
    test(`${route.label} (${route.path}) @responsive`, async ({ browser, baseURL }, testInfo) => {
      test.setTimeout(240_000);
      await auditRoute(browser, route, { app: 'seller-center', baseURL: baseURL ?? '', allow: ALLOW }, testInfo);
    });
  }
});
