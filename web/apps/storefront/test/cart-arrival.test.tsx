/* The cart's package header: a delivery date only from a real window (the seller's handling time, where it ships from, the
   shopper's place), as a RANGE; with no real basis no date at all, only the method. "Package n of m" only when there are
   several packages. Copy never says AfroTransact collects or delivers. Mock BFF. */
import { type Cart, createApiClient } from '@afrotransact/api-client';
import { http, HttpResponse, mockServer } from '@afrotransact/api-client/mocks/node';
import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { CartView } from '@/components/cart/CartView';

import { installMockBff } from './mockBff';
import { renderWithProviders } from './render';

installMockBff();

const api = () => createApiClient({ baseUrl: `${window.location.origin}/api/bff` });

async function addProduct(atin: string) {
  const c = api();
  const p = await c.getProduct(atin);
  return c.addToCart({ offerId: p.buyBox?.offerId ?? '', quantity: 1 });
}

/** Answers the cart with its real mock answer changed for the first package. */
function overridePackage(real: Cart, patch: (p: Cart['packages'][number]) => Cart['packages'][number]) {
  mockServer.use(http.get('*/v1/cart', () => HttpResponse.json({ ...real, packages: real.packages.map((p, i) => (i === 0 ? patch(p) : p)) })));
}

const WINDOW = /^Arrives [A-Z][a-z]{2} \d{1,2}( – (\d{1,2}|[A-Z][a-z]{2} \d{1,2}))?$/;

describe('cart package header', () => {
  it('a seller package shows a date RANGE from real inputs, "Shipped by {seller}", and no "Package 1 of 1"', async () => {
    const cart = await addProduct('AT000000P6');
    const pkg = cart.packages[0];
    expect(pkg?.delivery.basis).toBe('PROMISE');
    expect(pkg?.delivery.earliestDate).toBeTruthy();
    expect(pkg?.delivery.latestDate).toBeTruthy();
    expect(pkg?.delivery.earliestDate).not.toBe(pkg?.delivery.latestDate);
    renderWithProviders(<CartView />);
    const title = await screen.findByTestId('cart-package-title');
    expect(title.textContent).toMatch(WINDOW);
    const meta = screen.getByTestId('cart-package-meta');
    expect(meta.textContent).toMatch(/^Shipped by /);
    expect(meta.textContent).not.toMatch(/Package/);
    expect(screen.queryByTestId('cart-package-date-note')).not.toBeInTheDocument();
  });

  it('with several packages each says "Package n of m"', async () => {
    await addProduct('AT000000P6');
    await addProduct('AT000000P2');
    renderWithProviders(<CartView />);
    const metas = await screen.findAllByTestId('cart-package-meta');
    expect(metas.length).toBeGreaterThan(1);
    metas.forEach((m, i) => expect(m.textContent).toContain(`Package ${i + 1} of ${metas.length}`));
  });

  it('no real basis (seller gave no handling time or ship-from): no date at all, only the method', async () => {
    const real = await addProduct('AT000000P6');
    overridePackage(real, (p) => ({ ...p, delivery: { ...p.delivery, basis: 'NONE', reason: 'NO_ORIGIN', label: '', earliestDate: undefined, latestDate: undefined }, deliveryOptions: p.deliveryOptions.map((o) => ({ ...o, eta: '' })) }));
    renderWithProviders(<CartView />);
    const title = await screen.findByTestId('cart-package-title');
    expect(title).toHaveTextContent('Delivery');
    const text = document.body.textContent ?? '';
    expect(text).not.toMatch(/Arrives|Arriving|Tomorrow|Today/);
    expect(text).not.toMatch(/(Mon|Tue|Wed|Thu|Fri|Sat|Sun)[a-z]*, [A-Z][a-z]{2} \d/);
    expect(screen.queryByTestId('cart-package-date-note')).not.toBeInTheDocument();
  });

  it('the shopper’s place missing: "Delivery date shown at checkout", no date', async () => {
    const real = await addProduct('AT000000P6');
    overridePackage(real, (p) => ({ ...p, delivery: { ...p.delivery, basis: 'NONE', reason: 'NO_PLACE', label: '', earliestDate: undefined, latestDate: undefined } }));
    renderWithProviders(<CartView />);
    expect(await screen.findByTestId('cart-package-date-note')).toHaveTextContent('Delivery date shown at checkout');
    expect(screen.getByTestId('cart-package-title')).toHaveTextContent('Delivery');
    expect(screen.getByTestId('cart-package-title').textContent).not.toMatch(/Arrives/);
  });

  it('an older server that sends no basis never gets a date either', async () => {
    const real = await addProduct('AT000000P6');
    overridePackage(real, (p) => ({ ...p, delivery: { ...p.delivery, basis: undefined, label: 'Tomorrow, Oct 11' } }));
    renderWithProviders(<CartView />);
    const title = await screen.findByTestId('cart-package-title');
    expect(title).toHaveTextContent('Delivery');
    expect(document.body.textContent).not.toContain('Tomorrow, Oct 11');
  });

  it('never says AfroTransact collects or delivers', async () => {
    await addProduct('AT000000P6');
    await addProduct('AT000000P2');
    renderWithProviders(<CartView />);
    await screen.findAllByTestId('cart-package-title');
    const text = document.body.textContent ?? '';
    expect(text).not.toMatch(/AfroTransact (collects|delivers|Delivery)|Delivery by AfroTransact|Shipped by AfroTransact/);
  });
});
