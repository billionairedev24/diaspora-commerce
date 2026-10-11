import { expect, test } from '@playwright/test';

/* Staff console › Finance › Plans & billing (S292–S297) against the mock BFF: the plan catalogue with the Stripe sync
   status, a promo-code trial campaign, a reward rule with its "who qualifies now" preview, and a per-seller credit
   with a reason code that lands in the grants log. Names are unique per run so the journey can run again. */
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

    await page.getByRole('tab', { name: 'Grants log' }).click();
    await expect(page.getByRole('table', { name: 'Grants log' })).toContainText('$10 credit');
  });
});
