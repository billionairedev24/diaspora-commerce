import { expect, test } from '@playwright/test';

/* Staff console › Finance › Plans & billing (S292–S298) against the mock BFF: the plan catalogue with the Stripe sync
   status, a promo-code trial campaign, a reward rule with its "who qualifies now" preview, a per-seller credit with a
   reason code that lands in the grants log, a per-seller override of how the plan is paid and the platform default
   for new sellers (Settings). Names are unique per run so the journey can run again. */
test.describe('staff console · plans & billing', () => {
  test('catalogue, campaign, reward preview, a seller credit and the grants log', async ({ page }) => {
    const run = Date.now().toString(36).toUpperCase();
    await page.goto('/plans');
    await expect(page.getByRole('heading', { name: 'Plans & billing' })).toBeVisible();
    const plans = page.getByRole('table', { name: 'Plans' });
    await expect(plans.getByRole('row', { name: /Seller Growth/ })).toBeVisible();
    await expect(page.getByRole('table', { name: 'Stripe sync status' })).toContainText('Free');

    await page.getByRole('tab', { name: 'Trials & promotions' }).click();
    await page.getByRole('button', { name: 'New campaign' }).click();
    const campaign = page.getByRole('dialog', { name: 'New campaign' });
    await campaign.getByLabel('Name').fill(`E2E ${run}`);
    await campaign.getByLabel('Promo code').fill(`E2E${run}`);
    await campaign.getByLabel('Trial days').fill('45');
    await campaign.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('table', { name: 'Trials & promotions' })).toContainText(`E2E${run}`);

    await page.getByRole('tab', { name: 'Rewards' }).click();
    await page.getByRole('button', { name: 'New reward rule' }).click();
    const rule = page.getByRole('dialog', { name: 'New reward rule' });
    await rule.getByLabel('Name').fill(`Ten orders ${run}`);
    await rule.getByLabel('Metric').selectOption('COMPLETED_ORDERS');
    await rule.getByLabel('Threshold').fill('10');
    await rule.getByLabel('Reward', { exact: true }).selectOption('CREDIT');
    await rule.getByLabel('Credit ($)').fill('25');
    await rule.getByRole('button', { name: 'Check now' }).click();
    await expect(rule).toContainText(/\d+ of \d+ organisations qualify now/);
    await rule.getByRole('button', { name: 'Close' }).click();

    await page.getByRole('tab', { name: 'Seller billing' }).click();
    await page.getByRole('searchbox').fill('ade');
    await page.getByRole('button', { name: /Ade’s Kitchen/ }).click();
    await page.getByRole('button', { name: 'Add credit' }).click();
    const credit = page.getByRole('dialog', { name: 'Add credit' });
    await credit.getByLabel('Credit ($)').fill('10');
    await credit.getByLabel('Reason').selectOption('GOODWILL');
    await credit.getByRole('button', { name: 'Apply' }).click();
    await expect(credit).toBeHidden();
    await expect(page.getByRole('table', { name: 'Rewards and adjustments' })).toContainText('$10 credit');

    // S298: how the seller pays its plan, overridden with a reason (applies from its next invoice).
    const fees = page.getByTestId('panel-plan-fees');
    const target = (await fees.textContent())?.includes('Card only') ? 'EARNINGS_FIRST' : 'CARD';
    await fees.getByRole('button', { name: 'Change' }).click();
    const override = page.getByRole('dialog', { name: 'How Ade’s Kitchen pays its plan' });
    await override.getByLabel('Plan fees').selectOption(target);
    await override.getByLabel('Reason').selectOption('SELLER_REQUEST');
    await override.getByLabel('Note (optional)').fill(`E2E ${run}`);
    await override.getByRole('button', { name: 'Apply' }).click();
    await expect(override).toBeHidden();
    await expect(fees).toContainText(target === 'CARD' ? 'Card only' : 'From sales first, card for the rest');
    await expect(page.getByRole('list', { name: 'Changes' })).toContainText(`E2E ${run}`);
    await expect(page.getByRole('table', { name: 'Invoices paid from earnings' })).toContainText('$25.00');

    await page.getByRole('tab', { name: 'Grants log' }).click();
    await expect(page.getByRole('table', { name: 'Grants log' })).toContainText('$10 credit');

    // S298: the platform default for new sellers; the kill switch is shown, not changed here. Restored afterwards.
    await page.getByRole('tab', { name: 'Settings' }).click();
    await expect(page.getByTestId('kill-switch')).toContainText('On: sellers may pay their plan from their sales');
    const defaults = page.getByLabel('Default for new sellers');
    await defaults.selectOption('CARD');
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByText('Default saved')).toBeVisible();
    await defaults.selectOption('EARNINGS_FIRST');
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(defaults).toHaveValue('EARNINGS_FIRST');
  });

  /* S299: "1st month free (the trial) and 2nd month free if the seller sold less than $1,000". A LESS_THAN rule anchored
     at the subscription's start reads back in plain words, and the preview shows sellers still in their window as
     pending (with their sales so far) until it is checked, 24 h before the trial ends. Saved as a draft. */
  test('a low-sales relief rule in plain words, with sellers in their window pending', async ({ page }) => {
    const run = Date.now().toString(36).toUpperCase();
    await page.goto('/plans');
    await page.getByRole('tab', { name: 'Rewards' }).click();
    await expect(page.getByRole('table', { name: 'Rewards' })).toContainText('less than $1,000 in sales in the first 30 days of the subscription');
    await page.getByRole('button', { name: 'New reward rule' }).click();
    const rule = page.getByRole('dialog', { name: 'New reward rule' });
    await rule.getByLabel('Name').fill(`Quiet start ${run}`);
    await rule.getByLabel('Threshold ($ in sales)').fill('1000');
    await rule.getByLabel('Kind').selectOption('SELLER');
    await rule.getByLabel('Comparison').selectOption('LESS_THAN');
    await rule.getByLabel('Window starts at').selectOption('SUBSCRIPTION_START');
    await rule.getByLabel('First N days of the subscription').fill('30');
    await rule.getByLabel('Seller Growth').check();
    await expect(rule.getByTestId('rule-sentence')).toContainText(
      'Sellers on Seller Growth who sell less than $1,000 in the first 30 days of their subscription get 30 free days (checked 24 h before the trial ends).'
    );
    await rule.getByRole('button', { name: 'Check now' }).click();
    await expect(rule).toContainText(/still in their window/);
    await expect(rule.getByRole('table', { name: 'Who qualifies now' })).toContainText(/Pending · checked/);
    await rule.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(rule).toBeHidden();
    await expect(page.getByRole('table', { name: 'Rewards' }).getByRole('row', { name: new RegExp(`Quiet start ${run}`) })).toContainText('Draft');
  });
});
