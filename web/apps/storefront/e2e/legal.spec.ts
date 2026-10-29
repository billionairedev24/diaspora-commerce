import { type BrowserContext, expect, test } from '@playwright/test';

/* Legal & privacy center (S191, S192) against the mock BFF: every document, the first-visit cookie banner,
   cookie preferences, Your Privacy Choices and unknown documents. Starts without a stored cookie choice. */
test.use({ storageState: { cookies: [], origins: [] } });

const DOCS: Array<[string, string]> = [
  ['conditions-of-use', 'Conditions of Use'],
  ['privacy', 'Privacy Notice'],
  ['cookies', 'Cookie Notice'],
  ['privacy-choices', 'Your Privacy Choices'],
  ['returns', 'Returns & Refunds'],
  ['seller-terms', 'Seller Terms'],
  ['prohibited-items', 'Prohibited & Restricted Items'],
  ['accessibility', 'Accessibility Statement'],
  ['sms-terms', 'SMS & Notification Terms'],
  ['gift-card-terms', 'Gift Card Terms'],
  ['store-credit-terms', 'Store Credit Terms'],
  ['membership-terms', 'Plus Membership Terms'],
  ['referral-terms', 'Referral Program Terms'],
  ['preorder-delivery-terms', 'Pre-order and Delivery Run Terms'],
  ['community-guidelines', 'Community Guidelines'],
  ['ip-policy', 'Intellectual Property and Copyright Policy'],
  ['provider-terms', 'Service Provider Terms'],
  ['payments-terms', 'Payment Terms for Sellers and Providers'],
  ['subprocessors', 'Sub-processors and Service Providers'],
  ['data-processing', 'Data Processing Terms'],
  ['seller-fees', 'Seller Fees Summary']
];

const GROUPS = ['Customers', 'Payments and money', 'Sellers and providers', 'Privacy and cookies', 'Policies'];

async function consent(context: BrowserContext) {
  const c = (await context.cookies()).find((x) => x.name === 'at_consent');
  return c ? (JSON.parse(decodeURIComponent(c.value)) as Record<string, unknown>) : null;
}

for (const [slug, title] of DOCS) {
  test(`${title} renders with its title, version and "On this page"`, async ({ page }) => {
    await page.goto(`/legal/${slug}`);
    await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible();
    const toc = page.getByRole('navigation', { name: 'On this page' });
    await expect(toc.getByRole('link').first()).toBeVisible();
    const first = toc.getByRole('link').first();
    const anchor = await first.getAttribute('href');
    await expect(page.locator(`[id="${anchor?.slice(1)}"]`)).toHaveCount(1);
    await expect(page.getByText(/^Version \d/).first()).toBeVisible();
    await expect(page.getByRole('navigation', { name: 'Legal documents' }).getByRole('link', { name: title })).toHaveAttribute('aria-current', 'page');
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
  });
}

for (const [slug, title] of DOCS) {
  test(`${title} in French shows the translation notice above the binding English text`, async ({ page, context, baseURL }) => {
    await context.addCookies([{ name: 'at_locale', value: 'fr', url: baseURL ?? 'http://localhost:3005' }]);
    await page.goto(`/legal/${slug}`);
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await expect(page.getByRole('note').filter({ hasText: /anglais|English/ }).first()).toBeVisible();
    await expect(page.locator('article[lang="en"]')).toBeVisible();
    await expect(page.locator('html')).toHaveAttribute('lang', 'fr');
  });
}

test('the legal center lists every document under its group', async ({ page }) => {
  await page.goto('/legal');
  await expect(page.getByRole('heading', { level: 1, name: 'Legal & privacy' })).toBeVisible();
  for (const group of GROUPS) await expect(page.getByRole('heading', { level: 2, name: group })).toBeVisible();
  for (const [slug, title] of DOCS) {
    await expect(page.locator('main').getByRole('link', { name: title, exact: true }).first()).toHaveAttribute('href', `/legal/${slug}`);
  }
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
});

test('every footer legal link and the legal center link land on a page', async ({ page }) => {
  await page.goto('/');
  const links = page.getByRole('navigation', { name: 'Legal' }).getByRole('link');
  const hrefs = await links.evaluateAll((as) => as.map((a) => a.getAttribute('href') ?? ''));
  expect(hrefs).toContain('/legal');
  expect(hrefs.length).toBeGreaterThanOrEqual(7);
  for (const href of hrefs) {
    const res = await page.goto(href);
    expect(res?.status(), href).toBe(200);
  }
});

test('legacy slugs redirect and unknown documents are 404 with a way back', async ({ page }) => {
  await page.goto('/legal/terms');
  await expect(page).toHaveURL(/\/legal\/conditions-of-use$/);
  for (const [alias, key] of [['gift-cards', 'gift-card-terms'], ['dpa', 'data-processing'], ['fees', 'seller-fees'], ['refunds', 'returns']]) {
    await page.goto(`/legal/${alias}`);
    await expect(page).toHaveURL(new RegExp(`/legal/${key}$`));
  }
  const res = await page.goto('/legal/not-a-policy');
  expect(res?.status()).toBe(404);
  await expect(page.getByRole('heading', { level: 1, name: 'Document not found' })).toBeVisible();
  await page.getByRole('link', { name: 'All legal documents' }).click();
  await expect(page).toHaveURL(/\/legal$/);
});

test('an archived version shows the notice and links to the current one; the print button is offered', async ({ page }) => {
  await page.goto('/legal/conditions-of-use?version=3.1');
  await expect(page.getByText('You are reading version 3.1, which is no longer in force.')).toBeVisible();
  await page.getByRole('link', { name: 'Read the current version' }).click();
  await expect(page).toHaveURL(/\/legal\/conditions-of-use$/);
  await expect(page.getByRole('button', { name: 'Print' })).toBeVisible();
});

test('cookie banner: Accept all is remembered', async ({ page, context }) => {
  await page.goto('/');
  const banner = page.getByRole('region', { name: 'Your cookie choices' });
  await expect(banner).toBeVisible();
  await banner.getByRole('button', { name: 'Accept all' }).click();
  await expect(banner).toBeHidden();
  await expect.poll(async () => (await consent(context))?.id).toBeTruthy();
  expect(await consent(context)).toMatchObject({ functional: true, analytics: true, advertising: true, dns: false });
  await page.reload();
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Your cookie choices' })).toHaveCount(0);
});

test('cookie banner: Reject non-essential is remembered and preferences can change it', async ({ page, context }) => {
  await page.goto('/');
  await page.getByRole('region', { name: 'Your cookie choices' }).getByRole('button', { name: 'Reject non-essential' }).click();
  await expect.poll(async () => (await consent(context))?.analytics).toBe(false);
  await page.goto('/legal/cookies');
  const prefs = page.getByRole('region', { name: 'Cookie preferences' });
  const analytics = prefs.getByRole('switch', { name: 'Analytics' });
  await expect(analytics).toHaveAttribute('aria-checked', 'false');
  await analytics.click();
  await prefs.getByRole('button', { name: 'Save preferences' }).click();
  await expect(prefs.getByRole('status')).toHaveText('Preferences saved.');
  expect(await consent(context)).toMatchObject({ analytics: true, advertising: false });
  await expect(page.getByRole('table', { name: 'Cookies we use' }).getByRole('rowheader', { name: 'at_consent' })).toBeVisible();
});

test.describe('with Global Privacy Control', () => {
  test.use({ extraHTTPHeaders: { 'Sec-GPC': '1' } });
  test('advertising is locked off', async ({ page, context }) => {
    await page.addInitScript(() => Object.defineProperty(navigator, 'globalPrivacyControl', { value: true }));
    await page.goto('/legal/cookies');
    await expect(page.getByText(/Your browser sent a Global Privacy Control signal/).first()).toBeVisible();
    await expect(page.getByRole('switch', { name: 'Advertising' })).toBeDisabled();
    await page.getByRole('button', { name: 'Accept all' }).click();
    await expect.poll(async () => (await consent(context))?.dns).toBe(true);
    expect(await consent(context)).toMatchObject({ advertising: false, analytics: true, gpc: true });
  });
});

test('Your Privacy Choices: privacy request and "Do not sell or share"', async ({ page, context }) => {
  await page.goto('/legal/privacy-choices');
  const form = page.getByRole('form', { name: 'Make a privacy request' });
  await form.getByRole('button', { name: 'Submit request' }).click();
  await expect(page.getByRole('alert').filter({ hasText: 'There are 3 problems with your request' })).toBeVisible();
  await form.getByRole('radio', { name: 'Get a copy of my data' }).check();
  await form.getByLabel('Email on your account').fill('ada@example.com');
  await form.getByLabel('State of residence').selectOption('NY');
  await form.getByRole('button', { name: 'Submit request' }).click();
  await expect(form.getByRole('status')).toContainText(/Request received\. Reference PR-\d{6}\./);

  const dns = page.getByRole('switch', { name: 'Do not sell or share my personal information' });
  await dns.click();
  await expect(dns).toHaveAttribute('aria-checked', 'true');
  await expect.poll(async () => (await consent(context))?.dns).toBe(true);
});
