import { expect, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';
import { noSidewaysScroll, SIZES } from '../support/layout';

/* Orders & shipments › Set status, per fulfilment mode, against the in-process mock BFF (default role: Super Admin).
   AT-200400 has two packages: Fulfilled by AfroTransact (ready) and a seller who ships it themselves (packed); AT-200421
   is a seller pickup order. The flow: take the delivery steps from the sheet, prove the delivery with a photo inside the
   sheet, see the seller's shipment as the seller's with an override that needs a reason, and read the accurate reasons.
   Each step is checked at 1280 and 390 for alignment (the menu button and the override link sit on the shipment's right
   edge, one control per line on a phone) and with axe. */

const JPEG = Buffer.from(
  '/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wgALCAABAAEBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA=',
  'base64'
);

for (const size of SIZES) {
  test.describe(`staff console · order sheet shipments · ${size.name}`, () => {
    test.use({ viewport: { width: size.width, height: size.height } });

    test('takes the delivery steps from the sheet, proves the delivery with a photo, and the seller’s shipment stays the seller’s', async ({ page }, testInfo) => {
      await page.goto('/orders?open=AT-200400');
      const sheet = page.getByRole('dialog', { name: 'Order AT-200400' });
      const rows = sheet.getByTestId('shipment-row');
      await expect(rows).toHaveCount(2);
      const afro = rows.filter({ hasText: 'AfroTransact' }).first();
      const seller = rows.filter({ hasText: 'Patel Grocers' });

      // The three modes are named in plain words; nothing says driver or a carrier.
      await expect(afro).toContainText('Fulfilled by AfroTransact');
      await expect(seller).toContainText('Seller ships this order');
      await expect(sheet).not.toContainText(/driver|ups|fedex|usps|dhl/i);
      await noSidewaysScroll(page, 'order sheet');

      // Alignment: each shipment's controls sit on the right edge of the row (stacked, right aligned), and do not overflow it.
      for (const row of [afro, seller]) {
        const box = await row.boundingBox();
        const controls = await row.getByRole('button').evaluateAll((els) => els.map((el) => el.getBoundingClientRect()));
        for (const c of controls) {
          expect(c.right, 'a control stays inside its shipment row').toBeLessThanOrEqual((box?.x ?? 0) + (box?.width ?? 0) + 1);
          expect(c.left).toBeGreaterThanOrEqual((box?.x ?? 0) - 1);
        }
      }
      await expectNoSeriousA11yViolations(page, testInfo, { page: `order sheet ${size.name}` });

      // Menu: accurate reasons, never the generic "Not available from here" for a step that is just not next.
      await afro.getByRole('button', { name: /^Set status of AfroTransact/ }).click();
      const menu = page.getByRole('menu');
      await expect(menu.getByRole('menuitem', { name: /^Picked up from the seller/ })).toBeEnabled();
      await expect(menu.getByRole('menuitem', { name: /^Delivered/ })).toContainText('Finish earlier steps first');
      await expectNoSeriousA11yViolations(page, testInfo, { page: `order sheet menu ${size.name}`, include: '[role="menu"]' });
      await menu.getByRole('menuitem', { name: /^Picked up from the seller/ }).click();
      await page.getByRole('dialog', { name: 'Set shipment to Picked up from the seller?' }).getByLabel('Note (optional)').fill('2 boxes from the kitchen');
      await page.getByRole('button', { name: 'Set to Picked up from the seller' }).click();
      await expect(afro).toContainText('Picked up from the seller');

      // Out for delivery, then delivered with a photo, inside the sheet.
      await afro.getByRole('button', { name: /^Set status of AfroTransact/ }).click();
      await page.getByRole('menuitem', { name: /^Out for delivery/ }).click();
      await page.getByRole('button', { name: 'Set to Out for delivery' }).click();
      await expect(afro).toContainText('Out for delivery');
      await afro.getByRole('button', { name: /^Set status of AfroTransact/ }).click();
      await page.getByRole('menuitem', { name: /^Delivered/ }).click();
      const proof = page.getByRole('dialog', { name: /Mark AT-200400 delivered/ });
      await proof.getByRole('button', { name: 'Mark delivered' }).click();
      await expect(proof.getByText('Add at least one photo as proof of delivery.')).toBeVisible();
      await proof.getByLabel('Add a photo').setInputFiles({ name: 'door.jpg', mimeType: 'image/jpeg', buffer: JPEG });
      await expect(proof.getByTestId('proof-photo')).toHaveCount(1);
      await proof.getByLabel('Who received it (optional)').fill('Ada Obi');
      await expectNoSeriousA11yViolations(page, testInfo, { page: `proof capture ${size.name}`, include: '[role="dialog"]' });
      await proof.getByRole('button', { name: 'Mark delivered' }).click();
      await expect(afro).toContainText('Delivered');
      await afro.getByText(/^History \(/).click();
      await expect(afro).toContainText('2 boxes from the kitchen');

      // The seller's shipment: read-only reasons, then an override that needs a reason and only goes forward.
      await seller.getByRole('button', { name: /^Set status of Patel Grocers/ }).click();
      await expect(page.getByRole('menuitem', { name: /^Ready/ })).toContainText('Updated by the seller');
      await page.keyboard.press('Escape');
      await seller.getByRole('button', { name: 'Override status' }).click();
      const override = page.getByRole('dialog', { name: 'Override the seller’s status' });
      await override.getByRole('button', { name: 'Override' }).click();
      await expect(override.getByText('Say why to continue.')).toBeVisible();
      await override.getByLabel('Why are you overriding it? (required)').fill('Seller phoned, it left this morning');
      await expectNoSeriousA11yViolations(page, testInfo, { page: `override dialog ${size.name}`, include: '[role="dialog"]' });
      await override.getByRole('button', { name: 'Override' }).click();
      await expect(seller).toContainText('Shipped');
    });

    test('a pickup order is Ready for pickup, Collected or Not collected, and the delivery steps say why they are not offered', async ({ page }, testInfo) => {
      await page.goto('/orders?open=AT-200421');
      const sheet = page.getByRole('dialog', { name: 'Order AT-200421' });
      const row = sheet.getByTestId('shipment-row').first();
      await expect(row).toContainText('Seller pickup');
      await row.getByRole('button', { name: /^Set status of/ }).click();
      const menu = page.getByRole('menu');
      await expect(menu.getByRole('menuitem', { name: 'Collected' })).toBeEnabled();
      await expect(menu.getByRole('menuitem', { name: 'Not collected' })).toBeEnabled();
      await expect(menu.getByRole('menuitem', { name: /^Out for delivery/ })).toContainText('Not available for pickup orders');
      await expectNoSeriousA11yViolations(page, testInfo, { page: `pickup order menu ${size.name}`, include: '[role="menu"]' });
    });
  });
}
