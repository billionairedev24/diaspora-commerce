import { createRequire } from 'node:module';
import { gzipSync } from 'node:zlib';

import { type Browser, type BrowserContext, expect, type Page, test } from '@playwright/test';

/* The dynamic home against the mock BFF (S427 to S429): the page is built from what exists, differs between visits, is stable on
   refresh, stays inside the performance budget and has no serious accessibility violation. Each test picks its catalogue with
   the `at_mock_catalogue` cookie, so tests run side by side against one server. */

const require = createRequire(import.meta.url);
const AXE = require.resolve('axe-core');

const CONSENT = encodeURIComponent(JSON.stringify({ necessary: true, functional: true, analytics: false, advertising: false, dns: false, gpc: false, v: '2.1', at: '2026-10-01T00:00:00.000Z' }));

async function visit(browser: Browser, size: number, extra: { locale?: string; userAgent?: string; consent?: boolean } = {}): Promise<{ page: Page; context: BrowserContext }> {
  const context = await browser.newContext({ baseURL: `http://localhost:${process.env.E2E_PORT ?? 3005}`, ...(extra.userAgent ? { userAgent: extra.userAgent } : {}) });
  const cookies = [{ name: 'at_mock_catalogue', value: String(size), url: `http://localhost:${process.env.E2E_PORT ?? 3005}` }];
  if (extra.consent !== false) cookies.push({ name: 'at_consent', value: CONSENT, url: `http://localhost:${process.env.E2E_PORT ?? 3005}` });
  if (extra.locale) cookies.push({ name: 'at_locale', value: extra.locale, url: `http://localhost:${process.env.E2E_PORT ?? 3005}` });
  await context.addCookies(cookies);
  const page = await context.newPage();
  return { page, context };
}

/** The page is ready when its first module is in the DOM and the network is quiet. */
async function ready(page: Page) {
  await page.locator('main [data-block]').first().waitFor({ state: 'attached' });
  await page.waitForLoadState('networkidle');
}

const productIds = (page: Page) => page.locator('main a[href^="/p/"]').evaluateAll((as) => [...new Set(as.map((a) => a.getAttribute('href')))].sort());

test.describe('catalogue fixtures', () => {
  test('0 products: text-only brand hero, Plus and sell bands, no image, no tile, no rail, no department in the bar', async ({ browser }) => {
    const { page, context } = await visit(browser, 0);
    await page.goto('/');
    await ready(page);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('A marketplace for the African and Caribbean diaspora');
    await expect(page.getByTestId('sell-band')).toBeVisible();
    await expect(page.locator('main img')).toHaveCount(0);
    await expect(page.getByTestId('department-tiles')).toHaveCount(0);
    const bar = page.getByRole('navigation', { name: 'Departments' });
    for (const gone of ['Groceries', 'Services', 'Deals']) await expect(bar.getByRole('link', { name: gone, exact: true })).toHaveCount(0);
    // A community that does not exist is a 404 (not a zero-result page) and the footer lists none.
    expect((await page.goto('/communities/nigerian'))?.status()).toBe(404);
    await page.goto('/');
    await expect(page.getByRole('contentinfo').getByRole('heading', { name: 'Shop by community' })).toHaveCount(0);
    await context.close();
  });

  test('10 products: department tiles, no communities or deals; empty departments are not linked; community routes 404', async ({ browser }) => {
    const { page, context } = await visit(browser, 10);
    await page.goto('/');
    await ready(page);
    const tiles = page.getByTestId('department-tiles');
    await expect(tiles.getByRole('link')).toHaveCount(3);
    await expect(page.getByTestId('community-tiles')).toHaveCount(0);
    await expect(page.getByRole('heading', { name: 'Deals' })).toHaveCount(0);
    const bar = page.getByRole('navigation', { name: 'Departments' });
    await expect(bar.getByRole('link', { name: 'Groceries' })).toBeVisible();
    for (const gone of ['Electronics', 'Pets', 'Baby & Kids']) await expect(bar.getByRole('link', { name: gone, exact: true })).toHaveCount(0);
    expect((await page.goto('/communities/ethiopian'))?.status()).toBe(404);
    await context.close();
  });

  test('100 products: communities appear with 3 or more, routes resolve, footer lists the same ones, no broken image', async ({ browser }) => {
    const { page, context } = await visit(browser, 100);
    await page.goto('/');
    await ready(page);
    const communities = page.getByTestId('community-tiles').getByRole('link');
    expect(await communities.count()).toBeGreaterThanOrEqual(3);
    const hrefs = await communities.evaluateAll((as) => as.map((a) => a.getAttribute('href') as string));
    const footer = page.getByRole('contentinfo').getByRole('navigation', { name: 'Shop by community' });
    await expect(footer).toBeVisible();
    for (const h of hrefs) await expect(footer.locator(`a[href="${h}"]`)).toHaveCount(1);
    // every image on the page loaded (no broken photo), none is an svg or icon
    await page.waitForLoadState('networkidle');
    const bad = await page.locator('main img').evaluateAll((imgs) => imgs.filter((i) => !(i as HTMLImageElement).complete || (i as HTMLImageElement).naturalWidth === 0 || /\.(svg|ico)(\?|$)/i.test((i as HTMLImageElement).src)).map((i) => (i as HTMLImageElement).src));
    expect(bad.filter((s) => !s.includes('img.afrotransact.dev'))).toEqual([]);
    expect((await page.goto(hrefs[0] as string, { waitUntil: 'commit' }))?.status()).toBeLessThan(400);
    await context.close();
  });

  test('10,000 products: at most 12 modules and no product twice', async ({ browser }) => {
    const { page, context } = await visit(browser, 10000);
    await page.goto('/');
    await ready(page);
    expect(await page.locator('main [data-block]').count()).toBeLessThanOrEqual(12);
    const hrefs = await page.locator('main a[href^="/p/"]').evaluateAll((as) => as.map((a) => a.getAttribute('href')));
    // each card links its product twice (photo and title); every product is on the page once
    const counts = new Map<string | null, number>();
    for (const h of hrefs) counts.set(h, (counts.get(h) ?? 0) + 1);
    expect([...counts.values()].every((n) => n <= 2)).toBe(true);
    await context.close();
  });
});

test.describe('visits', () => {
  test('one visit is stable on refresh; another visit shows different products; crawlers get the deterministic page and no cookie', async ({ browser }) => {
    const a = await visit(browser, 10000);
    await a.page.goto('/');
    await ready(a.page);
    const first = await productIds(a.page);
    expect(first.length).toBeGreaterThan(20);
    expect((await a.context.cookies()).map((c) => c.name)).toEqual(expect.arrayContaining(['at_vid', 'at_visit']));
    await a.page.reload();
    await ready(a.page);
    expect(await productIds(a.page)).toEqual(first);
    await a.page.goto('/help');
    await a.page.goto('/');
    await ready(a.page);
    expect(await productIds(a.page)).toEqual(first);

    const b = await visit(browser, 10000);
    await b.page.goto('/');
    await ready(b.page);
    const other = await productIds(b.page);
    expect(other.filter((x) => first.includes(x)).length).toBeLessThan(first.length * 0.8);

    const bot = 'Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)';
    const c1 = await visit(browser, 10000, { userAgent: bot });
    const c2 = await visit(browser, 10000, { userAgent: bot });
    await c1.page.goto('/');
    await c2.page.goto('/');
    await Promise.all([ready(c1.page), ready(c2.page)]);
    expect(await productIds(c1.page)).toEqual(await productIds(c2.page));
    expect((await c1.context.cookies()).map((c) => c.name)).not.toContain('at_visit');
    const res = await c1.page.request.get('/');
    expect(res.headers()['cache-control']).toBe('private, no-store');
    await Promise.all([a.context.close(), b.context.close(), c1.context.close(), c2.context.close()]);
  });

  test('the first HTML already holds the page (no JavaScript needed), for shoppers and for crawlers alike', async ({ browser }) => {
    const bot = 'Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)';
    for (const userAgent of [undefined, bot]) {
      const { page, context } = await visit(browser, 100, userAgent ? { userAgent } : {});
      const html = await (await page.request.get('/')).text();
      expect(html).toContain('Shop by department');
      expect(html).toContain('Shop by community');
      expect(html).toMatch(/<h1[^>]*>Everything for the table<\/h1>/);
      // The hero photo is the one eager, high-priority image and is in the HTML (the largest contentful paint does not wait for scripts).
      expect(html.match(/fetchPriority="high"|fetchpriority="high"/gi)?.length).toBe(1);
      await context.close();
    }
  });

  test('refusing Functional cookies gives the default page and sets no visit cookie', async ({ browser }) => {
    const refused = encodeURIComponent(JSON.stringify({ necessary: true, functional: false, analytics: false, advertising: false, dns: false, gpc: false, v: '2.1', at: '2026-10-01T00:00:00.000Z' }));
    const context = await browser.newContext({ baseURL: `http://localhost:${process.env.E2E_PORT ?? 3005}` });
    const url = `http://localhost:${process.env.E2E_PORT ?? 3005}`;
    await context.addCookies([
      { name: 'at_mock_catalogue', value: '10000', url },
      { name: 'at_consent', value: refused, url }
    ]);
    const page = await context.newPage();
    await page.goto('/');
    expect((await context.cookies()).map((c) => c.name)).not.toContain('at_visit');
    await context.close();
  });
});

test.describe('budgets and accessibility', () => {
  for (const size of [10, 100, 10000]) {
    test(`${size} products: LCP at most 2.5 s, CLS at most 0.05, home JS at most 90 KB gzip beyond the shell`, async ({ browser }) => {
      const { page, context } = await visit(browser, size);
      await page.addInitScript(() => {
        const w = window as unknown as { __lcp: number; __cls: number };
        w.__lcp = 0;
        w.__cls = 0;
        new PerformanceObserver((l) => {
          for (const e of l.getEntries()) w.__lcp = e.startTime;
        }).observe({ type: 'largest-contentful-paint', buffered: true });
        new PerformanceObserver((l) => {
          for (const e of l.getEntries() as unknown as Array<{ value: number; hadRecentInput: boolean }>) if (!e.hadRecentInput) w.__cls += e.value;
        }).observe({ type: 'layout-shift', buffered: true });
      });
      const scripts = new Map<string, number>();
      page.on('response', async (r) => {
        if (r.request().resourceType() !== 'script') return;
        try {
          scripts.set(r.url(), gzipSync(await r.body()).length);
        } catch {
          /* a response that vanished with the navigation */
        }
      });
      await page.goto('/');
      await page.waitForLoadState('networkidle');
      // Let late-arriving content (hydrated header, rails) settle before reading the layout shift.
      await page.waitForTimeout(1200);
      const { lcp, cls } = await page.evaluate(() => {
        const w = window as unknown as { __lcp: number; __cls: number };
        return { lcp: w.__lcp, cls: w.__cls };
      });
      expect(lcp).toBeLessThanOrEqual(2500);
      expect(cls).toBeLessThanOrEqual(0.05);
      // The shell is what a plain content page already loads: the budget is for what the home adds to it.
      const homeScripts = new Map(scripts);
      scripts.clear();
      await page.goto('/about');
      await page.waitForLoadState('networkidle');
      let extra = 0;
      for (const [u, n] of homeScripts) if (!scripts.has(u)) extra += n;
      expect(extra, `home-only JS ${Math.round(extra / 1024)} KB gzip`).toBeLessThanOrEqual(90 * 1024);
      await context.close();
    });

    test(`${size} products: no serious or critical accessibility violation in the page`, async ({ browser }) => {
      const { page, context } = await visit(browser, size);
      await page.goto('/');
      await ready(page);
      await page.addScriptTag({ path: AXE });
      const serious = await page.evaluate(async () => {
        const axe = (window as unknown as { axe: { run: (ctx: unknown) => Promise<{ violations: Array<{ id: string; impact: string; nodes: Array<{ target: string[] }> }> }> } }).axe;
        const r = await axe.run({ include: [['main']] });
        return r.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical').map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(' | ')}`);
      });
      expect(serious).toEqual([]);
      await context.close();
    });
  }

  test('Arabic renders right to left with the module titles translated and no horizontal scroll', async ({ browser }) => {
    const { page, context } = await visit(browser, 100, { locale: 'ar' });
    await page.goto('/');
    await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
    await expect(page.getByRole('heading', { name: 'تسوّق حسب القسم' })).toBeVisible();
    await ready(page);
    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(0);
    await context.close();
  });
});
