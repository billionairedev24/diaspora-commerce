import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { defaultStoreConfig, type StoreConfig } from '@afrotransact/api-client';
import { buildStorefront } from '@afrotransact/api-client/mocks';
import { resolveTheme } from '@afrotransact/tokens';
import { type StorefrontCommerce, type StorefrontModel,StorefrontView } from '@afrotransact/ui';
import { fireEvent, screen, within } from '@testing-library/react';
import axe from 'axe-core';
import { describe, expect, it, vi } from 'vitest';

import { storefrontJsonLd } from '@/lib/seo/jsonld';
import { storefrontRedirectPath } from '@/lib/store/redirects';

import { renderWithProviders } from './render';

const NOW = new Date('2026-11-14T12:00:00Z');
const commerce = (over: Partial<StorefrontCommerce> = {}): StorefrontCommerce => ({
  homeHref: '/', cartHref: '/cart', accountHref: '/account', cartCount: 0, productHref: (a) => `/p/${a}`, quantityInCart: () => 0, onAdd: vi.fn(), onBuyNow: vi.fn(), ...over
});

function model(mut: (c: StoreConfig) => void = () => undefined, slug = 'laserfeet'): StorefrontModel {
  const config = defaultStoreConfig();
  config.featured = ['AT0000LF01', 'AT0000LF06'];
  config.about = { heading: '', body: 'First paragraph.\n\nSecond paragraph.', photoUrl: null };
  config.policies = { shipping: 'Packed in two days.', returns: 'Thirty days.' };
  mut(config);
  return buildStorefront({ slug, name: 'Laserfeet', logoUrl: null, description: 'Foot care', contactEmail: 'hi@example.com', sinceYear: 2025, config, preview: false }, NOW) as unknown as StorefrontModel;
}

const render = (m: StorefrontModel, c = commerce()) => {
  const theme = resolveTheme({ preset: m.config.theme.preset as never, accent: m.config.theme.accent });
  return renderWithProviders(<StorefrontView model={m} theme={theme} commerce={c} loadProducts={async () => m.products} />);
};

describe('storefront sections come from the seller settings', () => {
  it('has its own header and footer with a link home, never the marketplace chrome', () => {
    render(model());
    const header = screen.getByTestId('storefront-header');
    expect(within(header).getByRole('search')).toBeInTheDocument();
    expect(within(header).getByRole('link', { name: /cart/i })).toHaveAttribute('href', '/cart');
    expect(within(header).getByRole('link', { name: /account/i })).toHaveAttribute('href', '/account');
    const footer = screen.getByTestId('storefront-footer');
    expect(within(footer).getByRole('link', { name: 'Sold on AfroTransact' })).toHaveAttribute('href', '/');
    expect(screen.getAllByRole('banner')).toHaveLength(1);
    expect(screen.queryByText(/Deliver to/i)).toBeNull();
  });

  it('the (store) route group renders no marketplace header or footer (source check)', () => {
    const root = resolve(__dirname, '../src/app/(store)');
    const layout = readFileSync(resolve(root, 'layout.tsx'), 'utf8');
    expect(layout).not.toMatch(/shell\/(Header|Footer|AllMenu|SupportChat|LocationPicker)/);
    const chromeUse = readFileSync(resolve(root, 'b/[slug]/page.tsx'), 'utf8').match(/ShopChrome/g) ?? [];
    // Only the provider branch keeps the marketplace chrome (import + open and close tags).
    expect(chromeUse.length).toBe(4);
    expect(readFileSync(resolve(root, 'b/[slug]/not-found.tsx'), 'utf8')).not.toMatch(/shell\//);
  });

  it('shows featured picks and hides the section when switched off', () => {
    const { unmount } = render(model());
    expect(screen.getByRole('heading', { name: 'Featured' })).toBeInTheDocument();
    unmount();
    render(model((c) => { c.sections = c.sections.map((s) => (s.id === 'featured' ? { ...s, enabled: false } : s)); }));
    expect(screen.queryByRole('heading', { name: 'Featured' })).toBeNull();
  });

  it('orders sections as the seller did', () => {
    render(model((c) => { c.sections = [...c.sections].reverse(); }));
    const heads = screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent);
    expect(heads.indexOf('Shipping and returns')).toBeLessThan(heads.indexOf('Our story'));
    expect(heads.indexOf('All products')).toBeLessThan(heads.indexOf('Featured'));
  });

  it('announcement only when enabled and not empty', () => {
    const { unmount } = render(model((c) => { c.announcement.text = 'Free pickup'; }));
    expect(screen.queryByText('Free pickup')).toBeNull(); // announcement section is off by default
    unmount();
    render(model((c) => { c.announcement.text = 'Free pickup'; c.sections = c.sections.map((s) => (s.id === 'announcement' ? { ...s, enabled: true } : s)); }));
    expect(screen.getByText('Free pickup')).toBeInTheDocument();
  });

  it('about text is plain text with paragraphs (never HTML)', () => {
    render(model((c) => { c.about.body = 'Hello <b>there</b>\n\nSecond'; }));
    expect(screen.getByText('Hello <b>there</b>')).toBeInTheDocument();
    expect(document.querySelector('b')).toBeNull();
  });

  it('only sellable products: no sold-out or photo-less item, no icon or emoji images', () => {
    const m = model();
    const titles = m.products.hits.map((p) => p.title);
    expect(titles).not.toContain('Limited Foot Scrub (sold out)');
    expect(titles).not.toContain('Pumice Stone (no photo yet)');
    expect(m.products.hits.every((p) => p.imageUrl && !/svg|data:|placeholder/i.test(p.imageUrl))).toBe(true);
  });

  it('never "0 reviews": no reviews means no stars and no summary', () => {
    const m = model();
    const unrated = m.products.hits.find((p) => p.reviewCount === 0)!;
    render({ ...m, reviews: null, products: { ...m.products, hits: [unrated] }, featured: [] });
    expect(screen.queryByText(/0 reviews|\(0\)/)).toBeNull();
    expect(screen.queryByText(/What customers say/)).toBeNull();
    expect(screen.queryByRole('img', { name: /out of 5 stars/ })).toBeNull();
  });

  it('pickup and contact only when the data says so', () => {
    const base = model();
    render({ ...base, pickup: null, contactEmail: null });
    expect(screen.queryByText(/Pickup available/)).toBeNull();
    expect(screen.queryByText('hi@example.com')).toBeNull();
  });

  it('add to cart and buy now call the marketplace cart', () => {
    const c = commerce();
    const m = model();
    render(m, c);
    const tile = screen.getAllByTestId('store-product')[0]!;
    fireEvent.click(within(tile).getByRole('button', { name: /^Add to cart/ }));
    fireEvent.click(within(tile).getByRole('button', { name: /^Buy now/ }));
    expect(c.onAdd).toHaveBeenCalledTimes(1);
    expect(c.onBuyNow).toHaveBeenCalledTimes(1);
  });

  it('has no serious axe violations on a light and a dark look', async () => {
    for (const preset of ['warm-market', 'midnight', 'bold'] as const) {
      const { container, unmount } = render(model((c) => { c.theme.preset = preset; }));
      const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
      expect(result.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical'), preset).toEqual([]);
      unmount();
    }
  }, 40_000);
});

describe('addresses and structured data', () => {
  it('301 targets: mixed case, trailing slash and old aliases all go to /b/<lowercase>', () => {
    expect(storefrontRedirectPath('/b/LaserFeet')).toBe('/b/laserfeet');
    expect(storefrontRedirectPath('/b/laserfeet/')).toBe('/b/laserfeet');
    expect(storefrontRedirectPath('/stores/laserfeet')).toBe('/b/laserfeet');
    expect(storefrontRedirectPath('/sellers/LaserFeet/')).toBe('/b/laserfeet');
    expect(storefrontRedirectPath('/b/laserfeet')).toBeNull();
    expect(storefrontRedirectPath('/p/AT123')).toBeNull();
  });

  it('Store JSON-LD states only facts: no rating without reviews, no carriers, no tax', () => {
    const m = model();
    const withRating = JSON.stringify(storefrontJsonLd({ origin: 'https://x.test', store: m as never }));
    expect(withRating).toContain('"@type":"Store"');
    const none = JSON.stringify(storefrontJsonLd({ origin: 'https://x.test', store: { ...m, reviews: null } as never }));
    expect(none).not.toContain('aggregateRating');
    expect(none).not.toMatch(/UPS|FedEx|carrier|tax|AfroTransact (collects|delivers)/i);
  });
});
