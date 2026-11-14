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
  {
    label: 'new listing',
    path: '/listings/new',
    // A chosen category brings every section (price, details, variations, shipping, compliance) onto the page.
    open: {
      label: 'category chosen',
      run: async (p) => {
        await p.getByRole('combobox', { name: 'Category' }).fill('spices');
        await p.getByRole('option', { name: /Spices & seasonings/ }).click();
        await p.getByRole('heading', { name: 'Compliance' }).waitFor();
      }
    }
  },
  {
    label: 'new sized listing',
    path: '/listings/new',
    // A sized category adds the Size guide section; its chart table, the own-size input and the grouped quality list must fit every width.
    open: {
      label: 'sized category chosen',
      run: async (p) => {
        await p.getByRole('combobox', { name: 'Category' }).fill('kaftans');
        await p.getByRole('option', { name: /Kaftans & shirts/ }).click();
        await p.getByRole('heading', { name: 'Size guide' }).waitFor();
        await p.getByLabel('Size chart').selectOption({ label: 'Kaftans and shirts' });
        await p.getByRole('table', { name: 'Size chart Kaftans and shirts' }).waitFor();
      }
    }
  },
  { label: 'size charts', path: '/listings/size-charts', open: { label: 'chart editor', run: (p) => p.getByRole('button', { name: 'New size chart' }).click() } },
  { label: 'import size charts', path: '/listings/size-charts/import' },
  { label: 'inventory', path: '/inventory' },
  { label: 'orders', path: '/orders', open: { label: 'order sheet', run: (p) => p.getByTestId('order-row').first().click() } },
  { label: 'settings', path: '/settings' },
  { label: 'settings locations', path: '/settings/locations' },
  { label: 'settings shipping', path: '/settings/shipping' },
  { label: 'settings pickup', path: '/settings/pickup' },
  { label: 'settings payouts', path: '/settings/payouts' },
  { label: 'settings tax', path: '/settings/tax' },
  { label: 'settings documents', path: '/settings/documents' },
  { label: 'payouts', path: '/payouts' },
  { label: 'billing', path: '/billing' },
  { label: 'fees', path: '/fees' },
  { label: 'account health', path: '/account-health' },
  { label: 'storefront', path: '/storefront' },
  { label: 'team', path: '/settings/team' },
  { label: 'business profile', path: '/settings/business' },
  { label: 'onboarding', path: '/onboarding' },
  { label: 'account', path: '/account' },
  { label: 'account security', path: '/account/security' },
  { label: 'settings notifications', path: '/settings/notifications' },
  { label: 'settings language', path: '/settings/language' }
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
