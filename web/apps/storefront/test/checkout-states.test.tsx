/* What checkout says when the cart was not read, has nothing selected, or card payment cannot load. */
import { http, HttpResponse, mockServer } from '@afrotransact/api-client/mocks/node';
import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { CheckoutView } from '@/components/checkout/CheckoutView';
import { resetStripe } from '@/lib/payments/stripe';

import { fakeStripe } from './fakeStripe';
import { addCookie, installMockBff, signInDemo } from './mockBff';
import { renderWithProviders } from './render';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), back: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => '/checkout',
  useSearchParams: () => new URLSearchParams()
}));

describe('checkout states', () => {
  installMockBff();
  beforeEach(() => resetStripe());
  afterEach(() => {
    delete window.Stripe;
  });

  it('a cart that could not be read is an error with Try again, never "0 items"', async () => {
    await signInDemo();
    mockServer.use(http.get('*/v1/checkout/summary', () => HttpResponse.json({ title: 'x', status: 503 }, { status: 503 })));
    renderWithProviders(<CheckoutView />);
    expect(await screen.findByTestId('checkout-error')).toHaveTextContent('Checkout is unavailable');
    expect(screen.getByTestId('checkout-retry')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Place your order' })).not.toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/0 items|\$0\.00/);
  });

  it('an empty cart says so and offers to continue shopping', async () => {
    await signInDemo();
    renderWithProviders(<CheckoutView />);
    expect(await screen.findByTestId('checkout-empty', undefined, { timeout: 5000 })).toHaveTextContent('Your cart is empty');
    expect(screen.queryByRole('button', { name: 'Place your order' })).not.toBeInTheDocument();
  });

  it('a payment form that fails to load shows one line and Try again, hides the form, and keeps saved cards usable', async () => {
    const api = await signInDemo();
    const p = await api.getProduct('AT000000P2');
    await api.addToCart({ offerId: p.buyBox?.offerId ?? '', quantity: 1 });
    addCookie('at_mock_stripe_pk=pk_test_fake');
    const stripe = fakeStripe();
    window.Stripe = stripe.factory;
    renderWithProviders(<CheckoutView />);
    await screen.findByTestId('checkout-package');
    await waitFor(() => expect(stripe.payment()).toBeDefined());
    act(() => stripe.payment()?.handlers.payment?.loaderror?.({ error: { message: 'bad key' } }));
    const box = await screen.findByTestId('payment-unavailable');
    expect(box).toHaveTextContent('Card payment isn’t available right now.');
    expect(box).toHaveAttribute('data-reason', 'element');
    expect(screen.queryByText('bad key')).not.toBeInTheDocument();
    expect(screen.queryByTestId('stripe-payment-element')).not.toBeInTheDocument();
    // only the form failed: the shopper's saved card still pays
    expect(screen.queryByTestId('checkout-use-new')).not.toBeInTheDocument();
    // Try again mounts a fresh form
    const before = stripe.elements.length;
    await userEvent.setup().click(screen.getByTestId('payment-unavailable-retry'));
    await waitFor(() => expect(stripe.elements.length).toBeGreaterThan(before));
    await waitFor(() => expect(screen.queryByTestId('payment-unavailable')).not.toBeInTheDocument());
  });

  it('Stripe.js itself being down stops Place order and says why', async () => {
    const api = await signInDemo();
    const p = await api.getProduct('AT000000P2');
    await api.addToCart({ offerId: p.buyBox?.offerId ?? '', quantity: 1 });
    addCookie('at_mock_stripe_pk=pk_test_fake');
    window.Stripe = () => {
      throw new Error('blocked');
    };
    renderWithProviders(<CheckoutView />);
    const box = await screen.findByTestId('payment-unavailable');
    expect(box).toHaveAttribute('data-reason', 'script');
    expect(screen.getByRole('button', { name: 'Place your order' })).toBeDisabled();
    expect(screen.getByTestId('payment-blocked-note')).toHaveTextContent('Place order is off until card payment is available.');
  });
});
