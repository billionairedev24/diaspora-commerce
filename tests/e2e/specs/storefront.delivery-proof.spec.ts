import { type APIRequestContext, expect, type Page, test } from '@playwright/test';

/* Storefront order page: the delivery proof is shown only for an order AfroTransact delivered, and a seller-shipped order says
   who shipped it, shows the tracking number and never a carrier name or an AfroTransact delivery claim (mock BFF seed history:
   AT-2026-104221 delivered by AfroTransact with a photo, AT-2026-100873 shipped by the seller). The staff-to-customer link
   (proof recorded in the Delivery desk, then shown here) lives in two separate mock processes, so it is covered by the
   gateway-bff and orders service tests, not here. Screenshots are written when E2E_SHOTS=1. */
const SHOTS = process.env.E2E_SHOTS === '1';
const IMG = '../../docs/engineering/img';

/** The cookie banner would cover the page in screenshots. */
async function dismissCookies(page: Page) {
  const reject = page.getByRole('button', { name: 'Reject non-essential' });
  if (await reject.isVisible().catch(() => false)) await reject.click();
}

async function signIn(request: APIRequestContext) {
  const r = await request.get('/api/bff/v1/auth/login?returnTo=%2F');
  expect(r.ok()).toBe(true);
}

test.describe('storefront · delivery proof on the order page', () => {
  test('an AfroTransact-delivered order shows who delivered it and the photo proof', async ({ page, request }) => {
    await signIn(request);
    await page.goto('/orders/AT-2026-104221');
    await expect(page.getByText('Delivered by AfroTransact').first()).toBeVisible();
    const link = page.getByTestId('order-view-proof').first();
    await expect(link).toBeVisible();
    await link.click();
    const proof = page.getByTestId('delivery-proof');
    await expect(proof).toContainText('Received by Adaeze Nwosu');
    await expect(proof.getByRole('img')).toHaveCount(1);
    await dismissCookies(page);
    if (SHOTS) {
      await page.setViewportSize({ width: 1280, height: 800 });
      await page.screenshot({ path: `${IMG}/order-delivery-proof-1280.png` });
      await page.setViewportSize({ width: 390, height: 844 });
      await page.screenshot({ path: `${IMG}/order-delivery-proof-390.png` });
    }
  });

  test('a seller-shipped order shows the tracking number, no proof and no carrier', async ({ page, request }) => {
    await signIn(request);
    await page.goto('/orders/AT-2026-100873');
    await expect(page.getByText('Shipped by the seller').first()).toBeVisible();
    await expect(page.getByTestId('order-tracking').first()).toContainText('9400 1118 9922 3344 5566 77');
    await expect(page.getByTestId('order-view-proof')).toHaveCount(0);
    await expect(page.getByTestId('delivery-proof')).toHaveCount(0);
    await expect(page.locator('main')).not.toContainText(/Delivered by AfroTransact|UPS|FedEx|USPS|DHL/);
    await dismissCookies(page);
    if (SHOTS) {
      await page.setViewportSize({ width: 1280, height: 800 });
      await page.screenshot({ path: `${IMG}/order-seller-shipped-1280.png` });
      await page.setViewportSize({ width: 390, height: 844 });
      await page.screenshot({ path: `${IMG}/order-seller-shipped-390.png` });
    }
  });
});
