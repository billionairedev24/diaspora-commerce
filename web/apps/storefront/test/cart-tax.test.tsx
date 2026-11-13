/* The cart's tax estimate before an address is chosen. A signed-in shopper's saved address (or, failing that, the delivery
   place they chose with a ZIP code) is used without typing; a guest or a new shopper types a ZIP code. When there is no
   estimate the row says why (items we can't sell yet, an incomplete address, "try again") instead of hiding it behind the ZIP
   box. Never an amount without a place, never $0.00, never "calculated at checkout". */
import { type CartTaxEstimate, createApiClient } from '@afrotransact/api-client';
import { http, HttpResponse, mockServer } from '@afrotransact/api-client/mocks/node';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { CartView } from '@/components/cart/CartView';
import { tEn } from '@/i18n/t';

import { installMockBff, sent, signInDemo } from './mockBff';
import { renderWithProviders } from './render';

installMockBff();

/** A signed-in shopper (the demo account has a saved default address in Houston) with one item in the cart. */
async function signedInWithOneItem() {
  const api = await signInDemo();
  const seller = await api.getProduct('AT000000P6');
  await api.addToCart({ offerId: seller.buyBox?.offerId ?? '', quantity: 1 });
  const line = (await api.getCart()).packages[0]?.lines[0];
  if (!line) throw new Error('the mock cart has a line');
  return { api, line };
}

/** Answers the cart's estimate with the mock's real answer changed as given. */
async function overrideEstimate(patch: Partial<CartTaxEstimate>) {
  const { api, line } = await signedInWithOneItem();
  const real = await api.getTaxEstimate('');
  mockServer.use(http.get('*/v1/checkout/tax-estimate', () => HttpResponse.json({ ...real, ...patch })));
  return { real, line };
}

describe('cart tax estimate', () => {
  it('a signed-in shopper with a saved default address sees the estimate for it without typing anything', async () => {
    await signedInWithOneItem();
    renderWithProviders(<CartView />);
    const box = await screen.findByTestId('cart-tax-estimate');
    const result = await within(box).findByTestId('tax-zip-result');
    expect(result).toHaveTextContent('Estimated for Houston, TX');
    expect(within(box).getByTestId('tax-zip-amount').textContent).toMatch(/^\$\d+\.\d{2}$/);
    expect(box.textContent).not.toMatch(/when you place your order|at checkout/i);
  });

  it('never shows the ZIP box to that shopper; another place stays possible only as the secondary link', async () => {
    await signedInWithOneItem();
    renderWithProviders(<CartView />);
    const box = await screen.findByTestId('cart-tax-estimate');
    await within(box).findByTestId('tax-zip-result');
    expect(within(box).queryByTestId('tax-zip-form')).not.toBeInTheDocument();
    expect(within(box).queryByText('Enter your ZIP code to see tax')).not.toBeInTheDocument();
    expect(within(box).queryByRole('button', { name: 'See tax' })).not.toBeInTheDocument();
    // asked with no ZIP code: the BFF uses the saved address
    const asked = sent.filter((r) => r.url.includes('/v1/checkout/tax-estimate'));
    expect(asked.length).toBeGreaterThan(0);
    expect(asked.every((r) => !/zip=\d/.test(r.url))).toBe(true);
    expect(within(box).getByRole('button', { name: 'Estimate for another ZIP code' })).toBeInTheDocument();
  });

  it('a guest is asked for a ZIP code, shown no amount until one is typed, and gets the estimate for that place', async () => {
    const api = createApiClient({ baseUrl: `${window.location.origin}/api/bff` });
    const seller = await api.getProduct('AT000000P6');
    await api.addToCart({ offerId: seller.buyBox?.offerId ?? '', quantity: 1 });
    renderWithProviders(<CartView />);
    const box = await screen.findByTestId('cart-tax-estimate');
    const form = await within(box).findByTestId('tax-zip-form');
    expect(within(form).getByText('Enter your ZIP code to see tax')).toBeInTheDocument();
    expect(box.textContent).not.toMatch(/\$0\.00/);
    const user = userEvent.setup();
    await user.type(within(form).getByLabelText('Enter your ZIP code to see tax'), '77002');
    await user.click(within(form).getByRole('button', { name: 'See tax' }));
    await waitFor(() => expect(within(box).getByTestId('tax-zip-result')).toHaveTextContent('Estimated for Houston, TX'));
    expect(sent.some((r) => r.url.includes('/v1/checkout/tax-estimate') && r.url.includes('zip=77002'))).toBe(true);
    // changing the ZIP code goes back to the input
    await user.click(within(box).getByRole('button', { name: 'Change ZIP code' }));
    expect(within(box).getByTestId('tax-zip-form')).toBeInTheDocument();
  });

  it('a guest who chose a delivery place with a ZIP code is not asked for one either', async () => {
    const api = createApiClient({ baseUrl: `${window.location.origin}/api/bff` });
    await api.setDeliveryLocation({ zip: '77002' });
    const seller = await api.getProduct('AT000000P6');
    await api.addToCart({ offerId: seller.buyBox?.offerId ?? '', quantity: 1 });
    renderWithProviders(<CartView />);
    const box = await screen.findByTestId('cart-tax-estimate');
    expect(await within(box).findByTestId('tax-zip-result')).toHaveTextContent('Estimated for Houston, TX');
    expect(within(box).queryByTestId('tax-zip-form')).not.toBeInTheDocument();
    expect(sent.some((r) => r.url.includes('/v1/checkout/tax-estimate') && r.url.includes('zip=77002'))).toBe(true);
  });

  it('a signed-in shopper with no saved address at all is asked for a ZIP code, like a guest', async () => {
    await overrideEstimate({ taxEstimateStatus: 'NEEDS_ADDRESS', taxUnavailableReason: 'NEEDS_ZIP', taxMinor: 0, taxJurisdictionLabel: '', taxPlaceSource: undefined });
    renderWithProviders(<CartView />);
    const box = await screen.findByTestId('cart-tax-estimate');
    expect(await within(box).findByTestId('tax-zip-form')).toBeInTheDocument();
  });

  describe('why there is no estimate is said, not hidden behind the ZIP box', () => {
    it('items we can’t sell yet are named with a Remove action; Remove takes the line out of the cart', async () => {
      const { line } = await signedInWithOneItem();
      // the blocked line is the cart's own line, named by its title (never an id)
      mockServer.use(
        http.get('*/v1/checkout/tax-estimate', () =>
          HttpResponse.json({
            taxEstimateStatus: 'UNAVAILABLE',
            taxUnavailableReason: 'BLOCKED_ITEMS',
            taxMinor: 0,
            taxRateLabel: 'x',
            taxJurisdictionLabel: '',
            taxLines: [],
            subtotalMinor: 0,
            shippingMinor: 0,
            totalMinor: 0,
            currency: 'USD',
            blockedLines: [{ lineId: line.lineId, offerId: line.offerId, atin: line.atin, title: line.title, packageId: 'p', message: 'x', action: 'REMOVE' }]
          })
        )
      );
      renderWithProviders(<CartView />);
      const box = await screen.findByTestId('cart-tax-estimate');
      const blocked = await within(box).findByTestId('tax-zip-blocked');
      expect(blocked).toHaveTextContent(`We can’t sell ${line.title} yet. Remove it to see your tax.`);
      expect(blocked.textContent).not.toMatch(/AT0000|\$0\.00|at checkout/);
      expect(within(box).queryByTestId('tax-zip-form')).not.toBeInTheDocument();
      await userEvent.setup().click(within(box).getByRole('button', { name: `Remove ${line.title} from this order` }));
      await waitFor(() => expect(sent.some((r) => r.method === 'DELETE' && r.url.includes('/v1/cart/items/'))).toBe(true));
    });

    it('a saved address without a state or ZIP code asks to complete it, with Edit address', async () => {
      await overrideEstimate({ taxEstimateStatus: 'UNAVAILABLE', taxUnavailableReason: 'ADDRESS_INCOMPLETE', taxMinor: 0, taxJurisdictionLabel: '' });
      renderWithProviders(<CartView />);
      const box = await screen.findByTestId('cart-tax-estimate');
      const note = await within(box).findByTestId('tax-zip-incomplete');
      expect(note).toHaveTextContent('Add your state and ZIP code to this address');
      expect(within(note).getByRole('link', { name: 'Edit address' })).toHaveAttribute('href', '/account/addresses');
      expect(within(box).queryByTestId('tax-zip-form')).not.toBeInTheDocument();
    });

    it('when the estimate can’t be worked out: a plain try-again with Retry, never the ZIP box, never $0.00; Retry asks again', async () => {
      const { real } = await overrideEstimate({ taxEstimateStatus: 'UNAVAILABLE', taxUnavailableReason: 'SERVICE_UNAVAILABLE', taxMinor: 0, taxJurisdictionLabel: '' });
      renderWithProviders(<CartView />);
      const box = await screen.findByTestId('cart-tax-estimate');
      const retry = await within(box).findByTestId('tax-zip-retry');
      expect(retry).toHaveTextContent('We couldn’t work out your tax right now. Try again.');
      expect(box.textContent).not.toMatch(/\$0\.00|at checkout|calculated/);
      expect(within(box).queryByTestId('tax-zip-form')).not.toBeInTheDocument();
      mockServer.use(http.get('*/v1/checkout/tax-estimate', () => HttpResponse.json(real)));
      await userEvent.setup().click(within(retry).getByRole('button', { name: 'Retry' }));
      expect(await within(box).findByTestId('tax-zip-result')).toHaveTextContent('Estimated for Houston, TX');
    });

    it('a failing request is also a Retry, not the ZIP box', async () => {
      await signedInWithOneItem();
      mockServer.use(
        http.get('*/v1/checkout/tax-estimate', () => HttpResponse.json({ type: 'about:blank', title: 'Unavailable', status: 503, detail: 'down' }, { status: 503, headers: { 'content-type': 'application/problem+json' } }))
      );
      renderWithProviders(<CartView />);
      const box = await screen.findByTestId('cart-tax-estimate');
      expect(await within(box).findByTestId('tax-zip-retry')).toBeInTheDocument();
      expect(within(box).queryByTestId('tax-zip-form')).not.toBeInTheDocument();
    });

    it('an estimate with no place is never shown as an amount', async () => {
      await overrideEstimate({ taxJurisdictionLabel: '', taxZip: undefined });
      renderWithProviders(<CartView />);
      const box = await screen.findByTestId('cart-tax-estimate');
      expect(await within(box).findByTestId('tax-zip-retry')).toBeInTheDocument();
      expect(within(box).queryByTestId('tax-zip-amount')).not.toBeInTheDocument();
    });
  });

  describe('plural forms', () => {
    it('says 1 item and 1 seller in the singular, the plural otherwise', () => {
      expect(tEn('cart.subtotal', { count: 1 })).toBe('Subtotal (1 item):');
      expect(tEn('cart.subtotal', { count: 2 })).toBe('Subtotal (2 items):');
      expect(tEn('co.items', { n: 1 })).toBe('1 item');
      expect(tEn('co.items', { n: 3 })).toBe('3 items');
      expect(tEn('co.sellers', { n: 1 })).toBe('From 1 seller');
      expect(tEn('co.sellers', { n: 2 })).toBe('From 2 sellers');
      expect(tEn('co.reviewItems', { n: 1 })).toBe('Review 1 item');
      expect(tEn('cart.saved', { count: 1 })).toBe('Saved for later (1 item)');
      expect(tEn('cart.itemCount', { count: 1 })).toBe('Cart now has 1 item');
      expect(tEn('header.cartAria', { count: 1 })).toBe('Cart, 1 item');
      expect(tEn('co.pickupNote', { n: 1 })).toBe('1 pickup: no shipping charge');
      expect(tEn('dq.items', { n: 1, place: 'Houston' })).toBe('1 item to Houston');
      expect(tEn('co.taxBlockedNamed', { names: 'A', n: 1 })).toContain('Remove it');
      expect(tEn('co.taxBlockedNamed', { names: 'A and B', n: 2 })).toContain('Remove them');
    });

    it('the cart page itself says "1 item"', async () => {
      await signedInWithOneItem();
      renderWithProviders(<CartView />);
      expect(await screen.findByTestId('cart-subtotal')).toBeInTheDocument();
      expect(document.body.textContent).toMatch(/Subtotal \(1 item\):/);
      expect(document.body.textContent).not.toMatch(/1 items/);
    });
  });
});
