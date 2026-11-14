import { expect, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';
import { SELLER_ORG, setActiveOrg } from '../support/seller';

/* The one-time "let customers collect orders here" card. Mock org state is process-wide, so the journey starts by switching
   the seeded Warehouse pickup off through the same endpoint Seller Center uses, then answers the card like a seller would. */
test.describe.configure({ mode: 'default' });

test.describe('seller center · pickup prompt', () => {
  test.beforeEach(async ({ request, baseURL }) => {
    await setActiveOrg(request, baseURL ?? '', SELLER_ORG);
    const off = await request.put(`${baseURL}/api/bff/v1/seller/ship-from-addresses/sfa-store/customer-pickup`, { data: { enabled: false, hours: [], readyHours: 0 } });
    expect(off.status(), await off.text()).toBe(200);
  });

  test('home points to Settings > Pickup, which is pre-filled, stays off until confirmed, and turns pickup on only then', async ({ page, request, baseURL }, testInfo) => {
    await page.goto('/');
    const card = page.getByTestId('pickup-prompt');
    await expect(card).toBeVisible();
    await expect(card).toContainText('Only switch this on if someone will be there');
    await expect(card.getByRole('button', { name: 'Turn on pickup' })).toHaveCount(0);
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'seller home pickup card' });
    await card.getByRole('link', { name: 'Set up pickup' }).click();
    await expect(page).toHaveURL(/\/settings\/pickup$/);

    const form = page.getByTestId('pickup-location').first();
    await expect(form.getByTestId('pickup-address')).toContainText('9800 Bissonnet St');
    await form.getByLabel(/Customers can collect orders from this location/).check();
    await expect(form.getByTestId('pickup-preview')).toContainText('Shoppers near ZIP 77036 will see Pickup');
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'seller settings pickup' });
    const turnOn = form.getByRole('button', { name: 'Turn on pickup' });
    await expect(turnOn).toBeDisabled();
    await form.getByRole('button', { name: 'Add a pickup window' }).click();
    await form.getByLabel('Opens, window 1').fill('10:00');
    await form.getByLabel('Closes, window 1').fill('16:00');
    await form.getByLabel('Ready after (hours)').fill('3');
    await expect(turnOn).toBeDisabled();
    await form.getByLabel('Someone will be at this address during these hours.').check();
    await expect(turnOn).toBeEnabled();
    // Still off on the server until the button is pressed.
    const before = await (await request.get(`${baseURL}/api/bff/v1/seller/ship-from-addresses`)).json();
    expect(before.items[0].customerPickup.enabled).toBe(false);
    await turnOn.click();
    await expect(page.getByText('Customer pickup saved').first()).toBeVisible();
    const after = await (await request.get(`${baseURL}/api/bff/v1/seller/ship-from-addresses`)).json();
    expect(after.items[0].customerPickup).toMatchObject({ enabled: true, readyHours: 3 });
    await page.goto('/');
    await expect(page.getByTestId('pickup-prompt')).toHaveCount(0);
  });

  test('"Not now" and "Don’t ask again" are remembered by the server, not the browser', async ({ page, context, baseURL, request }) => {
    await page.goto('/');
    await page.getByTestId('pickup-prompt').getByRole('button', { name: 'Not now' }).click();
    await expect(page.getByTestId('pickup-prompt')).toHaveCount(0);
    // A fresh browser profile still does not see it: the answer lives on the server.
    const other = await context.browser()?.newContext({ baseURL });
    const fresh = await other?.newPage();
    await fresh?.goto('/');
    await expect(fresh?.getByTestId('pickup-prompt') ?? page.getByTestId('pickup-prompt')).toHaveCount(0);
    await other?.close();
    const res = await request.put(`${baseURL}/api/bff/v1/seller/pickup-prompt`, { data: { answer: 'DONT_ASK_AGAIN' } });
    expect(await res.json()).toMatchObject({ state: 'NEVER', show: false, nudge: true });
    await page.goto('/orders');
    await expect(page.getByText(/Customers can’t collect orders from you yet/)).toBeVisible();
    await expect(page.getByTestId('pickup-prompt')).toHaveCount(0);
  });
});
