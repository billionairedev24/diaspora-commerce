import { type Page, test } from '@playwright/test';

import { type Allowance, type AuditRoute,auditRoute } from '../support/responsive';

/* Responsive audit of the staff console at 320–1440 px: every module, every tab and My account
   (see support/responsive.ts for the checks). */

const openMenu = async (page: Page) => {
  const menu = page.getByRole('button', { name: /^Menu/ });
  if (await menu.isVisible()) await menu.click();
};
const openFirstRow = async (page: Page) => {
  await page.locator('tbody tr[data-row-id], [data-row-id]').first().click();
};

const openFirstRun = async (page: Page) => {
  await page.getByRole('button', { name: /^Open the .* run on / }).first().click();
};
const openNewRun = async (page: Page) => {
  await page.getByRole('button', { name: 'New run' }).click();
};
const openNewLocation = async (page: Page) => {
  await page.getByRole('button', { name: 'Add location' }).click();
};
const openEditLocation = async (page: Page) => {
  await page.getByRole('button', { name: 'Edit location' }).click();
};

const tabs = (base: string, label: string, values: string[]): AuditRoute[] => values.map((v, i) => ({ label: `${label} · ${v}`, path: i === 0 ? base : `${base}?tab=${v}` }));

const ROUTES: AuditRoute[] = [
  { label: 'dashboard', path: '/', open: { label: 'menu', run: openMenu } },
  { label: 'exports', path: '/exports' },
  ...tabs('/orders', 'orders', ['orders', 'flow']),
  ...tabs('/moderation', 'moderation', ['moderation', 'products']),
  ...tabs('/inventory', 'inventory', ['flow', 'overview', 'inbound', 'receiving', 'stock', 'counts', 'expiry', 'movements']),
  { label: 'inventory · lot sheet', path: '/inventory?tab=stock', open: { label: 'sheet', run: openFirstRow } },
  ...tabs('/retail', 'retail', ['pos', 'items', 'receipts', 'invoices', 'suppliers']),
  ...tabs('/dispatch', 'dispatch', ['routes', 'shipments', 'pickups', 'sos', 'defects', 'events']),
  { label: 'applications', path: '/applications', open: { label: 'sheet', run: openFirstRow } },
  ...tabs('/support', 'support', ['cases', 'customer']),
  ...tabs('/users', 'users', ['users', 'roles']),
  { label: 'payouts', path: '/payouts' },
  ...tabs('/fees', 'fees', ['fee-schedules', 'commission', 'connection-fees', 'negotiated', 'calculator', 'tax-rates']),
  ...tabs('/marketing', 'marketing', ['announcements', 'placements']),
  ...tabs('/disputes', 'disputes', ['claims', 'returns']),
  { label: 'audit', path: '/audit' },
  { label: 'markets', path: '/markets' },
  ...tabs('/site-content', 'site content', ['site-settings', 'legal-documents', 'help-articles', 'privacy-requests']),
  { label: 'developers', path: '/developers' },
  { label: 'notifications', path: '/notifications' },
  { label: 'pre-orders', path: '/preorders' },
  { label: 'pre-order settings', path: '/preorders/settings' },
  { label: 'new pre-order', path: '/preorders/new' },
  ...tabs('/preorders/ama-4', 'pre-order', ['overview', 'orders', 'creatives', 'analytics']),
  { label: 'edit pre-order', path: '/preorders/ama-4/edit' },
  { label: 'delivery runs', path: '/preorders/runs' },
  { label: 'delivery run sheet', path: '/preorders/runs', open: { label: 'sheet', run: openFirstRun } },
  { label: 'new delivery run sheet', path: '/preorders/runs', open: { label: 'sheet', run: openNewRun } },
  { label: 'new delivery location sheet', path: '/preorders/runs', open: { label: 'sheet', run: openNewLocation } },
  { label: 'edit delivery location sheet', path: '/preorders/runs', open: { label: 'sheet', run: openEditLocation } },
  { label: 'account', path: '/account' },
  { label: 'account security', path: '/account/security' },
  { label: 'account notifications', path: '/account/notifications' },
  { label: 'account preferences', path: '/account/preferences' }
];

const ALLOW: Allowance[] = [];

test.describe('staff console · responsive', () => {
  for (const route of ROUTES) {
    test(`${route.label} (${route.path}) @responsive`, async ({ browser, baseURL }, testInfo) => {
      test.setTimeout(240_000);
      await auditRoute(browser, route, { app: 'staff-console', baseURL: baseURL ?? '', allow: ALLOW }, testInfo);
    });
  }
});
