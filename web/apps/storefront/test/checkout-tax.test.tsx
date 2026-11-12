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
    // An untaxed item says why, in the words of tax-returns' decision (never hard-coded per category here).
    expect(within(summary).getByTestId('checkout-tax-exempt-reason')).toHaveTextContent(/no sales tax applies/);
  });

  describe('guard: no $0.00, 0% or "no tax" unless the decision is resolved (owner principle: never wrong tax information)', () => {
    let okSummary: CheckoutSummary;
    const overridden = async (patch: (s: CheckoutSummary) => Partial<CheckoutSummary>) => {
      const api = await signInDemo();
      const jollof = await api.getProduct('AT000000P2');
      await api.addToCart({ offerId: jollof.buyBox?.offerId ?? '', quantity: 1 });
      const real = await api.getCheckoutSummary();
      okSummary = real;
      mockServer.use(http.get('*/v1/checkout/summary', () => HttpResponse.json({ ...real, ...patch(real) })));
      renderWithProviders(<CheckoutView />);
      await screen.findByTestId('checkout-package');
      return within(await screen.findByTestId('order-summary'));
    };
    const NEVER = /\$0\.00|0(\.0+)?\s*%|not taxable|no tax/i;

    it('tax-returns could not answer: a plain "try again" with Retry, no amount, never "calculated later", and the order can’t be placed', async () => {
      const summary = await overridden(() => ({
        taxMinor: 0,
        taxRateLabel: 'We couldn’t work out your tax right now. Try again.',
        taxEstimateStatus: 'UNAVAILABLE',
        taxUnavailableReason: 'SERVICE_UNAVAILABLE',
        taxJurisdictionLabel: '',
        taxLines: [],
        canPlaceOrder: false
      }));
      expect(summary.getByTestId('checkout-tax-amount')).toHaveTextContent('—');
      const state = summary.getByTestId('checkout-tax-state');
      expect(state).toHaveTextContent('We couldn’t work out your tax right now. Try again.');
      expect(state.textContent).not.toMatch(NEVER);
      expect(screen.getByRole('button', { name: 'Place your order' })).toBeDisabled();
      expect(screen.getByTestId('tax-retry-hint')).toBeInTheDocument();
      expect(summary.queryByTestId('checkout-tax-place')).not.toBeInTheDocument();
      expect(document.body.textContent).not.toMatch(/when you place your order|at checkout|calculated later/i);
      // Retry asks the BFF again (and, once tax is worked out, the order can be placed)
      let calls = 0;
      mockServer.use(http.get('*/v1/checkout/summary', () => (calls++, HttpResponse.json({ ...okSummary }))));
      await userEvent.setup().click(within(state).getByTestId('checkout-tax-retry'));
      await waitFor(() => expect(calls).toBeGreaterThan(0));
      await waitFor(() => expect(screen.queryByTestId('checkout-tax-retry')).not.toBeInTheDocument());
      expect(screen.getByTestId('checkout-tax-amount').textContent).toMatch(/^\$\d+\.\d{2}$/);
    });

    it('no address and no ZIP code yet: an input for a ZIP code, never an amount; typing one shows the estimate for that place', async () => {
      const summary = await overridden(() => ({ taxMinor: 0, taxRateLabel: 'Enter your ZIP code to see tax', taxEstimateStatus: 'NEEDS_ADDRESS', taxUnavailableReason: 'NEEDS_ZIP', taxJurisdictionLabel: '', taxLines: [], canPlaceOrder: false }));
      expect(summary.getByTestId('checkout-tax-amount')).toHaveTextContent('—');
      expect(summary.getByText('Enter your ZIP code to see tax')).toBeInTheDocument();
      const user = userEvent.setup();
      await user.type(summary.getByLabelText('Enter your ZIP code to see tax'), '77002');
      await user.click(summary.getByRole('button', { name: 'See tax' }));
      expect(await summary.findByTestId('tax-zip-result')).toHaveTextContent('Estimated for Houston, TX');
      expect(summary.getByTestId('tax-zip-amount').textContent).toMatch(/^\$\d+\.\d{2}$/);
      expect(sent.some((r) => r.method === 'GET' && r.url.includes('/v1/checkout/tax-estimate') && r.url.includes('zip=77002'))).toBe(true);
    });

    it('a ZIP code that is not 5 digits is refused before asking', async () => {
      const summary = await overridden(() => ({ taxMinor: 0, taxEstimateStatus: 'NEEDS_ADDRESS', taxUnavailableReason: 'NEEDS_ZIP', taxJurisdictionLabel: '', taxLines: [], canPlaceOrder: false }));
      const user = userEvent.setup();
      await user.type(summary.getByLabelText('Enter your ZIP code to see tax'), '770');
      await user.click(summary.getByRole('button', { name: 'See tax' }));
      expect(await summary.findByText('Enter a 5-digit ZIP code.')).toBeInTheDocument();
      expect(sent.some((r) => r.url.includes('/v1/checkout/tax-estimate'))).toBe(false);
    });

    it('an address without a state or ZIP code says what to add and offers Edit address', async () => {
      const summary = await overridden(() => ({ taxMinor: 0, taxEstimateStatus: 'UNAVAILABLE', taxUnavailableReason: 'ADDRESS_INCOMPLETE', taxJurisdictionLabel: '', taxLines: [], canPlaceOrder: false }));
      expect(summary.getByTestId('checkout-tax-state')).toHaveTextContent('Add your state and ZIP code to this address to see your tax.');
      expect(summary.getByRole('button', { name: 'Edit address' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Place your order' })).toBeDisabled();
    });

    it('a known estimate says it is the tax you will pay, with the total labelled so it can be trusted', async () => {
      const summary = await overridden(() => ({}));
      await waitFor(() => expect(summary.getByTestId('checkout-tax-place')).toHaveTextContent('Estimated for Houston, TX'));
      expect(summary.queryByTestId('checkout-tax-state')).not.toBeInTheDocument();
      expect(screen.getByTestId('checkout-total-trust')).toHaveTextContent('You are never charged more without your OK.');
      expect(document.body.textContent).not.toMatch(/when you place your order|at checkout|calculated later/i);
    });

    it('a line whose decision is not resolved is never tagged or explained as untaxed, whatever else the payload says', async () => {
      const summary = await overridden((s) => {
        const line = s.packages[0]?.lines[0];
        return {
          taxMinor: 0,
          taxRateLabel: 'Remove the items we can’t sell yet to see your tax.',
          taxEstimateStatus: 'UNAVAILABLE',
          taxUnavailableReason: 'BLOCKED_ITEMS',
          taxLines: line ? [{ lineId: line.lineId, status: 'BLOCKED', taxMinor: 0, shippingMinor: 0, exemptReason: 'Groceries: no sales tax applies.' }] : []
        };
      });
      expect(summary.getByTestId('checkout-tax-amount').textContent).not.toMatch(NEVER);
      expect(summary.queryByTestId('checkout-tax-exempt-reason')).not.toBeInTheDocument();
      expect(screen.queryByTestId('checkout-item-no-tax')).not.toBeInTheDocument();
    });
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

  describe('the order would cost more than the total shown', () => {
    it('shows the new total, asks first, and places again with a NEW key and the new lock once confirmed', async () => {
      addCookie('at_mock_stripe_pk=pk_test_fake');
      const api = await signInDemo();
      const jollof = await api.getProduct('AT000000P2');
      await api.addToCart({ offerId: jollof.buyBox?.offerId ?? '', quantity: 1 });
      const real = await api.getCheckoutSummary();
      const bodies: { key: string | null; lock: string | undefined }[] = [];
      let first = true;
      mockServer.use(
        http.post('*/v1/checkout/orders', async ({ request }) => {
          const body = (await request.clone().json()) as { taxQuoteId?: string };
          bodies.push({ key: request.headers.get('Idempotency-Key'), lock: body.taxQuoteId });
          if (first) {
            first = false;
            return HttpResponse.json(
              { type: 'about:blank', title: 'Your total changed', status: 409, code: 'CHECKOUT_TOTAL_CHANGED', detail: 'Your total changed before we could charge you.', previousTotalMinor: real.totalMinor, totalMinor: real.totalMinor + 150, taxMinor: real.taxMinor + 150, shippingMinor: real.shippingMinor, subtotalMinor: real.subtotalMinor, discountMinor: 0, currency: 'USD', taxQuoteId: 'lock-for-new-total' },
              { status: 409, headers: { 'content-type': 'application/problem+json' } }
            );
          }
          return HttpResponse.json({ orderNumber: 'AT-2026-000777', status: 'placed', paymentClientSecret: null, totalMinor: real.totalMinor + 150, currency: 'USD', shipments: [] }, { status: 201 });
        })
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
      await waitFor(() => expect(screen.getByRole('button', { name: /Place your order/ })).toBeEnabled());
      await user.click(screen.getByRole('button', { name: /Place your order/ }));

      const box = await screen.findByTestId('checkout-total-changed');
      expect(box).toHaveTextContent('Your total changed');
      expect(box).toHaveTextContent('Nothing has been charged.');
      expect(box.textContent).toContain(`$${((real.totalMinor + 150) / 100).toFixed(2)}`);
      expect(screen.getByRole('button', { name: 'Place your order' })).toBeDisabled();
      // the first attempt carried the summary's lock
      expect(bodies[0]?.lock).toBe(real.taxQuoteId);

      await user.click(within(box).getByRole('button', { name: /Place order for \$/ }));
      await waitFor(() => expect(bodies).toHaveLength(2));
      expect(bodies[1]?.key).not.toBe(bodies[0]?.key);
      expect(bodies[1]?.lock).toBeTruthy();
    });
  });
});
