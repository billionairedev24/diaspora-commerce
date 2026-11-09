/* Checkout tax in the summary: "Estimated tax" with where it was worked out, exempt items named, and items that can't
   be sold yet (no definite tax decision) listed in plain words with Remove: no ids, no tax terms, the order can't be
   placed meanwhile. Mock BFF + mock Stripe. */
import { type CheckoutSummary } from '@afrotransact/api-client';
import { atinOf } from '@afrotransact/api-client/mocks';
import { http, HttpResponse, mockServer } from '@afrotransact/api-client/mocks/node';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { CheckoutView } from '@/components/checkout/CheckoutView';
import { resetStripe } from '@/lib/payments/stripe';

import { fakeStripe } from './fakeStripe';
import { addCookie, installMockBff, sent, signInDemo } from './mockBff';
import { renderWithProviders } from './render';

const nav = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), params: new URLSearchParams() }));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: nav.push, replace: nav.replace, refresh: vi.fn(), back: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => '/checkout',
  useSearchParams: () => nav.params
}));

const problem = (code: string, detail: string) =>
  HttpResponse.json({ type: 'about:blank', title: 'Unprocessable', status: 422, code, detail }, { status: 422, headers: { 'content-type': 'application/problem+json' } });

describe('checkout tax', () => {
  installMockBff();

  beforeEach(() => {
    resetStripe();
    window.Stripe = fakeStripe().factory;
  });
  afterEach(() => {
    delete window.Stripe;
  });

  it('a basket of exempt items shows Estimated tax with the place, no tax on the items or their delivery', async () => {
    const api = await signInDemo();
    const jollof = await api.getProduct('AT000000P2');
    await api.addToCart({ offerId: jollof.buyBox?.offerId ?? '', quantity: 2 });
    renderWithProviders(<CheckoutView />);
    await screen.findByTestId('checkout-package');
    const summary = await screen.findByTestId('order-summary');
    expect(within(summary).getByText('Estimated tax')).toBeInTheDocument();
    await waitFor(() => expect(within(summary).getByTestId('checkout-tax-place')).toHaveTextContent('Estimated for Houston, TX'));
    expect(within(summary).getByTestId('checkout-tax-amount')).toHaveTextContent('$0.00');
    expect(within(summary).getByTestId('checkout-tax-exempt-note')).toHaveTextContent('No tax on these items, or on their delivery.');
    expect(screen.getAllByTestId('checkout-item-no-tax')).toHaveLength(1);
    expect(screen.queryByTestId('checkout-blocked')).not.toBeInTheDocument();
  });

  it('a mixed basket taxes only the taxable item and says some items carry no tax', async () => {
    const api = await signInDemo();
    const jollof = await api.getProduct('AT000000P2');
    const pillow = await api.getProduct(atinOf('p20'));
    await api.addToCart({ offerId: jollof.buyBox?.offerId ?? '', quantity: 1 });
    await api.addToCart({ offerId: pillow.buyBox?.offerId ?? '', quantity: 1 });
    renderWithProviders(<CheckoutView />);
    const summary = await screen.findByTestId('order-summary');
    await waitFor(() => expect(within(summary).getByTestId('checkout-tax-exempt-note')).toHaveTextContent('No tax on some of your items, or on their delivery.'));
    const taxed = within(summary).getByTestId('checkout-tax-amount').textContent ?? '';
    expect(taxed).not.toBe('$0.00');
    expect(taxed).toMatch(/^\$\d+\.\d{2}$/);
    expect(screen.getAllByTestId('checkout-item-no-tax')).toHaveLength(1);
  });

  describe('an item that can’t be sold yet', () => {
    const blockedSummary = async (): Promise<CheckoutSummary> => {
      const api = await signInDemo();
      const jollof = await api.getProduct('AT000000P2');
      await api.addToCart({ offerId: jollof.buyBox?.offerId ?? '', quantity: 1 });
      const real = await api.getCheckoutSummary();
      const line = real.packages[0]?.lines[0];
      if (!line) throw new Error('the mock cart has a line');
      return {
        ...real,
        taxMinor: 0,
        taxLines: [{ lineId: line.lineId, status: 'BLOCKED', taxMinor: 0, shippingMinor: 0 }],
        blockedLines: [{ lineId: line.lineId, offerId: line.offerId, atin: line.atin, title: line.title, packageId: real.packages[0]?.packageId ?? '', message: 'We can’t sell it. (English, from the server)', action: 'REMOVE' }],
        canPlaceOrder: false
      };
    };

    it('lists it by name with Remove, no ids, and the order can’t be placed until it is removed', async () => {
      const held = await blockedSummary();
      const clean = { ...held, blockedLines: [], taxLines: [], canPlaceOrder: true };
      // The summary holds the item back until the page asked the cart to take it out of this order.
      const removed = () => sent.some((r) => r.method === 'PATCH' && r.url.includes('/v1/cart/items/'));
      mockServer.use(http.get('*/v1/checkout/summary', () => HttpResponse.json(removed() ? clean : held)));
      const user = userEvent.setup();
      renderWithProviders(<CheckoutView />);
      const box = await screen.findByTestId('checkout-blocked');
      const title = held.blockedLines[0]?.title ?? '';
      expect(within(box).getByTestId('checkout-blocked-line')).toHaveTextContent(`We can’t sell ${title} yet.`);
      expect(box).toHaveTextContent('Remove it from this order to continue');
      expect(box.textContent).not.toMatch(/AT0000|tax|ATIN/i);
      expect(screen.getByTestId('tax-blocked-note')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Place your order' })).toBeDisabled();

      // Remove takes the line out of this order (it stays in the cart), then the summary is read again.
      await user.click(within(box).getByRole('button', { name: `Remove ${title} from this order` }));
      await waitFor(() => expect(screen.queryByTestId('checkout-blocked')).not.toBeInTheDocument());
      expect(sent.some((r) => r.method === 'PATCH' && r.url.includes('/v1/cart/items/'))).toBe(true);
      expect(screen.queryByTestId('tax-blocked-note')).not.toBeInTheDocument();
    });

    it('a refused placement says so in plain words, never the server’s detail', async () => {
      addCookie('at_mock_stripe_pk=pk_test_fake');
      const held = await blockedSummary();
      mockServer.use(
        http.get('*/v1/checkout/summary', () => HttpResponse.json({ ...held, blockedLines: [], taxLines: [], canPlaceOrder: true })),
        http.post('*/v1/checkout/orders', () => problem('CHECKOUT_ITEM_BLOCKED', 'We can’t sell ATG0000123 (orders detail)'))
      );
      const stripe = fakeStripe();
      window.Stripe = stripe.factory;
      const user = userEvent.setup();
      renderWithProviders(<CheckoutView />);
      await screen.findByTestId('checkout-package');
      await waitFor(() => expect(stripe.payment()).toBeDefined());
      const el = await screen.findByTestId('stripe-payment-element');
      await waitFor(() => expect(el).toHaveAttribute('aria-busy', 'false'));
      act(() => stripe.payment()?.handlers.payment?.change?.({ complete: true, empty: false, value: { type: 'card' } }));
      await user.click(screen.getByRole('button', { name: /Place your order/ }));
      expect(await screen.findByText('Some items in your order can’t be sold yet. Remove them to place your order.')).toBeInTheDocument();
      expect(screen.queryByText(/ATG0000123/)).not.toBeInTheDocument();
    });
  });
});
