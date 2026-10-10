import { expect, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';

/* Notifications → Templates (S268) against the in-process mock BFF (default role: platform super admin, who holds
   notifications:templates:write in the mocks). Mock state lives in the app server process, so the journey tolerates
   versions created by an earlier run. */
test.describe('staff console · notification templates', () => {
  test('opens a template, previews it per channel, and creates a new SMS version @a11y', async ({ page }, testInfo) => {
    const run = Date.now().toString(36).slice(-5).toUpperCase();

    // List: searchable, every key with its channels and latest version.
    await page.goto('/notifications');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Notifications');
    const table = page.getByRole('table', { name: 'Notification templates' });
    await expect(table.getByRole('link', { name: 'order.confirmed' })).toBeVisible();
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'staff notification templates' });
    await page.getByRole('searchbox', { name: 'Search templates' }).fill('order');
    await expect(table.getByRole('link', { name: 'identity.verification' })).toHaveCount(0);
    await table.getByRole('link', { name: 'order.confirmed' }).click();

    // Detail: email preview rendered by the BFF with the event's sample data, in a sandboxed iframe.
    await expect(page.getByTestId('template-key')).toHaveText('order.confirmed');
    await expect(page.getByTestId('preview-subject')).toHaveText('Order AT-2026-7KQ3MD is confirmed');
    const frame = page.frameLocator('iframe[title^="Email:"]');
    await expect(frame.getByRole('heading', { name: 'Thanks for your order, Ada!' })).toBeVisible();
    await page.getByRole('button', { name: 'Mobile' }).click();
    await expect(page.getByRole('button', { name: 'Mobile' })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByTestId('template-version').first()).toBeVisible();
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'staff notification template' });

    // SMS variant: phone bubble with the segment count.
    await page.getByRole('button', { name: /^SMS · en · v\d+$/ }).click();
    await expect(page.getByTestId('preview-sms')).toContainText('AT-2026-7KQ3MD');
    await expect(page.getByTestId('sms-count')).toContainText('GSM-7');

    // New version: live draft preview, then created and live.
    await page.getByRole('button', { name: 'New version' }).click();
    const create = page.getByRole('button', { name: /^Create version \d+$/ });
    const next = Number(((await create.textContent()) ?? '').replace(/\D+/g, ''));
    const message = page.getByRole('textbox', { name: 'Message' });
    await message.fill(`AfroTransact: order {{.orderNumber}} ({{.total}}) confirmed, ref ${run}. Reply STOP to opt out.`);
    await expect(page.getByTestId('preview-sms')).toContainText(`order AT-2026-7KQ3MD ($48.00) confirmed, ref ${run}.`);
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'staff notification template editor' });
    await create.click();
    await expect(page.getByText(`Version ${next} created`)).toBeVisible();
    await expect(page.getByRole('button', { name: `SMS · en · v${next}` })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByTestId('preview-sms')).toContainText(`ref ${run}`);
  });
});
