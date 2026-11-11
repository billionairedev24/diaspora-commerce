/* "Ask for a delivery quote" in the storefront (listing rules D20): the ask box, My quote requests, and a ready quote applied
   at checkout (the agreed amount is the shipping, sent as quoteId, used once). */
import { decideMockQuote } from '@afrotransact/api-client/mocks';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AskForQuote } from '@/components/checkout/AskForQuote';
import { CheckoutView } from '@/components/checkout/CheckoutView';
import { DeliveryQuotesView } from '@/components/checkout/DeliveryQuotesView';
import { resetStripe } from '@/lib/payments/stripe';

import { fakeStripe } from './fakeStripe';
import { addCookie, installMockBff, sent, signInDemo } from './mockBff';
import { renderWithProviders } from './render';

const nav = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), params: new URLSearchParams() }));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: nav.push, replace: nav.replace, refresh: vi.fn(), back: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => '/account/delivery-quotes',
  useSearchParams: () => nav.params
}));

installMockBff();

beforeEach(() => {
  nav.params = new URLSearchParams();
  nav.push.mockClear();
});

describe('asking for a quote', () => {
  it('sends the request, says what happens next, and a repeat is the same request', async () => {
    const api = await signInDemo();
    const user = userEvent.setup();
    renderWithProviders(<AskForQuote addressId="ad1" place="Houston, TX 77042" />);
    expect(screen.getByRole('heading', { name: 'Too far for standard delivery' })).toBeInTheDocument();
    expect(screen.getByText(/Our team prices the delivery to Houston, TX 77042/)).toBeInTheDocument();
    await user.type(screen.getByTestId('ask-for-quote-note'), 'Gate code 4821');
    await user.click(screen.getByTestId('ask-for-quote-send'));
    expect(await screen.findByText(/We’ll email you when your quote is ready/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'See my quote requests' })).toHaveAttribute('href', '/account/delivery-quotes');
    expect((await api.listMyDeliveryQuotes()).items).toHaveLength(1);
    expect((await api.requestDeliveryQuote({ addressId: 'ad1' })).note).toBe('Gate code 4821');
    expect((await api.listMyDeliveryQuotes()).items).toHaveLength(1);
  });

  it('asks a signed-out shopper to sign in first', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AskForQuote addressId="ad1" place="Houston, TX 77042" />);
    await user.click(screen.getByTestId('ask-for-quote-send'));
    expect(await screen.findByText('Sign in to ask for a quote')).toBeInTheDocument();
  });
});

describe('My quote requests', () => {
  it('shows each request with a plain status, the amount and until when it can be used', async () => {
    const api = await signInDemo();
    const asked = await api.requestDeliveryQuote({ addressId: 'ad1' });
    const declined = await api.requestDeliveryQuote({ addressId: 'ad2' });
    decideMockQuote('u_adaeze', declined.id, { status: 'DECLINED' });
    renderWithProviders(<DeliveryQuotesView />);
    const cards = await screen.findAllByTestId('quote-card');
    expect(cards).toHaveLength(2);
    const byRef = (ref: string) => cards.find((c) => within(c).queryByText(new RegExp(ref)))!;
    expect(within(byRef(declined.reference)).getByText('Declined')).toBeInTheDocument();
    expect(within(byRef(declined.reference)).getByText(/can’t offer delivery to this address/)).toBeInTheDocument();
    expect(within(byRef(asked.reference)).getByText('Requested')).toBeInTheDocument();
    expect(screen.queryByTestId('quote-amount')).not.toBeInTheDocument();
  });

  it('a ready quote shows the amount and links back to checkout; an expired one does not', async () => {
    const api = await signInDemo();
    const q = await api.requestDeliveryQuote({ addressId: 'ad1' });
    decideMockQuote('u_adaeze', q.id, { status: 'IN_PROGRESS' });
    decideMockQuote('u_adaeze', q.id, { status: 'READY', amountMinor: 2250 });
    const { unmount } = renderWithProviders(<DeliveryQuotesView />);
    expect(await screen.findByText('Quote ready')).toBeInTheDocument();
    expect(screen.getByTestId('quote-amount')).toHaveTextContent('$22.50');
    expect(screen.getByText(/Use it before/)).toBeInTheDocument();
    expect(screen.getByTestId('quote-use')).toHaveAttribute('href', `/checkout?quote=${q.id}`);
    unmount();
    decideMockQuote('u_adaeze', q.id, { status: 'READY', amountMinor: 2250, expiresAt: new Date(Date.now() - 60_000).toISOString() });
    renderWithProviders(<DeliveryQuotesView />);
    expect(await screen.findByText('Expired')).toBeInTheDocument();
    expect(screen.queryByTestId('quote-use')).not.toBeInTheDocument();
  });

  it('is empty until the shopper asks', async () => {
    await signInDemo();
    renderWithProviders(<DeliveryQuotesView />);
    expect(await screen.findByTestId('quotes-empty')).toHaveTextContent(/haven’t asked for a delivery quote/);
  });
});

describe('checkout with an agreed quote', () => {
  beforeEach(() => {
    resetStripe();
    window.Stripe = fakeStripe().factory;
  });
  afterEach(() => {
    delete window.Stripe;
  });

  async function cartAndQuote(amountMinor: number | null) {
    const api = await signInDemo();
    const jollof = await api.getProduct('AT000000P2');
    await api.addToCart({ offerId: jollof.buyBox?.offerId ?? '', quantity: 1 });
    const q = await api.requestDeliveryQuote({ addressId: 'ad1' });
    if (amountMinor !== null) decideMockQuote('u_adaeze', q.id, { status: 'READY', amountMinor });
    nav.params = new URLSearchParams({ quote: q.id });
    addCookie('at_mock_stripe_pk=pk_test_fake');
    return { api, q };
  }

  it('applies the quote: the amount is the shipping, the order carries the quote once and it reads as used', async () => {
    const { api, q } = await cartAndQuote(1800);
    const user = userEvent.setup();
    const stripe = fakeStripe();
    window.Stripe = stripe.factory;
    renderWithProviders(<CheckoutView />);
    const notice = await screen.findByTestId('quote-applied');
    expect(notice).toHaveTextContent('Delivery quote applied: $18.00');
    expect(notice).toHaveTextContent(/Tax on the delivery charge is added when you place the order/);
    await screen.findByTestId('checkout-package');
    await waitFor(() => expect(stripe.payment()).toBeDefined());
    act(() => stripe.payment()?.handlers.payment?.change?.({ complete: true, empty: false, value: { type: 'card' } }));

    await user.click(screen.getByRole('button', { name: /Place your order/ }));
    await waitFor(() => expect(nav.push).toHaveBeenCalledWith(expect.stringMatching(/^\/orders\?placed=AT-2026-/)));
    const place = sent.filter((r) => r.url.endsWith('/v1/checkout/orders')).at(-1)!;
    const body = (await place.clone().json()) as { deliverySelections: Array<{ quoteId?: string; option: string }> };
    expect(body.deliverySelections.every((s) => s.option === 'pickup' || s.quoteId === q.id)).toBe(true);
    expect(body.deliverySelections.some((s) => s.quoteId === q.id)).toBe(true);
    expect((await api.getMyDeliveryQuote(q.id)).status).toBe('USED');
  });

  it('a quote that has expired or is not ready is explained and asks for a new one; nothing is charged for it', async () => {
    await cartAndQuote(null);
    renderWithProviders(<CheckoutView />);
    const notice = await screen.findByTestId('quote-void');
    expect(notice).toHaveTextContent(/isn’t ready yet/);
    expect(within(notice).getByRole('link', { name: 'Ask for a new quote' })).toHaveAttribute('href', '/account/delivery-quotes');
    await screen.findByTestId('checkout-package');
    expect(screen.queryByTestId('quote-applied')).not.toBeInTheDocument();
  });

  it('another shopper’s or unknown quote reads as void', async () => {
    const api = await signInDemo();
    const jollof = await api.getProduct('AT000000P2');
    await api.addToCart({ offerId: jollof.buyBox?.offerId ?? '', quantity: 1 });
    nav.params = new URLSearchParams({ quote: 'not-mine' });
    renderWithProviders(<CheckoutView />);
    expect(await screen.findByTestId('quote-void')).toHaveTextContent('This delivery quote can’t be used.');
  });
});
