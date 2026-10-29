import { expect, type Page, test } from '@playwright/test';

import { type Allowance, type AuditRoute,auditRoute } from '../support/responsive';

/* Responsive audit of every storefront page at 320–1440 px (see support/responsive.ts for the checks). Fails on page
   overflow, elements past the viewport, clipped text, fixed layers over controls and a sticky header taller than a
   quarter of a phone screen. RESPONSIVE_OUT=<dir> also writes the per-page JSON and screenshots for the report. */

const CONSENT = encodeURIComponent(
  JSON.stringify({ necessary: true, functional: true, analytics: false, advertising: false, dns: false, gpc: false, v: '2.1', at: '2026-10-01T00:00:00.000Z' })
);
const PDP = '/p/AT000000P2';

const fillCart = async (page: Page) => {
  await page.goto(PDP);
  const buy = page.getByRole('complementary', { name: 'Buy box' });
  await buy.getByRole('button', { name: 'Add to Cart' }).click();
  await expect(page.getByTestId('cart-count').first()).not.toHaveText('0');
};

const clickIfVisible = async (page: Page, name: RegExp) => {
  const b = page.getByRole('button', { name }).first();
  if (await b.isVisible()) await b.click();
};

const ROUTES: AuditRoute[] = [
  { label: 'home', path: '/', open: { label: 'Lola', run: (p) => p.getByRole('button', { name: /Ask Lola|Lola/ }).first().click() } },
  { label: 'search', path: '/s?q=rice', open: { label: 'filters', run: (p) => clickIfVisible(p, /^Filters/) } },
  { label: 'department', path: '/s?dept=Groceries' },
  { label: 'pdp', path: PDP, cta: (p) => p.getByRole('complementary', { name: 'Buy box' }).getByRole('button', { name: 'Add to Cart' }) },
  { label: 'cart', path: '/cart', setup: fillCart, cta: (p) => p.getByRole('link', { name: /Proceed to checkout|Check out/i }) },
  { label: 'checkout', path: '/checkout', setup: fillCart, ready: (p) => expect(p.getByTestId('checkout-address').first()).toBeVisible() },
  { label: 'pre-orders', path: '/pre-orders' },
  { label: 'services', path: '/services' },
  { label: 'service detail', path: '/services/svc-braids-cornrows' },
  { label: 'provider page', path: '/services/providers/org_braids_ama' },
  { label: 'account', path: '/account', open: { label: 'account menu', run: (p) => p.getByRole('button', { name: /account menu|Account/i }).first().click() } },
  { label: 'account profile', path: '/account/profile' },
  { label: 'account addresses', path: '/account/addresses' },
  { label: 'account payments', path: '/account/payments' },
  { label: 'account security', path: '/account/security' },
  { label: 'account notifications', path: '/account/notifications' },
  { label: 'account bookings', path: '/account/bookings' },
  { label: 'account returns', path: '/account/returns' },
  { label: 'start a return', path: '/account/returns/new' },
  { label: 'orders', path: '/orders' },
  { label: 'order detail', path: '/orders/AT-2026-104221' },
  { label: 'help', path: '/help' },
  { label: 'help article', path: '/help/shipping' },
  { label: 'legal center', path: '/legal' },
  { label: 'legal gift card terms', path: '/legal/gift-card-terms' },
  { label: 'legal privacy', path: '/legal/privacy' },
  { label: 'legal cookies', path: '/legal/cookies' },
  { label: 'privacy choices', path: '/legal/privacy-choices' },
  { label: 'about', path: '/about' },
  { label: 'careers', path: '/careers' },
  { label: 'communities', path: '/communities/nigerian', ready: (p) => expect(p.getByTestId('results-grid')).toBeVisible() },
  { label: 'sign in', path: '/signin' },
  { label: 'sign up', path: '/signup' }
];

/* Findings that stand, each with its reason. */
const ALLOW: Allowance[] = [];

test.describe('storefront · responsive', () => {
  for (const route of ROUTES) {
    test(`${route.label} (${route.path}) @responsive`, async ({ browser, baseURL }, testInfo) => {
      test.setTimeout(240_000);
      await auditRoute(
        browser,
        route,
        { app: 'storefront', baseURL: baseURL ?? '', cookies: [{ name: 'at_consent', value: CONSENT, url: baseURL ?? '' }], allow: ALLOW },
        testInfo
      );
    });
  }

  // First visit: the cookie banner must leave the page's primary action reachable on a phone.
  test('cookie banner leaves the buy box reachable on phones @responsive', async ({ browser, baseURL }, testInfo) => {
    test.setTimeout(120_000);
    await auditRoute(
      browser,
      { label: 'pdp · cookie banner', path: PDP, cta: (p) => p.getByRole('complementary', { name: 'Buy box' }).getByRole('button', { name: 'Add to Cart' }) },
      { app: 'storefront', baseURL: baseURL ?? '', allow: ALLOW, widths: [320, 360, 390, 414, 768] },
      testInfo
    );
  });
});
