import { expect, test } from '@playwright/test';

/* Back office › Delivery: AfroTransact staff run an AfroTransact-delivered order end to end (no driver app) against the
   in-process mock BFF (default role: platform super admin). Screenshots for docs/engineering/delivery-fulfilment.md are
   written when E2E_SHOTS=1 (npm run e2e with E2E_SHOTS=1). */
const SHOTS = process.env.E2E_SHOTS === '1';
const IMG = '../../docs/engineering/img';

// A 1x1 JPEG, enough for the app's type and size checks.
const JPEG = Buffer.from(
  '/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wgALCAABAAEBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA=',
  'base64'
);

test.describe('staff console · delivery desk', () => {
  test('takes a delivery, goes out, delivers with a photo, and shows the proof with the audit trail', async ({ page }) => {
    await page.goto('/delivery');
    await expect(page.getByRole('heading', { name: 'Delivery', exact: true }).first()).toBeVisible();
    const rows = page.getByTestId('delivery-row');
    await expect(rows.first()).toBeVisible();
    // Names, never ids, and nothing about a driver.
    await expect(page.locator('main')).not.toContainText(/shp-dl|stf-/);
    await expect(page.locator('main')).not.toContainText(/driver/i);
    if (SHOTS) {
      await page.setViewportSize({ width: 1280, height: 800 });
      await page.screenshot({ path: `${IMG}/delivery-desk-queue-1280.png` });
    }

    await rows.filter({ hasText: 'AT-2026-004901' }).click();
    const sheet = page.getByTestId('delivery-sheet');
    await expect(sheet).toBeVisible();
    await expect(sheet.getByRole('button', { name: /Mark delivered/ })).toHaveCount(0);
    await sheet.getByRole('button', { name: 'Take this delivery' }).click();
    await sheet.getByRole('button', { name: 'Picked up from the seller…' }).click();
    const pickup = page.getByRole('dialog', { name: 'Picked up from the seller' });
    await pickup.getByLabel('Pickup note (optional)').fill('2 boxes handed over at the shop');
    await pickup.getByRole('button', { name: 'Mark picked up' }).click();
    await sheet.getByRole('button', { name: 'Mark out for delivery' }).click();
    await sheet.getByRole('button', { name: /Mark delivered/ }).click();

    // Nothing is delivered without a photo.
    const dialog = page.getByRole('dialog', { name: /Mark AT-2026-004901 delivered/ });
    await dialog.getByRole('button', { name: 'Mark delivered' }).click();
    await expect(dialog.getByText('Add at least one photo as proof of delivery.')).toBeVisible();
    await dialog.getByLabel('Add a photo').setInputFiles({ name: 'door.jpg', mimeType: 'image/jpeg', buffer: JPEG });
    await expect(dialog.getByTestId('proof-photo')).toHaveCount(1);
    await dialog.getByLabel('Who received it (optional)').fill('Ada Obi');
    await dialog.getByLabel('Note (optional)').fill('Left with the neighbour');
    if (SHOTS) await page.screenshot({ path: `${IMG}/delivery-desk-proof-1280.png` });
    await dialog.getByRole('button', { name: 'Mark delivered' }).click();

    const proof = page.getByTestId('delivery-proof-section');
    await expect(proof.getByTestId('delivery-proof-photo')).toBeVisible();
    await expect(proof).toContainText('Ada Obi');
    await expect(proof).toContainText('Left with the neighbour');
    await expect(page.getByTestId('delivery-audit').getByRole('listitem').first()).toContainText('Delivered with proof');
    if (SHOTS) {
      await page.waitForTimeout(7000); // let the toasts leave the screenshot
      await proof.scrollIntoViewIfNeeded();
      await page.screenshot({ path: `${IMG}/delivery-desk-delivered-1280.png` });
      await page.setViewportSize({ width: 390, height: 844 });
      await page.screenshot({ path: `${IMG}/delivery-desk-delivered-390.png` });
      await page.goto('/delivery');
      await expect(page.getByTestId('delivery-row').first()).toBeVisible();
      await page.screenshot({ path: `${IMG}/delivery-desk-queue-390.png` });
    }
  });

  test('records a failed attempt with a reason', async ({ page }) => {
    await page.goto('/delivery');
    await page.getByTestId('delivery-row').filter({ hasText: 'AT-2026-004911' }).click();
    const sheet = page.getByTestId('delivery-sheet');
    await sheet.getByRole('button', { name: /Record a failed attempt/ }).click();
    const dialog = page.getByRole('dialog', { name: 'Record a failed attempt' });
    await dialog.getByRole('button', { name: 'Record failed attempt' }).click();
    await expect(dialog.getByText(/Say why it failed/)).toBeVisible();
    await dialog.getByLabel('What happened?').fill('Nobody home, left a card');
    await dialog.getByRole('button', { name: 'Record failed attempt' }).click();
    await expect(sheet.getByRole('button', { name: /Try again/ })).toBeVisible();
  });
});
