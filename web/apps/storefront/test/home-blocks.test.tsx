import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { type HomeBlock, type ProductSummary } from '@afrotransact/api-client';
import { home, resetDb } from '@afrotransact/api-client/mocks';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { dealPercent, Occasions, PromoBand } from '@/components/home/blocks/OrganicBlocks';
import { AdRow, Advertorial, BrandBanner, DisplayBanner, SponsoredProducts } from '@/components/home/blocks/PaidBlocks';
import { HomeBlocks } from '@/components/home/HomeBlocks';
import { RailSection, Tiles } from '@/components/home/HomeSections';
import { RailCarousel } from '@/components/home/RailCarousel';
import { capReached, sessionViews } from '@/lib/home/adTracking';

import { installMockBff, sent } from './mockBff';
import { renderWithProviders } from './render';

vi.mock('@/i18n/server', async () => {
  const { tEn: t } = await import('@/i18n/t');
  return { getLocale: async () => 'en', getT: async () => t };
});

installMockBff();

const hou = { zip: '77042', city: 'Houston', state: 'TX', label: 'Houston 77042', serviceable: true };
const now = new Date('2026-11-04T12:00:00Z');

beforeEach(() => {
  resetDb();
  window.sessionStorage.clear();
  sent.length = 0;
});

const block = (type: string, over: Partial<HomeBlock> = {}): HomeBlock => ({ id: `plc_${type.replace(/-/g, '_')}`, type, sponsored: true, advertiser: 'Acme Foods', ...over });
const composed = () => home(hou, now).blocks;
const byType = (t: string) => composed().find((b) => b.type === t)!;
const adEvents = () => sent.filter((r) => r.method === 'POST' && r.url.endsWith('/v1/home/ad-events'));
const bodies = async () => Promise.all(adEvents().map((r) => r.clone().json() as Promise<{ placementId: string; event: string }>));

describe('paid modules are always disclosed', () => {
  it('brand banner: Sponsored label with the advertiser, headline, button and product strip', () => {
    const b = byType('brand-banner');
    renderWithProviders(<BrandBanner block={b} />);
    const region = screen.getByRole('region', { name: `Sponsored · ${b.advertiser}` });
    expect(within(region).getByText('Sponsored')).toBeInTheDocument();
    expect(within(region).getByRole('heading', { name: b.title })).toBeInTheDocument();
    expect(within(region).getByRole('link', { name: b.cta })).toHaveAttribute('href', b.href);
    expect(within(region).getAllByRole('link').length).toBe(1 + (b.products?.length ?? 0));
  });

  it('ad row: every ad carries the Ad tag and names its advertiser; fewer than two ads render nothing', () => {
    const b = byType('ad-row');
    const { unmount } = renderWithProviders(<AdRow block={b} />);
    const links = within(screen.getByTestId('ad-row')).getAllByRole('link');
    expect(links).toHaveLength(b.ads?.length ?? 0);
    for (const l of links) {
      expect(l).toHaveAccessibleName(/^Ad · .+: /);
      expect(l).toHaveAttribute('rel', expect.stringContaining('sponsored'));
    }
    expect(screen.getAllByText('Ad')).toHaveLength(links.length);
    unmount();
    renderWithProviders(<AdRow block={{ ...b, ads: b.ads?.slice(0, 1) }} />);
    expect(screen.queryByTestId('ad-row')).not.toBeInTheDocument();
  });

  it('advertorial: "From our partners" heading, Sponsored label with advertiser, two-line text and link', () => {
    const b = byType('advertorial');
    renderWithProviders(<Advertorial block={b} />);
    const sec = screen.getByTestId('advertorial');
    expect(within(sec).getByText('From our partners')).toBeInTheDocument();
    expect(within(sec).getByText('Sponsored')).toBeInTheDocument();
    expect(within(sec).getByText(b.advertiser!)).toBeInTheDocument();
    expect(within(sec).getByRole('link', { name: b.cta })).toHaveAttribute('rel', 'sponsored');
  });

  it('display banner (older slot) keeps its label', () => {
    renderWithProviders(<DisplayBanner block={block('banner', { title: 'Kente', sub: 'Hand-woven', cta: 'Shop', href: '/s?q=kente', advertiser: 'Bonwire' })} />);
    expect(screen.getByRole('region', { name: 'Sponsored · Bonwire' })).toBeInTheDocument();
  });
});

describe('ad counting', () => {
  it('counts an impression once when the module is on screen, and a click on its link', async () => {
    const seen: IntersectionObserverCallback[] = [];
    vi.stubGlobal('IntersectionObserver', class { constructor(cb: IntersectionObserverCallback) { seen.push(cb); } observe() {} disconnect() {} unobserve() {} });
    const b = byType('advertorial');
    renderWithProviders(<Advertorial block={b} />);
    expect(adEvents()).toHaveLength(0);
    act(() => seen[0]?.([{ isIntersecting: true, intersectionRatio: 0.8 } as IntersectionObserverEntry], {} as IntersectionObserver));
    fireEvent.click(screen.getByRole('link', { name: b.cta }));
    await waitFor(() => expect(adEvents()).toHaveLength(2));
    expect(await bodies()).toEqual([
      { placementId: b.id, event: 'impression' },
      { placementId: b.id, event: 'click' }
    ]);
    expect(sessionViews(b.id)).toBe(1);
    vi.unstubAllGlobals();
  });

  it('a module the session has already seen maxPerSession times is not rendered, and not counted again', async () => {
    window.sessionStorage.setItem('at.adViews', JSON.stringify({ plc_capped: 2 }));
    expect(capReached('plc_capped', 2)).toBe(true);
    expect(capReached('plc_capped', 3)).toBe(false);
    expect(capReached('plc_capped', undefined)).toBe(false);
    renderWithProviders(<Advertorial block={{ ...byType('advertorial'), id: 'plc_capped', maxPerSession: 2 }} />);
    await waitFor(() => expect(screen.queryByTestId('advertorial')).not.toBeInTheDocument());
    expect(adEvents()).toHaveLength(0);
  });

  it('survives blocked storage', () => {
    const spy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    expect(sessionViews('plc_x')).toBe(0);
    spy.mockRestore();
  });
});

describe('sponsored products rail', () => {
  it('labels the rail and each card, and reports a click with the search id, position and ad token', async () => {
    const b = byType('sponsored-products');
    renderWithProviders(<SponsoredProducts block={b} />);
    const sec = screen.getByTestId('sponsored-products');
    expect(within(sec).getAllByText('Sponsored').length).toBeGreaterThan(0);
    const first = b.rail!.items[0]!;
    fireEvent.click(within(sec).getAllByRole('link', { name: new RegExp(first.title.slice(0, 12)) })[0]!);
    await waitFor(() => expect(sent.some((r) => r.url.endsWith('/v1/search/clicks'))).toBe(true));
    const click = (await sent.find((r) => r.url.endsWith('/v1/search/clicks'))!.clone().json()) as { searchId: string; atin: string; position: number; adToken?: string };
    expect(click).toMatchObject({ searchId: b.searchId, atin: first.atin, position: 1, adToken: first.sponsor?.adToken });
  });
});

describe('organic modules', () => {
  it('promo bands need a title and link; unknown and empty block types render nothing', async () => {
    const { container, unmount } = render(<PromoBand block={block('membership-promo', { sponsored: false, title: 'Plus', sub: 'Free delivery', cta: 'See membership', href: '/membership' })} />);
    expect(screen.getByRole('link', { name: 'See membership' })).toHaveAttribute('href', '/membership');
    unmount();
    const empty = render(<PromoBand block={block('membership-promo', { sponsored: false })} />);
    expect(empty.container).toBeEmptyDOMElement();
    expect(container).toBeEmptyDOMElement();
    const out = render(await HomeBlocks({ blocks: [block('from-the-future'), block('rail', { sponsored: false })] }));
    expect(out.container.querySelectorAll('[data-block]')).toHaveLength(0);
  });

  it('shop by occasion shows linked cells and needs three', () => {
    const b = byType('occasions');
    const { unmount } = render(<Occasions block={b} />);
    expect(within(screen.getByRole('region', { name: b.title })).getAllByRole('link')).toHaveLength(b.cells!.length);
    unmount();
    expect(render(<Occasions block={{ ...b, cells: b.cells!.slice(0, 2) }} />).container).toBeEmptyDOMElement();
  });

  it('deal percent comes from the compare-at price', () => {
    expect(dealPercent({ priceMinor: 800, listPriceMinor: 1000 })).toBe(20);
    expect(dealPercent({ priceMinor: 1000, listPriceMinor: null })).toBe(0);
    expect(dealPercent({ priceMinor: 1200, listPriceMinor: 1000 })).toBe(0);
  });

  it('every block the mock BFF composes renders without throwing (async rails included)', async () => {
    const ui = await HomeBlocks({ blocks: composed().filter((b) => !['preorders', 'recently-viewed'].includes(b.type)) });
    expect(ui).toBeTruthy();
    for (const b of composed().filter((x) => x.rail && ['rail', 'deals', 'community-spotlight'].includes(x.type))) {
      renderWithProviders(await RailSection({ rail: b.rail! }));
    }
    expect(screen.getAllByRole('link', { name: 'See all' }).length).toBeGreaterThan(0);
  });
});

describe('rails are carousels', () => {
  const items = Array.from({ length: 12 }, (_, i) => <li key={i}>{`card ${i}`}</li>);

  it('labels its controls, shows no arrows when the row fits, and scrolls with arrows and keys when it overflows', async () => {
    const scrollBy = vi.fn();
    Object.defineProperty(HTMLElement.prototype, 'scrollBy', { configurable: true, value: scrollBy });
    const dims = { scrollWidth: 2000, clientWidth: 800 };
    vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockImplementation(() => dims.scrollWidth);
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(() => dims.clientWidth);
    renderWithProviders(<RailCarousel label="Pantry staples">{items}</RailCarousel>);
    const track = screen.getByRole('group', { name: 'Pantry staples' });
    expect(track).toHaveAttribute('aria-roledescription', 'carousel');
    expect(track).toHaveAttribute('tabindex', '0');
    const next = await screen.findByRole('button', { name: 'Scroll forward through Pantry staples' });
    expect(screen.getByRole('button', { name: 'Scroll back through Pantry staples' })).toBeDisabled(); // at the start
    fireEvent.click(next);
    expect(scrollBy).toHaveBeenLastCalledWith(expect.objectContaining({ left: 680 }));
    fireEvent.keyDown(track, { key: 'ArrowLeft' });
    expect(scrollBy).toHaveBeenLastCalledWith(expect.objectContaining({ left: -680 }));
    vi.restoreAllMocks();
  });

  it('a row that fits shows no arrows', () => {
    vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockReturnValue(500);
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(800);
    renderWithProviders(<RailCarousel label="Short">{items.slice(0, 3)}</RailCarousel>);
    expect(screen.queryByRole('button', { name: /Scroll/ })).not.toBeInTheDocument();
    vi.restoreAllMocks();
  });
});

describe('layout leaves no dead space', () => {
  const css = (f: string) => readFileSync(resolve(__dirname, `../src/components/home/${f}`), 'utf8');

  it('tiles follow the tile count instead of an auto-fill grid that leaves empty tracks', async () => {
    const tiles = home(hou, now).tiles;
    render(await Tiles({ tiles }));
    expect(screen.getByRole('region', { name: 'Featured' })).toHaveAttribute('data-count', String(tiles.length));
    const home_css = css('Home.module.css');
    const rule = home_css.split('\n').find((l) => l.startsWith('.tiles {')) ?? '';
    expect(rule).not.toMatch(/auto-fill|auto-fit/);
    for (const n of [1, 2, 3, 5, 6]) expect(home_css).toContain(`.tiles[data-count='${n}']`);
    expect(home_css).toContain('repeat(4, minmax(0, 1fr))'); // four across from 1100 px
    expect(home_css).toContain('min-width: 1400px'); // five or six across on very wide screens
  });

  it('rails fill the container: fixed-width cards in a scrolling row, never a fixed-count grid', () => {
    const rails = css('Rails.module.css');
    expect(rails).toMatch(/grid-auto-flow: column/);
    expect(rails).toMatch(/overflow-x: auto/);
    expect(rails).toMatch(/scroll-snap-type: x/);
    expect(css('Home.module.css')).not.toMatch(/railGrid/);
  });

  it('every block shares the one centred page container (no per-block max width on the section rules)', () => {
    const sections = ['.tiles', '.rail', '.banner', '.band', '.brand', '.adRow', '.promo', '.carousel', '.spotlight', '.partner'];
    for (const f of ['Home.module.css', 'Blocks.module.css', 'Rails.module.css']) {
      for (const line of css(f).split('\n')) {
        const sel = line.split('{')[0]?.trim() ?? '';
        if (sections.includes(sel)) expect(line, `${f} ${sel}`).not.toMatch(/max-width/);
      }
    }
  });
});

describe('every home block type is exercised by the mock composition', () => {
  it('paid types are sponsored, organic types are not', () => {
    const paid: Record<string, boolean> = { banner: true, 'brand-banner': true, 'sponsored-products': true, 'ad-row': true, advertorial: true };
    for (const b of composed()) expect(b.sponsored, b.type).toBe(paid[b.type] ?? false);
  });

  it('only eligible products are shown (in stock)', () => {
    const products: ProductSummary[] = composed().flatMap((b) => [...(b.rail?.items ?? []), ...(b.products ?? [])]);
    expect(products.every((p) => p.inStock && /^https?:\/\//.test(p.imageUrl ?? ''))).toBe(true);
  });
});
