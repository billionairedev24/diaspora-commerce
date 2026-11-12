import { expect, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';

/* The storefront as an installable web app (docs/engineering/pwa.md): manifest, service worker script, offline page. */
test.describe('storefront · installable web app', () => {
  test('the home page links the manifest, which is served as the manifest media type', async ({ page, request }) => {
    await page.goto('/');
    const href = await page.locator('link[rel="manifest"]').getAttribute('href');
    expect(href).toBe('/manifest.webmanifest');
    const res = await request.get('/manifest.webmanifest');
    expect(res.status()).toBe(200);
    expect(res.headers()['content-type']).toMatch(/^application\/manifest\+json/);
    const manifest = (await res.json()) as { display: string; scope: string; start_url: string; icons: Array<{ src: string; sizes: string; purpose?: string }> };
    expect(manifest).toMatchObject({ display: 'standalone', scope: '/' });
    for (const icon of manifest.icons) expect((await request.get(icon.src)).status(), icon.src).toBe(200);
    expect(manifest.icons.map((i) => i.sizes)).toEqual(expect.arrayContaining(['192x192', '512x512']));
    expect(manifest.icons.some((i) => i.purpose === 'maskable')).toBe(true);
  });

  test('the service worker script is JavaScript and never cached for long', async ({ request }) => {
    const res = await request.get('/sw.js');
    expect(res.status()).toBe(200);
    expect(res.headers()['content-type']).toMatch(/javascript/);
    expect(res.headers()['cache-control']).toMatch(/no-cache/);
    const source = await res.text();
    expect(source).toContain('/checkout');
    expect(source).toContain('/api');
  });

  test('the offline page is accessible and offers a way back', async ({ page }, testInfo) => {
    await page.goto('/offline');
    await expect(page.getByRole('heading', { level: 1, name: 'You are offline' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Try again' })).toBeVisible();
    await expectNoSeriousA11yViolations(page, testInfo, { page: '/offline' });
  });
});
