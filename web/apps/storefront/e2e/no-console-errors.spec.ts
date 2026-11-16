import { expect, test } from '@playwright/test';

/* The home page must not log network or CORS errors. Regression: a <Link href="/sell"> on the home (brand hero, sell band) was
   prefetched with an RSC fetch (`/sell?_rsc=`); /sell redirects to Seller Center on another origin, so the browser blocked it
   ("Access to fetch ... has been blocked by CORS policy"). /sell and /provide are plain links now (src/lib/sell/handoff.ts). */

test('the home page logs no CORS errors and does not fetch /sell or /provide in the background', async ({ page }) => {
  const errors: string[] = [];
  const handoffFetches: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push(m.text());
  });
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('request', (r) => {
    const u = new URL(r.url());
    if (/^\/(sell|provide)(\/|$)/.test(u.pathname)) handoffFetches.push(r.url());
  });

  await page.goto('/');
  await page.waitForLoadState('networkidle');
  // Scroll to the foot so lazily rendered bands (the sell band) and their links are in the page and in view.
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await page.waitForLoadState('networkidle');

  expect(errors.filter((e) => /cors|access-control|blocked by|has been blocked/i.test(e))).toEqual([]);
  expect(handoffFetches).toEqual([]);
  // The sign-in-to-sell journey still starts from a plain link to /sell.
  const sell = page.locator('a[href="/sell"]').first();
  await expect(sell).toBeAttached();
});
