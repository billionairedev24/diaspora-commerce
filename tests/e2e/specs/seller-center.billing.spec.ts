import { expect, test } from '@playwright/test';

import { SELLER_ORG, setActiveOrg } from '../support/seller';

/* Seller Center › Plan & billing (S292–S297) against the mock BFF: the plan with its credit balance, rewards the
   seller can earn with progress bars, a plan change previewed before anything changes, a card added through the
   SetupIntent (the labelled local stand-in without a Stripe key) and invoices with their PDFs. */
test.describe('seller center · plan & billing', () => {
  test.beforeEach(async ({ request, baseURL }) => {
    await setActiveOrg(request, baseURL ?? '', SELLER_ORG);
  });

  test('plan, credit, rewards, preview of a plan change, a new card and invoices', async ({ page }) => {
    await page.goto('/billing');
    await expect(page.getByTestId('credit-balance')).toContainText('$');
    const reward = page.getByRole('progressbar', { name: 'First $1,000 in sales' });
    await expect(reward).toHaveAttribute('aria-valuetext', '$640 of $1,000 in sales');
    await expect(page.getByRole('table', { name: 'Rewards and credits received' })).toContainText('$25 credit');

    // A plan change shows what happens (and what is due now) before anything changes.
    await page.getByRole('button', { name: 'Choose Seller Essentials' }).click();
    const dialog = page.getByRole('dialog', { name: 'Change to Seller Essentials' });
    await expect(dialog).toContainText('takes effect at your next renewal');
    await expect(dialog.getByTestId('due-now')).toHaveText('$0.00');
    await dialog.getByRole('button', { name: 'Cancel' }).click();
    await expect(dialog).toBeHidden();

    // A card through the SetupIntent; no Stripe key here, so the local stand-in collects it.
    await page.getByRole('button', { name: 'Add payment method' }).click();
    const add = page.getByRole('dialog', { name: 'Add a payment method' });
    const local = add.getByRole('group', { name: 'Local test card' });
    await local.getByLabel('Card number').fill('4242 4242 4242 4242');
    await local.getByLabel('Expiry').fill('0431');
    await add.getByRole('checkbox').uncheck();
    await add.getByRole('button', { name: 'Save payment method' }).click();
    await expect(add).toBeHidden();
    await expect(page.getByText('•••• 4242 · expires 04/31')).toBeVisible();

    const invoices = page.getByRole('table', { name: 'Invoices' });
    await expect(invoices.getByRole('link', { name: 'Download PDF' }).first()).toHaveAttribute('href', /\.pdf$/);
  });
});
