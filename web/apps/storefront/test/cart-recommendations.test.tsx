/* The cart rail says only what is true. Empty cart: nothing (or the browser's own recently viewed items). With items:
   real related products (same department, in stock, real photo, not in the cart, never sponsored), under a heading that
   claims nothing about other customers. We have no co-purchase data, so "Customers also bought" must never appear. */
import { type ProductSummary, SearchQuerySchema } from '@afrotransact/api-client';
import { http, HttpResponse, mockServer } from '@afrotransact/api-client/mocks/node';
import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { CartView } from '@/components/cart/CartView';
import { pickRelated } from '@/lib/cart/related';
import { recommendable } from '@/lib/seo/eligibility';

import { installMockBff, signInDemo } from './mockBff';
import { renderWithProviders } from './render';

installMockBff();

const SOCIAL_PROOF = /customers? (who|also)|also bought|bought together|frequently bought|also viewed|best ?sellers?|trending/i;

async function cartWithOneItem() {
  const api = await signInDemo();
  const product = await api.getProduct('AT000000P6');
  await api.addToCart({ offerId: product.buyBox?.offerId ?? '', quantity: 1 });
  const line = (await api.getCart()).packages[0]?.lines[0];
  if (!line) throw new Error('the mock cart has a line');
  const page = await api.search(SearchQuerySchema.parse({ department: line.department, size: 24 }));
  return { api, line, page };
}

const hit = (page: { hits: ProductSummary[] }, i: number, patch: Partial<ProductSummary> = {}): ProductSummary => {
  const h = page.hits[i];
  if (!h) throw new Error(`mock search has hit ${i}`);
  return { ...h, ...patch };
};

describe('cart rail', () => {
  it('an empty cart renders no rail and no social-proof copy', async () => {
    await signInDemo();
    renderWithProviders(<CartView />);
    await screen.findByText('Your AfroTransact Cart is empty');
    expect(screen.queryByTestId('cart-recs')).not.toBeInTheDocument();
    expect(screen.queryByTestId('cart-recently-viewed')).not.toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(SOCIAL_PROOF);
  });

  it('an empty cart shows only the visitor\'s own recently viewed products, truthfully headed', async () => {
    await signInDemo();
    window.localStorage.setItem('at.recentlyViewed', JSON.stringify(['AT000000P6']));
    try {
      renderWithProviders(<CartView />);
      const rail = await screen.findByTestId('cart-recently-viewed');
      expect(rail).toHaveTextContent('Your recently viewed items');
      expect(await screen.findAllByTestId('cart-rec')).toHaveLength(1);
      expect(screen.queryByTestId('cart-recs')).not.toBeInTheDocument();
      expect(document.body.textContent).not.toMatch(SOCIAL_PROOF);
    } finally {
      window.localStorage.removeItem('at.recentlyViewed');
    }
  });

  it('a cart with items shows only real, in-stock, photographed, non-sponsored products that are not in the cart', async () => {
    const { line, page } = await cartWithOneItem();
    const good = [hit(page, 1), hit(page, 2)];
    const hits: ProductSummary[] = [
      hit(page, 0, { atin: line.atin }), // already in the cart
      hit(page, 3, { imageUrl: null, title: 'No photo' }),
      hit(page, 4, { imageUrl: 'https://cdn.example/icons/box.svg', title: 'Icon only' }),
      hit(page, 5, { inStock: false, stockStatus: 'OUT_OF_STOCK', availableQty: 0, title: 'Sold out' }),
      hit(page, 6, { sponsor: { advertiser: 'Acme' }, title: 'Paid slot' }),
      ...good.map((g, i) => ({ ...g, imageUrl: `https://cdn.example/p/${i}.jpg`, sellerName: 'Other Seller' }))
    ];
    mockServer.use(http.get('*/v1/search', () => HttpResponse.json({ ...page, hits })));
    renderWithProviders(<CartView />);
    const rail = await screen.findByTestId('cart-recs');
    const rows = await screen.findAllByTestId('cart-rec');
    expect(rows).toHaveLength(2);
    for (const bad of ['No photo', 'Icon only', 'Sold out', 'Paid slot']) expect(rail).not.toHaveTextContent(bad);
    expect(rail).not.toHaveTextContent(line.title);
    expect(screen.getByText(`More in ${line.department}`)).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(SOCIAL_PROOF);
    expect(rail.textContent).not.toMatch(/\b0 (reviews|ratings)\b/i);
  });

  it('shows nothing when nothing real is left to recommend', async () => {
    const { page } = await cartWithOneItem();
    mockServer.use(http.get('*/v1/search', () => HttpResponse.json({ ...page, hits: [hit(page, 1, { imageUrl: null })] })));
    renderWithProviders(<CartView />);
    await screen.findByTestId('cart-line');
    await waitFor(() => expect(screen.queryByTestId('cart-recs')).not.toBeInTheDocument());
  });
});

describe('pickRelated', () => {
  const base = { atin: 'A', sellerName: 'S1', imageUrl: 'https://cdn.example/a.jpg', inStock: true, stockStatus: 'IN_STOCK', availableQty: 5 } as unknown as ProductSummary;
  const p = (atin: string, patch: Partial<ProductSummary> = {}): ProductSummary => ({ ...base, atin, ...patch });
  const none = new Set<string>();

  it('drops cart items, photo-less, out-of-stock and sponsored products', () => {
    const out = pickRelated({
      pools: [[p('A1'), p('A2', { imageUrl: null }), p('A3', { inStock: false }), p('A4', { sponsor: { advertiser: 'X' } } as Partial<ProductSummary>), p('A5')]],
      exclude: new Set(['A1']),
      cartSellers: none
    });
    expect(out.map((x) => x.atin)).toEqual(['A5']);
  });

  it('interleaves departments and prefers sellers not yet in the cart', () => {
    const out = pickRelated({ pools: [[p('A1', { sellerName: 'Mine' }), p('A2', { sellerName: 'Other' })], [p('B1', { sellerName: 'Third' })]], exclude: none, cartSellers: new Set(['Mine']) });
    expect(out.map((x) => x.atin)).toEqual(['B1', 'A2', 'A1']);
  });

  it('shows nothing when everything left is one seller who is already in the cart', () => {
    expect(pickRelated({ pools: [[p('A1', { sellerName: 'Mine' }), p('A2', { sellerName: 'Mine' })]], exclude: none, cartSellers: new Set(['Mine']) })).toEqual([]);
  });

  it('recommendable follows the home eligibility rule', () => {
    expect(recommendable({ atin: 'x', inStock: true, imageUrl: '/media/a.jpg', availableQty: 1, stockStatus: 'IN_STOCK' })).toBe(true);
    expect(recommendable({ atin: 'x', inStock: true, imageUrl: '/images/placeholder.png', availableQty: 1, stockStatus: 'IN_STOCK' })).toBe(false);
    expect(recommendable({ atin: 'x', inStock: true, imageUrl: '/media/a.jpg', availableQty: 1, stockStatus: 'IN_STOCK', sponsor: {} })).toBe(false);
  });
});
