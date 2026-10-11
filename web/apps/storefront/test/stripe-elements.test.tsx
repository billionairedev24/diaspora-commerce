/* Stripe Elements in checkout (S282) against a fake window.Stripe: the storefront's real Elements code (deferred
   intent, Payment Element, Express Checkout Element, confirmPayment) with the mock BFF behind it. */
import { mockServer } from '@afrotransact/api-client/mocks/node';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { CheckoutComplete } from '@/components/checkout/CheckoutComplete';
import { CheckoutView } from '@/components/checkout/CheckoutView';
import {
  deferredIntentOptions,
  type ElementsOptions,
  type PaymentIntentResult,
  resetStripe,
  stripeAppearance,
  type StripeElementChange,
  type StripeInstance,
  stripePaymentMethodTypes
} from '@/lib/payments/stripe';

import { addCookie, installMockBff, signInDemo } from './mockBff';
import { renderWithProviders } from './render';

const nav = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), params: new URLSearchParams() }));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: nav.push, replace: nav.replace, refresh: vi.fn(), back: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => '/checkout',
  useSearchParams: () => nav.params
}));

type Handler = (e: StripeElementChange & Record<string, unknown>) => void;

interface FakeElements {
  options: ElementsOptions;
  created: string[];
  handlers: Record<string, Record<string, Handler>>;
  updates: Record<string, unknown>[];
}

/** A fake Stripe.js: records every call in order and lets a test decide submit / confirm results. */
function fakeStripe() {
  const log: string[] = [];
  const elements: FakeElements[] = [];
  const confirms: Parameters<StripeInstance['confirmPayment']>[0][] = [];
  const results: { submit: { error?: { type?: string; message?: string } }; confirm: PaymentIntentResult } = {
    submit: {},
    confirm: { paymentIntent: { id: 'pi_1', status: 'requires_capture' } }
  };
  const factory = vi.fn((key: string): StripeInstance => {
    log.push(`Stripe(${key})`);
    return {
      elements: (options) => {
        const rec: FakeElements = { options, created: [], handlers: {}, updates: [] };
        elements.push(rec);
        return {
          create: (type) => {
            rec.created.push(type);
            const on: Record<string, Handler> = {};
            rec.handlers[type] = on;
            return {
              mount: (el: HTMLElement) => {
                el.setAttribute('data-fake-stripe', type);
                setTimeout(() => act(() => on.ready?.({})), 0);
              },
              unmount: () => undefined,
              on: (event: string, fn: Handler) => {
                on[event] = fn;
              }
            };
          },
          submit: async () => {
            log.push(`submit:${rec.created.join('+')}`);
            return results.submit;
          },
          update: (u) => {
            rec.updates.push(u);
          }
        };
      },
      confirmPayment: async (o) => {
        log.push('confirmPayment');
        confirms.push(o);
        return results.confirm;
      },
      confirmSetup: async () => ({})
    };
  });
  const payment = () => elements.find((e) => e.created.includes('payment'));
  const express = () => elements.find((e) => e.created.includes('expressCheckout'));
  return { factory, log, elements, confirms, results, payment, express };
}

const TOKENS: Record<string, string> = {
  '--at-color-action-primary': '#FFD400',
  '--at-color-text': '#1F1F1F',
  '--at-color-danger': '#C62828',
  '--at-color-action-secondary': '#1E7B3C',
  '--at-color-link': '#17602F',
  '--at-font-sans': '"Figtree", ui-sans-serif, system-ui',
  '--at-radius-sm': '6px'
};

const cents = (text: string) => Math.round(Number(text.replace(/[^0-9.]/g, '')) * 100);

describe('Stripe Appearance and deferred-intent options', () => {
  afterEach(() => Object.keys(TOKENS).forEach((k) => document.documentElement.style.removeProperty(k)));

  it('reads the design tokens at runtime: gold primary, Figtree, radius, colours, designed tabs', () => {
    Object.entries(TOKENS).forEach(([k, v]) => document.documentElement.style.setProperty(k, v));
    const a = stripeAppearance();
    expect(a.variables).toMatchObject({ colorPrimary: '#FFD400', colorText: '#1F1F1F', colorDanger: '#C62828', borderRadius: '6px' });
    expect(a.variables.fontFamily).toContain('Figtree');
    expect(a.rules['.Tab--selected']).toMatchObject({ borderColor: '#1E7B3C', color: '#17602F' });
  });

  it('leaves Stripe defaults when a token is missing (no hard-coded colours)', () => {
    expect(stripeAppearance().variables.colorPrimary).toBeUndefined();
  });

  it('builds manual-capture deferred options, saving and pre-order restrictions like orders does', () => {
    expect(deferredIntentOptions({ amountMinor: 4599, currency: 'USD' })).toMatchObject({ mode: 'payment', amount: 4599, currency: 'usd', captureMethod: 'manual' });
    expect(deferredIntentOptions({ amountMinor: 1, currency: 'usd' })).not.toHaveProperty('setupFutureUsage');
    expect(deferredIntentOptions({ amountMinor: 1, currency: 'usd', save: true }).setupFutureUsage).toBe('off_session');
    expect(stripePaymentMethodTypes([])).toBeUndefined();
    expect(stripePaymentMethodTypes([['CARD', 'WALLET', 'ACH']])).toBeUndefined();
    expect(stripePaymentMethodTypes([['CARD', 'WALLET', 'ACH'], ['CARD', 'WALLET']])).toEqual(['card', 'link']);
    expect(stripePaymentMethodTypes([['CARD']])).toEqual(['card']);
    expect(stripePaymentMethodTypes([['ACH']])).toBeUndefined();
  });
});

describe('checkout with Stripe Elements (fake Stripe.js)', () => {
  installMockBff();
  let stripe: ReturnType<typeof fakeStripe>;
  const placed: Record<string, unknown>[] = [];

  beforeEach(() => {
    resetStripe();
    stripe = fakeStripe();
    window.Stripe = stripe.factory;
    nav.push.mockReset();
    nav.replace.mockReset();
    placed.length = 0;
    mockServer.events.on('request:start', ({ request }) => {
      if (request.method === 'POST' && new URL(request.url).pathname.endsWith('/v1/checkout/orders')) {
        stripe.log.push('placeOrder');
        void request.clone().json().then((b: Record<string, unknown>) => placed.push(b));
      }
    });
  });
  afterEach(() => {
    mockServer.events.removeAllListeners();
    delete window.Stripe;
  });

  async function openPaymentStep() {
    const api = await signInDemo();
    const jollof = await api.getProduct('AT000000P2');
    await api.addToCart({ offerId: jollof.buyBox?.offerId ?? '', quantity: 2 });
    addCookie('at_mock_stripe_pk=pk_test_fake');
    const user = userEvent.setup();
    renderWithProviders(<CheckoutView />);
    await user.click(await screen.findByRole('button', { name: 'Deliver to this address' }));
    await screen.findAllByTestId('checkout-payment');
    return user;
  }

  const total = () => cents(screen.getByText('Order total').nextElementSibling?.textContent ?? '');

  async function chooseNewMethod(user: ReturnType<typeof userEvent.setup>) {
    await user.click(screen.getByRole('radio', { name: /Use a new payment method/ }));
    await waitFor(() => expect(stripe.payment()).toBeDefined());
    const el = await screen.findByTestId('stripe-payment-element');
    await waitFor(() => expect(el).toHaveAttribute('aria-busy', 'false'));
    act(() => stripe.payment()?.handlers.payment?.change?.({ complete: true, value: { type: 'card' } }));
  }

  it('creates the Payment Element in deferred mode with the cart total, currency and manual capture; no mock form', async () => {
    const user = await openPaymentStep();
    expect(screen.queryByTestId('stripe-dev-notice')).not.toBeInTheDocument();
    await chooseNewMethod(user);
    expect(stripe.factory).toHaveBeenCalledWith('pk_test_fake');
    const opts = stripe.payment()?.options;
    expect(opts).toMatchObject({ mode: 'payment', amount: total(), currency: 'usd', captureMethod: 'manual' });
    expect(opts).not.toHaveProperty('setupFutureUsage');
    expect(opts).not.toHaveProperty('clientSecret');
    expect(screen.queryByTestId('mock-payment-element')).not.toBeInTheDocument();

    // "Save for future purchases" → setupFutureUsage off_session on the same Elements (matches the PaymentIntent).
    await user.click(screen.getByRole('checkbox', { name: /Save for future purchases/ }));
    expect(stripe.payment()?.updates).toContainEqual({ setupFutureUsage: 'off_session' });
    await user.click(screen.getByRole('checkbox', { name: /Save for future purchases/ }));
    expect(stripe.payment()?.updates).toContainEqual({ setupFutureUsage: null });
  });

  it('keeps the amount current when delivery selections change', async () => {
    const user = await openPaymentStep();
    await chooseNewMethod(user);
    const before = total();
    await user.click(screen.getByRole('button', { name: 'Use this payment method' }));
    const pkg = await screen.findByTestId('checkout-package');
    const radios = within(pkg).getAllByRole('radio', { name: /AfroTransact Delivery|Express|Standard|Pick up/ });
    for (const r of radios) {
      await user.click(r);
      if (total() !== before) break;
    }
    expect(total()).not.toBe(before);
    expect(stripe.payment()?.updates).toContainEqual({ amount: total() });
    expect(stripe.express()?.updates).toContainEqual({ amount: total() });
  });

  it('places the order in Stripe’s order: elements.submit(), then the order, then confirmPayment with the client secret', async () => {
    const user = await openPaymentStep();
    await chooseNewMethod(user);
    await user.click(screen.getByRole('checkbox', { name: /Save for future purchases/ }));
    await user.click(screen.getByRole('button', { name: 'Use this payment method' }));
    expect(await screen.findByTestId('checkout-new-method-summary')).toHaveTextContent('Card');
    await user.click(screen.getByRole('button', { name: 'Place your order' }));
    await waitFor(() => expect(nav.push).toHaveBeenCalledWith(expect.stringMatching(/^\/orders\?placed=AT-2026-/)), { timeout: 5000 });

    const order = stripe.log.filter((l) => l === 'placeOrder' || l === 'confirmPayment' || l.startsWith('submit:payment'));
    expect(order).toEqual(['submit:payment', 'submit:payment', 'placeOrder', 'confirmPayment']);
    expect(placed[0]).toMatchObject({ newPaymentMethod: true, savePaymentMethod: true });
    expect(placed[0]).not.toHaveProperty('paymentMethodId');
    const confirm = stripe.confirms[0];
    expect(confirm?.clientSecret).toMatch(/^pi_mock_\d+_secret_mock$/);
    expect(confirm?.elements).toBeDefined();
    expect(confirm?.redirect).toBe('if_required');
    expect(String(confirm?.confirmParams?.return_url)).toMatch(/\/checkout\/complete\?order=AT-2026-/);
  });

  it('shows a decline from Stripe next to the Payment Element and does not continue the order', async () => {
    const user = await openPaymentStep();
    await chooseNewMethod(user);
    await user.click(screen.getByRole('button', { name: 'Use this payment method' }));
    stripe.results.confirm = { error: { type: 'card_error', code: 'card_declined', message: 'Your card was declined.' } };
    await user.click(await screen.findByRole('button', { name: 'Place your order' }));
    expect(await screen.findByTestId('payment-element-error')).toHaveTextContent('Your card was declined.');
    expect(screen.getByTestId('payment-element-error')).toBeVisible();
    expect(nav.push).not.toHaveBeenCalled();
  });

  it('does not create an order when elements.submit() fails', async () => {
    const user = await openPaymentStep();
    await chooseNewMethod(user);
    await user.click(screen.getByRole('button', { name: 'Use this payment method' }));
    await screen.findByTestId('checkout-package');
    stripe.results.submit = { error: { type: 'validation_error', message: 'Your card number is incomplete.' } };
    await user.click(screen.getByRole('button', { name: 'Place your order' }));
    expect(await screen.findByText('Your card number is incomplete.')).toBeInTheDocument();
    expect(stripe.log).not.toContain('placeOrder');
    expect(stripe.log).not.toContain('confirmPayment');
  });

  it('keeps the saved-method path: confirmPayment with the PaymentMethod id, no Payment Element', async () => {
    const user = await openPaymentStep();
    await user.click(screen.getByRole('button', { name: 'Use this payment method' }));
    await user.click(await screen.findByRole('button', { name: 'Place your order' }));
    await waitFor(() => expect(nav.push).toHaveBeenCalled(), { timeout: 5000 });
    expect(stripe.payment()).toBeUndefined();
    expect(placed[0]?.paymentMethodId).toMatch(/^pm_/);
    expect(placed[0]).not.toHaveProperty('newPaymentMethod');
    expect(stripe.confirms[0]?.elements).toBeUndefined();
    expect(stripe.confirms[0]?.confirmParams?.payment_method).toBe(placed[0]?.paymentMethodId);
  });

  it('wires Express Checkout: wallet confirm → submit → order with the wallet → confirmPayment with its elements', async () => {
    await openPaymentStep();
    await waitFor(() => expect(stripe.express()).toBeDefined());
    expect(stripe.express()?.options).toMatchObject({ mode: 'payment', amount: total(), currency: 'usd', captureMethod: 'manual' });
    await act(async () => stripe.express()?.handlers.expressCheckout?.confirm?.({ expressPaymentType: 'apple_pay' }));
    await waitFor(() => expect(nav.push).toHaveBeenCalledWith(expect.stringMatching(/^\/orders\?placed=/)), { timeout: 5000 });
    expect(stripe.log.filter((l) => l === 'placeOrder' || l === 'confirmPayment' || l.startsWith('submit:'))).toEqual(['submit:expressCheckout', 'placeOrder', 'confirmPayment']);
    expect(placed[0]).toMatchObject({ wallet: 'apple_pay' });
    expect(stripe.confirms[0]?.elements).toBeDefined();
  });
});

describe('dev-only stand-in without a Stripe key', () => {
  installMockBff();
  beforeEach(() => {
    resetStripe();
    delete window.Stripe;
    nav.push.mockReset();
  });

  it('labels the stand-in form and never loads Stripe.js', async () => {
    const api = await signInDemo();
    const jollof = await api.getProduct('AT000000P2');
    await api.addToCart({ offerId: jollof.buyBox?.offerId ?? '', quantity: 1 });
    const user = userEvent.setup();
    renderWithProviders(<CheckoutView />);
    await user.click(await screen.findByRole('button', { name: 'Deliver to this address' }));
    await user.click(await screen.findByRole('radio', { name: /Use a new payment method/ }));
    expect(screen.getByTestId('stripe-dev-notice')).toHaveTextContent('Stripe test mode is not configured');
    expect(screen.getByTestId('stripe-dev-notice')).toHaveTextContent('STRIPE_PUBLISHABLE_KEY and STRIPE_SECRET_KEY');
    expect(document.querySelector('script[src*="js.stripe.com"]')).toBeNull();
    await user.type(screen.getByLabelText('Card number'), '4000 0000 0000 9995');
    await user.type(screen.getByLabelText('Expiration'), '12 / 34');
    await user.type(screen.getByLabelText('Security code'), '123');
    await user.type(screen.getByLabelText('ZIP'), '77002');
    await user.click(screen.getByRole('button', { name: 'Use this payment method' }));
    await user.click(await screen.findByRole('button', { name: 'Place your order' }));
    expect(await screen.findByTestId('payment-element-error')).toHaveTextContent('Your card was declined.');
    expect(nav.push).not.toHaveBeenCalled();
  });
});

describe('return from a Stripe redirect (3-D Secure, Cash App Pay, Affirm)', () => {
  installMockBff();
  beforeEach(() => {
    nav.replace.mockReset();
  });

  it('confirms the payment through the BFF and opens the placed order', async () => {
    const api = await signInDemo();
    const jollof = await api.getProduct('AT000000P2');
    await api.addToCart({ offerId: jollof.buyBox?.offerId ?? '', quantity: 1 });
    const summary = await api.getCheckoutSummary();
    const pkg = summary.packages[0];
    const conf = await api.placeOrder(
      { addressId: summary.addresses[0]?.id ?? '', newPaymentMethod: true, deliverySelections: [{ packageId: pkg?.packageId ?? '', option: pkg?.deliveryOptions[0]?.key ?? 'carrier' }] },
      'return-test-key-000001'
    );
    expect(conf.status).toBe('awaiting_payment');
    nav.params = new URLSearchParams({ order: conf.orderNumber, payment_intent: 'pi_mock_1', redirect_status: 'succeeded' });
    renderWithProviders(<CheckoutComplete pollMs={10} />);
    expect(screen.getByText('Checking your payment with Stripe…')).toBeInTheDocument();
    await waitFor(() => expect(nav.replace).toHaveBeenCalledWith(`/orders?placed=${conf.orderNumber}`));
    expect((await api.getCheckoutStatus(conf.orderNumber)).status).toBe('placed');
  });

  it('sends a failed redirect back to checkout without confirming anything', async () => {
    nav.params = new URLSearchParams({ order: 'AT-2026-200001', redirect_status: 'failed' });
    renderWithProviders(<CheckoutComplete pollMs={10} />);
    expect(await screen.findByText(/nothing was charged/)).toHaveAttribute('role', 'alert');
    expect(screen.getByRole('link', { name: 'Back to checkout' })).toHaveAttribute('href', '/checkout');
    expect(nav.replace).not.toHaveBeenCalled();
  });
});
