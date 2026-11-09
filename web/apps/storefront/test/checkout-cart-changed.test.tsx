/* A cart mismatch at checkout (orders.line-not-in-cart and kin) reads "Your cart changed, please review it." with a link
   back to the cart; the problem's detail, which names an internal ATIN, is never shown. */
import { ApiError, isCartMismatch } from '@afrotransact/api-client';
import { http, HttpResponse, mockServer } from '@afrotransact/api-client/mocks/node';
import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { CheckoutView } from '@/components/checkout/CheckoutView';
import { resetStripe } from '@/lib/payments/stripe';

import { fakeStripe } from './fakeStripe';
import { addCookie, installMockBff, signInDemo } from './mockBff';
import { renderWithProviders } from './render';

const nav = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), params: new URLSearchParams() }));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: nav.push, replace: nav.replace, refresh: vi.fn(), back: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => '/checkout',
  useSearchParams: () => nav.params
}));

const problem = (code: string, detail: string) =>
  HttpResponse.json({ type: 'about:blank', title: 'Unprocessable', status: 422, code, detail }, { status: 422, headers: { 'content-type': 'application/problem+json' } });

describe('isCartMismatch', () => {
  const api = (code: string) => new ApiError({ type: 'about:blank', title: 't', status: 422, code });
  it('recognises the cart mismatch codes only', () => {
    for (const code of ['orders.line-not-in-cart', 'orders.cart-changed', 'orders.cart-empty', 'orders.cart-not-checkoutable']) {
      expect(isCartMismatch(api(code))).toBe(true);
    }
    expect(isCartMismatch(api('orders.address-not-found'))).toBe(false);
    expect(isCartMismatch(new Error('orders.line-not-in-cart'))).toBe(false);
  });
});

describe('checkout when the cart changed', () => {
  installMockBff();

  beforeEach(() => {
    resetStripe();
    window.Stripe = fakeStripe().factory;
  });
  afterEach(() => {
    delete window.Stripe;
  });

  it('says so in plain words with a link back to the cart, never the ATIN', async () => {
    mockServer.use(http.post('*/v1/checkout/orders', () => problem('orders.line-not-in-cart', 'ATG0000123 is not in your cart')));
    const api = await signInDemo();
    const jollof = await api.getProduct('AT000000P2');
    await api.addToCart({ offerId: jollof.buyBox?.offerId ?? '', quantity: 1 });
    addCookie('at_mock_stripe_pk=pk_test_fake');
    const user = userEvent.setup();
    const stripe = fakeStripe();
    window.Stripe = stripe.factory;
    renderWithProviders(<CheckoutView />);
    await screen.findByTestId('checkout-package');
    await waitFor(() => expect(stripe.payment()).toBeDefined());
    act(() => stripe.payment()?.handlers.payment?.change?.({ complete: true, empty: false, value: { type: 'card' } }));

    await user.click(screen.getByRole('button', { name: /Place your order/ }));

    const alert = await screen.findByText(/Your cart changed, please review it\./);
    expect(alert).toBeInTheDocument();
    expect(screen.queryByText(/ATG0000123/)).not.toBeInTheDocument();
    expect(screen.queryByText(/is not in your cart/)).not.toBeInTheDocument();
    expect(screen.getByTestId('checkout-review-cart')).toHaveAttribute('href', '/cart');
  });
});
