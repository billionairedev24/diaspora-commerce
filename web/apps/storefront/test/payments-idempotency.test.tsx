/* No double charge (fake Stripe.js, mock BFF): checkout keeps its Idempotency-Key until the attempt is over, places
   once on a double click; the late pre-order pay-now page confirms the order's
   PaymentIntent with Stripe Elements and refuses invalid, expired and paid links. */
import { MOCK_PAY_LINK } from '@afrotransact/api-client/mocks';
import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { CheckoutView } from '@/components/checkout/CheckoutView';
import { PayNowView } from '@/components/paynow/PayNowView';
import { resetStripe } from '@/lib/payments/stripe';

import { fakeStripe } from './fakeStripe';
import { addCookie, failNext, installMockBff, sent, signInDemo } from './mockBff';
import { renderWithProviders } from './render';

const nav = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), params: new URLSearchParams() }));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: nav.push, replace: nav.replace, refresh: vi.fn(), back: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => '/checkout',
  useSearchParams: () => nav.params
}));

const isPlace = (r: Request) => r.method === 'POST' && new URL(r.url).pathname.endsWith('/v1/checkout/orders');
const placeKeys = () => sent.filter(isPlace).map((r) => r.headers.get('Idempotency-Key'));

let stripe: ReturnType<typeof fakeStripe>;

describe('checkout idempotency (fake Stripe.js)', () => {
  installMockBff();

  beforeEach(() => {
    resetStripe();
    stripe = fakeStripe();
    window.Stripe = stripe.factory;
    nav.push.mockReset();
  });
  afterEach(() => {
    delete window.Stripe;
  });

  /** Signed in with jollof in the cart and a new payment method complete in the always-visible Payment Element. */
  async function ready() {
    const api = await signInDemo();
    const jollof = await api.getProduct('AT000000P2');
    await api.addToCart({ offerId: jollof.buyBox?.offerId ?? '', quantity: 2 });
    addCookie('at_mock_stripe_pk=pk_test_fake');
    const user = userEvent.setup();
    renderWithProviders(<CheckoutView />);
    await screen.findByTestId('checkout-package');
    await waitFor(() => expect(stripe.payment()).toBeDefined());
    const el = await screen.findByTestId('stripe-payment-element');
    await waitFor(() => expect(el).toHaveAttribute('aria-busy', 'false'));
    act(() => stripe.payment()?.handlers.payment?.change?.({ complete: true, empty: false, value: { type: 'card' } }));
    // No Express Checkout row: wallets are inside the Payment Element.
    expect(stripe.express()).toBeUndefined();
    return user;
  }
  const placeButton = () => screen.getByRole('button', { name: /Place your order|Placing your order/ });

  it('keeps the key after a network error: the retry is the same attempt', async () => {
    const user = await ready();
    failNext(isPlace, 'network');
    await user.click(placeButton());
    expect((await screen.findAllByRole('alert')).length).toBeGreaterThan(0);
    await waitFor(() => expect(placeButton()).toBeEnabled());
    await user.click(placeButton());
    await waitFor(() => expect(nav.push).toHaveBeenCalledWith(expect.stringMatching(/^\/orders\?placed=/)), { timeout: 5000 });
    const keys = placeKeys();
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
  });

  it('keeps the key when the answer was lost: the retry replays the same order and PaymentIntent', async () => {
    const user = await ready();
    failNext(isPlace, 'lost');
    await user.click(placeButton());
    expect((await screen.findAllByRole('alert')).length).toBeGreaterThan(0);
    await waitFor(() => expect(placeButton()).toBeEnabled());
    await user.click(placeButton());
    await waitFor(() => expect(nav.push).toHaveBeenCalled(), { timeout: 5000 });
    expect(new Set(placeKeys()).size).toBe(1);
    expect(stripe.confirms).toHaveLength(1);
  });

  it('keeps the key after a Stripe confirmation error and confirms the same PaymentIntent again', async () => {
    const user = await ready();
    stripe.results.confirm = { error: { type: 'card_error', code: 'card_declined', message: 'Your card was declined.' } };
    await user.click(placeButton());
    expect(await screen.findByTestId('payment-element-error')).toHaveTextContent('Your card was declined.');
    stripe.results.confirm = { paymentIntent: { id: 'pi_1', status: 'requires_capture' } };
    await user.click(await screen.findByRole('button', { name: 'Place your order' }));
    await waitFor(() => expect(nav.push).toHaveBeenCalled(), { timeout: 5000 });
    expect(new Set(placeKeys()).size).toBe(1);
    expect(stripe.confirms).toHaveLength(2);
    expect(stripe.confirms[1]?.clientSecret).toBe(stripe.confirms[0]?.clientSecret);
  });

  it('places once on a double click', async () => {
    await ready();
    const button = placeButton();
    act(() => {
      fireEvent.click(button);
      fireEvent.click(button);
    });
    await waitFor(() => expect(nav.push).toHaveBeenCalled(), { timeout: 5000 });
    expect(placeKeys()).toHaveLength(1);
    expect(stripe.confirms).toHaveLength(1);
  });
});

describe('pay-now page of a late pre-order add', () => {
  installMockBff();

  beforeEach(() => {
    resetStripe();
    stripe = fakeStripe();
    window.Stripe = stripe.factory;
    addCookie('at_mock_stripe_pk=pk_test_fake');
  });
  afterEach(() => {
    delete window.Stripe;
  });

  const open = (token: string) => {
    nav.params = new URLSearchParams(token ? { token } : {});
    renderWithProviders(<PayNowView orderNumber={MOCK_PAY_LINK.orderNumber} />);
  };

  it('confirms the order’s PaymentIntent with the Payment Element (no hosted page), then shows it paid', async () => {
    const user = userEvent.setup();
    open(MOCK_PAY_LINK.token);
    expect(await screen.findByRole('heading', { name: 'Complete your pre-order payment' })).toBeInTheDocument();
    await waitFor(() => expect(stripe.payment()).toBeDefined());
    expect(stripe.payment()?.options).toMatchObject({ mode: 'payment', amount: 4297, currency: 'usd', captureMethod: 'manual' });
    expect(stripe.express()).toBeDefined();
    const el = await screen.findByTestId('stripe-payment-element');
    await waitFor(() => expect(el).toHaveAttribute('aria-busy', 'false'));
    await user.click(screen.getByTestId('paynow-pay'));
    expect(await screen.findByTestId('paynow-paid')).toHaveTextContent('This pre-order is paid');
    expect(stripe.confirms).toHaveLength(1);
    expect(stripe.confirms[0]).toMatchObject({ clientSecret: 'pi_mock_paylink_secret_mock', redirect: 'if_required' });
    expect(stripe.confirms[0]?.elements).toBeDefined();
    expect(String(stripe.confirms[0]?.confirmParams?.return_url)).toContain(`/orders/${MOCK_PAY_LINK.orderNumber}/pay?token=`);
  });

  it('refuses a paid link: nothing to pay', async () => {
    const api = await signInDemo();
    await api.confirmPayLink(MOCK_PAY_LINK.orderNumber, MOCK_PAY_LINK.token);
    open(MOCK_PAY_LINK.token);
    expect(await screen.findByTestId('paynow-paid')).toBeInTheDocument();
    expect(screen.queryByTestId('paynow-pay')).not.toBeInTheDocument();
    expect(stripe.factory).not.toHaveBeenCalled();
  });

  it('refuses an expired link', async () => {
    open(MOCK_PAY_LINK.expiredToken);
    expect(await screen.findByRole('heading', { name: 'This payment link has expired' })).toBeInTheDocument();
    expect(screen.queryByTestId('paynow-pay')).not.toBeInTheDocument();
  });

  it('refuses an invalid or missing token', async () => {
    open('forged-token');
    expect(await screen.findByRole('heading', { name: 'This payment link is not valid' })).toBeInTheDocument();
  });

  it('refuses a link without a token', async () => {
    open('');
    expect(await screen.findByRole('heading', { name: 'This payment link is not valid' })).toBeInTheDocument();
    expect(sent.some((r) => r.url.includes('/v1/pay-links/'))).toBe(false);
  });
});
