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
    const overridden = async (patch: (s: CheckoutSummary) => Partial<CheckoutSummary>) => {
      const api = await signInDemo();
      const jollof = await api.getProduct('AT000000P2');
      await api.addToCart({ offerId: jollof.buyBox?.offerId ?? '', quantity: 1 });
      const real = await api.getCheckoutSummary();
      mockServer.use(http.get('*/v1/checkout/summary', () => HttpResponse.json({ ...real, ...patch(real) })));
      renderWithProviders(<CheckoutView />);
      await screen.findByTestId('checkout-package');
      return within(await screen.findByTestId('order-summary'));
    };
    const NEVER = /\$0\.00|0(\.0+)?\s*%|not taxable|no tax/i;

    it('an address is chosen but the tax lookup failed: "Calculated when you place your order", never $0.00, never the address prompt', async () => {
      const summary = await overridden(() => ({ taxMinor: 0, taxRateLabel: 'Calculated when you place your order', taxEstimateStatus: 'UNAVAILABLE', taxJurisdictionLabel: '', taxLines: [] }));
      const row = summary.getByTestId('checkout-tax-amount');
      expect(row).toHaveTextContent('Calculated when you place your order');
      expect(row.textContent).not.toMatch(NEVER);
      expect(summary.queryByTestId('checkout-tax-place')).not.toBeInTheDocument();
      expect(summary.queryByTestId('checkout-tax-exempt-note')).not.toBeInTheDocument();
      expect(summary.queryByText(/add your address/i)).not.toBeInTheDocument();
    });

    it('no address chosen: the dash and the address prompt', async () => {
      const summary = await overridden(() => ({ taxMinor: 0, taxRateLabel: 'Add your address to see an estimate', taxEstimateStatus: 'NEEDS_ADDRESS', taxJurisdictionLabel: '', taxLines: [] }));
      expect(summary.getByTestId('checkout-tax-amount')).toHaveTextContent('—');
      expect(summary.getByText('Add your address to see an estimate')).toBeInTheDocument();
    });

    it('a line whose decision is not resolved is never tagged or explained as untaxed, whatever else the payload says', async () => {
      const summary = await overridden((s) => {
        const line = s.packages[0]?.lines[0];
        return {
          taxMinor: 0,
          taxRateLabel: 'Calculated when you place your order',
          taxEstimateStatus: 'UNAVAILABLE',
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
      mockServer.use(http.get('*/v1/checkout/summary', () => Ht