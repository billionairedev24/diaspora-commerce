import { expect, test } from '@playwright/test';

import { PROVIDER_ORG, SELLER_ORG, setActiveOrg } from '../support/seller';

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

  /* S298: how the plan is paid. "Pay your plan from your sales" takes each invoice from pending earnings (the card pays
     the rest); "Charge my card" keeps plan fees a separate card expense. Invoices show the split; Payouts lists the
     "Plan fee (invoice …)" deductions so the seller's books reconcile. The journey restores the choice it changed. */
  test('pays the plan from sales or by card, with the split on invoices and the deduction on payouts', async ({ page }) => {
    await page.goto('/billing');
    const choice = page.getByTestId('plan-fee-collection');
    const fromSales = choice.getByRole('radio', { name: /Pay your plan from your sales/ });
    const byCard = choice.getByRole('radio', { name: /Charge my card/ });
    await expect(choice).toContainText('Changes apply from your next invoice, on');
    if ((await byCard.getAttribute('aria-checked')) === 'true') {
      await fromSales.click();
      await expect(fromSales).toHaveAttribute('aria-checked', 'true');
    }
    await byCard.click();
    await expect(byCard).toHaveAttribute('aria-checked', 'true');
    await expect(page.getByText('Saved. It applies from your next invoice.')).toBeVisible();
    await fromSales.click();
    await expect(fromSales).toHaveAttribute('aria-checked', 'true');

    await expect(page.getByRole('table', { name: 'Invoices' })).toContainText('Paid from your sales: $90.00 · Card: $21.15');

    await page.goto('/payouts');
    const fees = page.getByRole('list', { name: 'Plan fees paid from your sales' });
    await expect(fees).toContainText('Plan fee (invoice AT-1003)');
    await expect(fees).toContainText('Deducted from order AT-2026-400112');
  });

  /* S299: low-sales relief. A provider in its trial sees a neutral note (no progress bar) saying when it is checked;
     a seller whose relief was applied sees when the card is charged next. */
  test('low-sales relief: pending until checked, then the next charge date', async ({ page, request, baseURL }) => {
    await page.goto('/billing');
    await expect(page.getByText('Low-sales relief applied:')).toBeVisible();
    await expect(page.getByRole('status').filter({ hasText: 'Low-sales relief applied' })).toContainText(/your next charge is on \w{3} \d{1,2}, \d{4}/);

    await setActiveOrg(request, baseURL ?? '', PROVIDER_ORG);
    await page.goto('/billing');
    const relief = page.getByTestId('low-sales-relief');
    await expect(relief).toContainText(/If your sales in your first 30 days are under \$1,000, you get 1 month free — we’ll check on \w{3} \d{1,2}, \d{4}\./);
    await expect(relief.getByRole('progressbar')).toHaveCount(0);
    await expect(page.getByRole('progressbar', { name: '10 completed bookings' })).toBeVisible();
  });
});
