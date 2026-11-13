/* S429: the home rendered from the mock gateway for 0 / 10 / 100 / 10,000 eligible products, in five languages, plus the visit-seed
   behaviour (different visits differ, one visit is stable on refresh, crawlers get the deterministic page). The page is the
   gateway's module list drawn by the storefront's module registry. */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { createApiClient, type Home, homeVisitHeaders, type SiteNavigation, type VisitHeaders } from '@afrotransact/api-client';
import { homeMock, resetHomeMock } from '@afrotransact/api-client/mocks';
import { handleMockRequest } from '@afrotransact/api-client/mocks/runtime';
import { createTranslator, dirOf, type Locale } from '@afrotransact/i18n';
import { cleanup, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { Hero } from '@/components/home/Hero';
import { HomeBlocks } from '@/components/home/HomeBlocks';
import { messagesFor } from '@/i18n/catalogs';
import { EN_MESSAGES } from '@/i18n/t';
import { isRealPhoto } from '@/lib/home/photo';

import { installMockBff } from './mockBff';
import { renderServer } from './serverTree';

const state = vi.hoisted(() => ({ locale: 'en' as string }));
vi.mock('@/i18n/server', async () => {
  const { EN_MESSAGES: en } = await import('@/i18n/t');
  const { messagesFor: messages } = await import('@/i18n/catalogs');
  const { createTranslator: create } = await import('@afrotransact/i18n');
  return { getLocale: async () => state.locale, getT: async () => create(en, state.locale as Locale, messages(state.locale as Locale)) };
});

installMockBff();

const api = (visit: VisitHeaders = {}) => createApiClient({ baseUrl: 'http://storefront.test/api/bff', headers: homeVisitHeaders(visit), fetch: (r) => handleMockRequest(r) });
const homeFor = async (size: number, visit: VisitHeaders = {}): Promise<Home> => {
  homeMock.catalogue = size;
  return api(visit).getHome();
};
const navFor = async (size: number): Promise<SiteNavigation> => {
  homeMock.catalogue = size;
  return api().getNavigation();
};

async function show(home: Home) {
  const heroPhoto = home.hero.some((s) => isRealPhoto(s.imageUrl));
  const body = await HomeBlocks({ blocks: home.blocks, lcpTiles: !heroPhoto });
  return renderServer(
    <>
      <Hero slides={home.hero} flat />
      {body}
    </>
  );
}

const productLinks = (root: HTMLElement) => [...root.querySelectorAll<HTMLAnchorElement>('a[href^="/p/"]')].map((a) => a.getAttribute('href') as string);

beforeEach(() => {
  state.locale = 'en';
  resetHomeMock();
});
afterEach(() => {
  cleanup();
  resetHomeMock();
});

describe('the home at 0 / 10 / 100 / 10,000 products', () => {
  it('0 products: a text-only brand hero, the Plus and sell bands; no tile, no rail, no image', async () => {
    const { container } = await show(await homeFor(0));
    expect(screen.getByRole('heading', { level: 1, name: 'A marketplace for the African and Caribbean diaspora' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Sell on AfroTransact' })).toHaveAttribute('href', '/sell');
    expect(screen.getByRole('link', { name: 'Start selling' })).toHaveAttribute('href', '/sell');
    expect(container.querySelectorAll('img')).toHaveLength(0);
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual(['AfroTransact Plus', 'Sell on AfroTransact']);
    expect(screen.queryByTestId('department-tiles')).not.toBeInTheDocument();
    expect(container.textContent).not.toMatch(/Shop by community|Deals|Top rated|Just landed/);
  });

  it('10 products: department tiles for the departments that exist, no deals, communities or top sellers, Services as text without a photo', async () => {
    const home = await homeFor(10);
    const { container } = await show(home);
    const tiles = within(screen.getByTestId('department-tiles'));
    expect(tiles.getByRole('heading', { name: 'Shop by department' })).toBeInTheDocument();
    expect(tiles.getAllByRole('link')).toHaveLength(3);
    expect(container.textContent).not.toMatch(/Shop by community|Deals|Top rated/);
    const services = within(screen.getByTestId('service-cards'));
    expect(services.getAllByRole('link').length).toBeGreaterThan(0);
  });

  it('100 products: communities (3 or more), deals, groceries and the hero join; exactly one eager image, and it is the hero photo', async () => {
    const home = await homeFor(100);
    const { container } = await show(home);
    const communities = within(screen.getByTestId('community-tiles'));
    expect(communities.getByRole('heading', { name: 'Shop by community' })).toBeInTheDocument();
    expect(communities.getAllByRole('link').length).toBeGreaterThanOrEqual(3);
    for (const a of communities.getAllByRole('link')) expect(a.getAttribute('href')).toMatch(/^\/communities\/[a-z0-9-]+$/);
    expect(screen.getByRole('heading', { name: 'Deals' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Featured promotions' })).toBeInTheDocument();
    const eager = [...container.querySelectorAll('img')].filter((i) => i.getAttribute('loading') === 'eager');
    expect(eager).toHaveLength(1);
    expect(eager[0]).toHaveAttribute('fetchpriority', 'high');
    expect(eager[0]?.closest('section')).toBe(screen.getByRole('region', { name: 'Featured promotions' }));
  });

  it('a page without a hero photo gives the first department tile photo the priority instead (still one eager image)', async () => {
    const { container } = await show(await homeFor(10));
    const eager = [...container.querySelectorAll('img')].filter((i) => i.getAttribute('loading') === 'eager');
    expect(eager).toHaveLength(1);
    expect(eager[0]?.closest('[data-testid="department-tiles"]')).not.toBeNull();
  });

  it('10,000 products: at most 12 modules, no product twice, never "0 reviews", no raw copy key, no empty heading', async () => {
    const home = await homeFor(10000, { visitor: 'v', window: 'w' });
    const { container } = await show(home);
    expect(container.querySelectorAll('[data-block]').length).toBeLessThanOrEqual(12);
    // Every card links its product twice (photo and title): the distinct products on the page are exactly the ones the gateway sent, once each.
    const sent = home.blocks.flatMap((b) => (b.rail?.items ?? []).map((p) => p.atin));
    expect(new Set(sent).size).toBe(sent.length);
    expect(new Set(productLinks(container).map((l) => l.replace('/p/', ''))).size).toBe(sent.length);
    expect(container.textContent).not.toMatch(/\b0 reviews\b/i);
    for (const h of screen.getAllByRole('heading')) {
      expect(h.textContent?.trim()).toBeTruthy();
      expect(h.textContent).not.toMatch(/^home\.|^dept\./);
    }
    expect(screen.getAllByRole('heading', { name: /^Top in / }).length).toBeGreaterThan(0);
    for (const h of screen.getAllByRole('heading', { name: /^Top in / })) expect(h.textContent).not.toMatch(/[{}]/);
  });

  it.each([0, 10, 100, 10000])('%i products: every image is a real photo (never an icon, svg, emoji or placeholder) and every link is a path the site serves', async (n) => {
    const { container } = await show(await homeFor(n, { visitor: 'v', window: 'w' }));
    for (const img of container.querySelectorAll('img')) {
      expect(isRealPhoto(img.getAttribute('src'))).toBe(true);
    }
    // Tile, service and hero photos are decorative (the label beside them names the link).
    for (const img of container.querySelectorAll('[data-testid="department-tiles"] img, [data-testid="community-tiles"] img, [data-testid="service-cards"] img')) expect(img.getAttribute('alt')).toBe('');
    expect(container.querySelector('[class*="placeholder"]:not([class*="withImage"])')).toBeNull();
    for (const a of container.querySelectorAll('a[href]')) expect(a.getAttribute('href')).toMatch(/^\/[A-Za-z0-9/?=&%+._-]*$/);
  });

  it.each([10, 100, 10000])('%i products: department and community tiles link only where the navigation says products exist', async (n) => {
    const home = await homeFor(n, { visitor: 'v', window: 'w' });
    const nav = await navFor(n);
    await show(home);
    const known = new Set(nav.all.map((d) => d.href));
    const communities = new Set(nav.communities.map((c) => c.href));
    const tiles = [...document.querySelectorAll<HTMLAnchorElement>('[data-testid="department-tiles"] a')];
    expect(tiles.length).toBeGreaterThan(0);
    for (const a of tiles) expect(known.has(a.getAttribute('href') ?? '')).toBe(true);
    for (const a of document.querySelectorAll<HTMLAnchorElement>('[data-testid="community-tiles"] a')) expect(communities.has(a.getAttribute('href') ?? '')).toBe(true);
  });

  it('a service without a real photo is text only, never an icon or emoji', async () => {
    const home = await homeFor(100);
    const svc = home.blocks.find((b) => b.type === 'service-cards');
    expect(svc?.services?.some((s) => s.imageUrl === null)).toBe(true);
    await show(home);
    const cards = within(screen.getByTestId('service-cards')).getAllByRole('link');
    const without = cards.filter((c) => !c.querySelector('img'));
    expect(without.length).toBeGreaterThan(0);
    for (const c of without) expect(c.textContent).toMatch(/\S/);
  });

  it('one page, one h1', async () => {
    for (const n of [0, 10, 100]) {
      cleanup();
      await show(await homeFor(n));
      expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    }
  });
});

describe('five languages', () => {
  const copyIn = (locale: Locale, key: keyof typeof EN_MESSAGES) => createTranslator(EN_MESSAGES, locale, messagesFor(locale))(key);

  it.each(['fr', 'es', 'ar', 'yo'] as const)('%s: module titles and department labels come from the catalogue, never a raw key or English leftovers', async (locale) => {
    state.locale = locale;
    const home = await homeFor(100, { visitor: 'v', window: 'w' });
    await show(home);
    expect(screen.getByRole('heading', { name: copyIn(locale, 'home.departments.title') })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: copyIn(locale, 'home.communities.title') })).toBeInTheDocument();
    expect(copyIn(locale, 'home.departments.title')).not.toBe(EN_MESSAGES['home.departments.title']);
    const tileLabels = [...document.querySelectorAll('[data-testid="department-tiles"] a')].map((a) => a.textContent ?? '');
    expect(tileLabels).toContain(copyIn(locale, 'dept.groceries-food'));
    expect(document.body.textContent).not.toMatch(/\bhome\.[a-z]+\.[a-z]+|\bdept\.[a-z-]+\b/i);
  });

  it('Arabic is right to left and the new styles use logical properties only', () => {
    expect(dirOf('ar')).toBe('rtl');
    for (const f of ['src/components/home/modules/DynamicHome.module.css', 'src/components/shell/AllMenu.module.css']) {
      const css = readFileSync(resolve(__dirname, '..', f), 'utf8');
      expect(css, f).not.toMatch(/(margin|padding|border)-(left|right)|\b(left|right)\s*:|text-align\s*:\s*(left|right)/);
    }
  });

  it('a missing translation falls back to English, never to the key', async () => {
    state.locale = 'yo';
    const home = await homeFor(100);
    home.blocks = home.blocks.map((b) => (b.type === 'department-tiles' ? { ...b, titleKey: 'home.somethingNew.title', title: 'Fresh picks' } : b));
    await show(home);
    expect(screen.getByRole('heading', { name: 'Fresh picks' })).toBeInTheDocument();
  });
});

describe('visits', () => {
  const picks = async (visit: VisitHeaders) => {
    const home = await homeFor(10000, visit);
    return home.blocks.flatMap((b) => (b.rail?.items ?? []).map((p) => p.atin));
  };

  it('one visit is the same page on refresh; another visit shows different items; crawlers see the deterministic default', async () => {
    const a1 = await picks({ visitor: 'v', window: 'visit-a' });
    const a2 = await picks({ visitor: 'v', window: 'visit-a' });
    const b = await picks({ visitor: 'v', window: 'visit-b' });
    expect(a1).toEqual(a2);
    expect(a1.length).toBeGreaterThan(20);
    expect(b.filter((x) => a1.includes(x)).length).toBeLessThan(a1.length * 0.8);
    const c1 = await picks({ crawler: true });
    const c2 = await picks({ crawler: true, visitor: 'someone' });
    const none = await picks({});
    expect(c1).toEqual(c2);
    expect(c1).toEqual(none);
    expect((await homeFor(10000, { crawler: true })).visit?.deterministic).toBe(true);
  });

  it('the rendered pages differ between visits and are identical on refresh', async () => {
    const html = async (visit: VisitHeaders) => {
      cleanup();
      const { container } = await show(await homeFor(10000, visit));
      return container.innerHTML;
    };
    const a = await html({ visitor: 'v', window: 'one' });
    expect(await html({ visitor: 'v', window: 'one' })).toBe(a);
    expect(await html({ visitor: 'v', window: 'two' })).not.toBe(a);
  });

  it('the home tells the page what it showed so the next visit can avoid repeats', async () => {
    const home = await homeFor(10000, { visitor: 'v', window: 'w' });
    expect(home.visit?.seen.length).toBeGreaterThan(10);
    expect(home.visit?.seen.every((k) => /^[A-Za-z0-9]{1,8}$/.test(k))).toBe(true);
    await waitFor(() => expect(home.visit?.window).toBe('w'));
  });
});

describe('design rules of the new modules', () => {
  const files = ['src/components/home/modules/DynamicHome.module.css', 'src/components/home/modules/DynamicModules.tsx', 'src/components/home/modules/HomePhoto.tsx', 'src/components/shell/AllMenu.module.css', 'src/components/shell/AllMenu.tsx'];
  it('tokens only (no raw hex colour), no shadows or rounded cards, no icon or emoji standing in for a photo', () => {
    for (const f of files) {
      const text = readFileSync(resolve(__dirname, '..', f), 'utf8');
      expect(text, f).not.toMatch(/#[0-9a-fA-F]{3,8}\b(?![\w-])/);
      if (f.endsWith('.css')) expect(text, f).not.toMatch(/box-shadow|border-radius/);
      expect(text, f).not.toMatch(/\p{Extended_Pictographic}/u);
    }
  });
});
