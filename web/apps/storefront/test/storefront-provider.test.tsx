import { defaultStoreConfig, type StoreConfig } from '@afrotransact/api-client';
import { buildStorefront } from '@afrotransact/api-client/mocks';
import { resolveTheme } from '@afrotransact/tokens';
import { type StorefrontCommerce, type StorefrontModel, type StorefrontQueryState,StorefrontView } from '@afrotransact/ui';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import axe from 'axe-core';
import { describe, expect, it, vi } from 'vitest';

import { storefrontJsonLd } from '@/lib/seo/jsonld';
import { isDefaultQuery, storefrontQueryOf } from '@/lib/store/query';

import { renderWithProviders } from './render';

const NOW = new Date('2026-11-14T12:00:00Z');
const commerce = (): StorefrontCommerce => ({ homeHref: '/', cartHref: '/cart', accountHref: '/account', cartCount: 0, productHref: (a) => `/p/${a}`, quantityInCart: () => 0, onAdd: vi.fn(), onBuyNow: vi.fn() });

function provider(mut: (c: StoreConfig) => void = () => undefined, key = 'org_braids_ama'): StorefrontModel {
  const config = defaultStoreConfig('PROVIDER');
  config.about = { heading: '', body: 'We braid.', photoUrl: null };
  mut(config);
  return buildStorefront({ kind: 'PROVIDER', providerKey: key, slug: 'braids-by-ama', name: 'Braids by Ama', logoUrl: null, description: 'Braids', contactEmail: null, sinceYear: 2024, config, preview: false }, NOW) as unknown as StorefrontModel;
}

const seller = (): StorefrontModel =>
  buildStorefront({ slug: 'laserfeet', name: 'Laserfeet', logoUrl: null, description: 'Foot care', contactEmail: null, sinceYear: 2025, config: defaultStoreConfig(), preview: false }, NOW) as unknown as StorefrontModel;

const render = (m: StorefrontModel, extra: Partial<React.ComponentProps<typeof StorefrontView>> = {}) => {
  const theme = resolveTheme({ preset: m.config.theme.preset as never, accent: m.config.theme.accent });
  return renderWithProviders(<StorefrontView model={m} theme={theme} commerce={commerce()} loadProducts={async () => m.products} {...extra} />);
};

describe('a provider page is a bookable mini-store', () => {
  it('lists real services with durations and prices, and has no cart, search or products', () => {
    render(provider());
    const header = screen.getByTestId('storefront-header');
    expect(within(header).queryByRole('search')).toBeNull();
    expect(within(header).queryByRole('link', { name: /cart/i })).toBeNull();
    expect(screen.queryByRole('heading', { name: 'All products' })).toBeNull();
    expect(screen.getByRole('heading', { name: 'Services' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Knotless braids, mid-back' })).toBeInTheDocument();
    expect(screen.getAllByText(/\d+ min/).length).toBeGreaterThan(0);
    expect(screen.getByText('Verified provider')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Book an appointment' })).toHaveAttribute('href', '#sf-book');
  });

  it('shows the weekly schedule with closed days and the service area, never invented', () => {
    render(provider());
    const hours = screen.getByRole('heading', { name: /Opening hours/ }).parentElement!;
    expect(within(hours).getByText('Sunday').nextSibling).toHaveTextContent('Closed');
    expect(within(hours).getByText('Monday').nextSibling).toHaveTextContent(/9:00.*6:00/);
    expect(screen.getByText(/Based in Houston, TX/)).toBeInTheDocument();
    expect(screen.getByText(/Travels up to 20 miles/)).toBeInTheDocument();
  });

  it('no schedule at all when the provider has set no hours, and nothing for an empty area', () => {
    const m = provider();
    render({ ...m, provider: { ...m.provider!, hours: [], area: null } });
    expect(screen.queryByRole('heading', { name: /Opening hours/ })).toBeNull();
    expect(screen.queryByText(/Closed/)).toBeNull();
  });

  it('shows a rating only from real reviews: none means no summary and no zero', () => {
    const { unmount } = render(provider());
    expect(screen.getByRole('heading', { name: 'What customers say' })).toBeInTheDocument();
    unmount();
    const m = provider((c) => c.sections.forEach((s) => (s.enabled = true)), 'org_zuri_studio');
    render({ ...m, reviews: null });
    expect(screen.queryByText(/What customers say/)).toBeNull();
    expect(screen.queryByText(/0 reviews|\(0\)/)).toBeNull();
  });

  it('offers only in-person or at-home services: no virtual wording', () => {
    render(provider());
    expect(document.body.textContent).not.toMatch(/virtual|online|video|remote/i);
  });

  it('hands the chosen service to the booker; the booker renders only in live pages', () => {
    const booker = vi.fn((_ctx: { selectedId: string | null }) => <div data-testid="booker">booking panel</div>);
    const { unmount } = render(provider(), { renderBooker: booker });
    fireEvent.click(screen.getAllByRole('button', { name: /^Book/ })[1]!);
    expect(screen.getByTestId('booker')).toBeInTheDocument();
    const last = booker.mock.calls.at(-1)![0];
    expect(last.selectedId).toBe(provider().provider!.services[1]!.id);
    unmount();
    render(provider(), { renderBooker: booker, preview: true });
    expect(screen.queryByTestId('booker')).toBeNull();
    expect(screen.getByText(/Preview: customers choose a service/)).toBeInTheDocument();
  });

  it('respects the sections the provider switched off and their order', () => {
    render(provider((c) => { c.sections = c.sections.map((s) => (s.id === 'schedule' || s.id === 'team' ? { ...s, enabled: false } : s)); }));
    expect(screen.queryByRole('heading', { name: /Opening hours/ })).toBeNull();
    expect(screen.queryByRole('heading', { name: 'Team' })).toBeNull();
  });

  it('JSON-LD is a ProfessionalService with no invented hours or rating', () => {
    const m = provider();
    const ld = storefrontJsonLd({ origin: 'https://www.example.test', store: { ...m, reviews: null } as never });
    expect(ld['@type']).toBe('ProfessionalService');
    expect(JSON.stringify(ld)).not.toMatch(/aggregateRating|openingHours/);
  });

  it('has no serious axe violations', async () => {
    const { container } = render(provider((c) => { c.theme.preset = 'heritage'; }), { renderBooker: () => <div>booking panel</div> });
    const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
    expect(result.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical')).toEqual([]);
  }, 40_000);
});

describe('the On sale filter', () => {
  it('is a checkbox that asks for sale items only, and the in-stock line is gone', async () => {
    const m = seller();
    const load = vi.fn(async (_q: StorefrontQueryState) => ({ ...m.products, hits: m.products.hits.filter((p) => p.listPriceMinor !== null && p.listPriceMinor > p.priceMinor) }));
    render(m, { loadProducts: load });
    expect(screen.queryByText(/Showing products in stock now/)).toBeNull();
    fireEvent.click(screen.getByRole('checkbox', { name: 'On sale' }));
    await waitFor(() => expect(load).toHaveBeenCalledWith(expect.objectContaining({ onSale: true }), 1));
    await waitFor(() => expect(screen.getAllByTestId('store-product').length).toBeGreaterThan(0));
    for (const tile of screen.getAllByTestId('store-product')) expect(within(tile).getByText(/% off/)).toBeInTheDocument();
  });

  it('says so plainly when nothing is on sale, and a clear button resets it', async () => {
    const m = seller();
    render(m, { loadProducts: async () => ({ ...m.products, hits: [], total: 0 }) });
    fireEvent.click(screen.getByRole('checkbox', { name: 'On sale' }));
    expect(await screen.findByText('Nothing is on sale right now.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }));
    expect((screen.getByRole('checkbox', { name: 'On sale' }) as HTMLInputElement).checked).toBe(false);
  });

  it('is part of the shareable address', () => {
    expect(storefrontQueryOf({ onSale: 'true' }).onSale).toBe(true);
    expect(isDefaultQuery(storefrontQueryOf({ onSale: 'true' }))).toBe(false);
    expect(isDefaultQuery(storefrontQueryOf({}))).toBe(true);
  });
});
