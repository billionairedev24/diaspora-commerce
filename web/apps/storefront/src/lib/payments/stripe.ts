'use client';

/* Stripe.js without an npm dependency: the official script from js.stripe.com (what @stripe/stripe-js loads) plus the
   small typed surface the storefront uses (S282, Stripe's recommended deferred-intent integration):
   - checkout: the Payment Element inline, created from the cart (mode 'payment', amount, currency, capture per method,
     setupFutureUsage when the shopper saves the method) before any PaymentIntent exists; Place order runs
     elements.submit() → orders creates the PaymentIntent → stripe.confirmPayment({ elements, clientSecret });
   - Pay Now only: Express Checkout Element (Apple Pay, Google Pay, Link) on the same deferred options;
   - the Payment Element on a SetupIntent for saving methods (Your Payments, booking deposits);
   - PaymentIntent confirmation with a saved method.
   Mock mode (publishable key pk_test_mock…, or none: the local BFF without STRIPE_PUBLISHABLE_KEY) renders the
   storefront's own labelled test form instead; gateway-bff refuses to start without a real key outside local. */

import { fonts } from '@afrotransact/tokens';

export interface StripeError {
  type?: string;
  code?: string;
  message?: string;
}

export interface StripeElementChange {
  complete?: boolean;
  empty?: boolean;
  value?: { type?: string };
  expressPaymentType?: string;
  error?: StripeError;
}

export interface StripeElement {
  mount(target: HTMLElement): void;
  unmount(): void;
  destroy?(): void;
  on(event: string, handler: (e: StripeElementChange & Record<string, unknown>) => void): void;
}

/** Appearance API (https://docs.stripe.com/elements/appearance-api): variables and rules, values resolved. */
export interface StripeAppearance {
  theme: 'stripe' | 'flat' | 'night';
  variables: Record<string, string>;
  rules: Record<string, Record<string, string>>;
}

/** Elements options of the deferred-intent flow (no client secret yet). */
export interface DeferredIntentOptions {
  mode: 'payment';
  /** Minor units; shown in wallets and used for BNPL eligibility. Kept current with elements.update({ amount }). */
  amount: number;
  /** Lower-case ISO currency. */
  currency: string;
  /** Must match the PaymentIntent: payments creates order intents with capture_method automatic_async and manual capture
      per method (cards, Klarna, Affirm, Afterpay, Amazon Pay, Cash App, Link are captured per shipment; US bank accounts,
      which cannot hold funds, are charged on confirmation). A top-level 'manual' would hide ACH from the element. */
  captureMethod: 'automatic_async';
  /** Must match the PaymentIntent: 'off_session' when the shopper saves the method (savePaymentMethod). */
  setupFutureUsage?: 'off_session' | null;
  /** The shopper's Stripe Customer (cus_...); required to show saved methods in the Payment Element. */
  customer?: string;
  /** Only when the intent is restricted (pre-order windows); otherwise automatic payment methods (Dashboard settings). */
  paymentMethodTypes?: string[];
  automaticPaymentMethods?: { enabled: boolean };
  appearance: StripeAppearance;
  fonts: { cssSrc: string }[];
}

export type ElementsOptions = DeferredIntentOptions | { clientSecret: string; appearance: StripeAppearance; fonts: { cssSrc: string }[] };

export interface StripeElements {
  create(type: 'payment' | 'expressCheckout', options?: Record<string, unknown>): StripeElement;
  submit(): Promise<{ error?: StripeError }>;
  update(options: Partial<Pick<DeferredIntentOptions, 'amount' | 'setupFutureUsage' | 'appearance'>>): void;
}

export interface PaymentIntentResult {
  error?: StripeError;
  paymentIntent?: { id?: string; status: string };
}

export interface StripeInstance {
  elements(options: ElementsOptions): StripeElements;
  confirmSetup(options: { elements: StripeElements; redirect: 'if_required'; confirmParams?: Record<string, unknown> }): Promise<{ error?: StripeError; setupIntent?: { status: string } }>;
  confirmPayment(options: {
    clientSecret: string;
    elements?: StripeElements;
    redirect: 'if_required';
    confirmParams?: Record<string, unknown>;
  }): Promise<PaymentIntentResult>;
}

type StripeFactory = (publishableKey: string) => StripeInstance;

declare global {
  interface Window {
    Stripe?: StripeFactory;
  }
}

export const STRIPE_JS = 'https://js.stripe.com/v3/';

/** The platform fonts inside Stripe's iframes (they cannot use the page's self-hosted font files). */
export const STRIPE_FONTS = [{ cssSrc: fonts.googleFontsUrl }];

/** True for the mock BFF's key or no key at all (local without STRIPE_PUBLISHABLE_KEY): the labelled test form. */
export const isMockKey = (publishableKey: string | null | undefined): boolean => !publishableKey || publishableKey.startsWith('pk_test_mock');

let loader: Promise<StripeFactory> | null = null;

function loadStripeJs(): Promise<StripeFactory> {
  if (typeof window === 'undefined') return Promise.reject(new Error('Stripe.js runs in the browser only'));
  if (window.Stripe) return Promise.resolve(window.Stripe);
  loader ??= new Promise<StripeFactory>((resolve, reject) => {
    const script = document.createElement('script');
    script.src = STRIPE_JS;
    script.async = true;
    script.onload = () => (window.Stripe ? resolve(window.Stripe) : reject(new Error('Stripe.js did not load')));
    script.onerror = () => {
      loader = null;
      reject(new Error('Stripe.js could not be loaded'));
    };
    document.head.appendChild(script);
  });
  return loader;
}

const instances = new Map<string, StripeInstance>();

/** One Stripe instance per publishable key. */
export async function getStripe(publishableKey: string): Promise<StripeInstance> {
  const cached = instances.get(publishableKey);
  if (cached) return cached;
  const factory = await loadStripeJs();
  const s = factory(publishableKey);
  instances.set(publishableKey, s);
  return s;
}

/** Forgets loaded instances (tests swap window.Stripe between cases). */
export function resetStripe(): void {
  instances.clear();
  loader = null;
}

/* Appearance variables from the design tokens. Stripe renders in its own iframe, so each value is read from the CSS
   custom properties at runtime (var() references resolved by the browser); a missing token leaves Stripe's default. */
const VARIABLE_TOKENS: Record<string, string> = {
  colorPrimary: '--at-color-action-primary',
  colorBackground: '--at-color-surface',
  colorText: '--at-color-text',
  colorTextSecondary: '--at-color-text-muted',
  colorTextPlaceholder: '--at-color-text-muted',
  colorDanger: '--at-color-danger',
  colorSuccess: '--at-color-success',
  colorIcon: '--at-color-text-muted',
  fontFamily: '--at-font-sans',
  fontSizeBase: '--at-text-md',
  borderRadius: '--at-radius-sm',
  focusOutline: '--at-color-focus'
};

function tokenReader(): (name: string) => string {
  const css = typeof window === 'undefined' ? null : getComputedStyle(document.documentElement);
  return (name: string) => css?.getPropertyValue(name).trim() ?? '';
}

/** Payment / Express Checkout Element appearance: gold primary, the platform font, our radius and colours (Shop design). */
export function stripeAppearance(): StripeAppearance {
  const v = tokenReader();
  const variables: Record<string, string> = { fontWeightNormal: '400', fontWeightMedium: '600', fontWeightBold: '700', spacingUnit: '3px', gridRowSpacing: '12px' };
  for (const [key, token] of Object.entries(VARIABLE_TOKENS)) {
    const value = v(token);
    if (value) variables[key] = value;
  }
  const border = v('--at-color-border-strong');
  const selected = v('--at-color-action-secondary');
  const link = v('--at-color-link');
  const focus = v('--at-color-focus');
  const tint = v('--at-gold-50');
  const rules: Record<string, Record<string, string>> = {
    '.Input': { boxShadow: 'none', ...(border && { borderColor: border }) },
    '.Input:focus': focus ? { borderColor: focus, boxShadow: `0 0 0 1px ${focus}` } : {},
    '.Tab': { boxShadow: 'none', ...(border && { borderColor: border }) },
    // Selected tab as designed: action-secondary border, link-coloured label (pmTabs in the Shop prototype).
    '.Tab--selected': { ...(selected && { borderColor: selected, boxShadow: `0 0 0 1px ${selected}` }), ...(link && { color: link }), ...(tint && { backgroundColor: tint }) },
    '.Tab--selected:focus': selected ? { boxShadow: `0 0 0 2px ${selected}` } : {},
    '.TabIcon--selected': link ? { fill: link } : {},
    '.Label': { fontWeight: '600' },
    // Accordion layout (checkout): flat rows with our border; the selected method gets the action-secondary edge.
    '.AccordionItem': { boxShadow: 'none', paddingTop: '12px', paddingBottom: '12px', ...(border && { borderColor: border }) },
    '.AccordionItem--selected': { ...(selected && { borderColor: selected }), ...(link && { color: link }) },
    '.Block': { boxShadow: 'none', ...(border && { borderColor: border }) }
  };
  return { theme: 'stripe', variables, rules };
}

/* Pre-order windows can restrict payment methods (back office: CARD, WALLET, ACH). The PaymentIntent then carries
   explicit payment_method_types and the Elements must be created with the same list: this mirrors orders'
   PreorderPayments.stripeTypes (every method → automatic; CARD or WALLET → card; WALLET → link; ACH-only → automatic,
   since us_bank_account cannot be captured manually). */
const ALL_METHODS = ['CARD', 'WALLET', 'ACH'] as const;

export function stripePaymentMethodTypes(windows: readonly (readonly string[])[]): string[] | undefined {
  let allowed: Set<string> = new Set(ALL_METHODS);
  for (const w of windows) {
    const upper = new Set(w.map((m) => m.toUpperCase()));
    allowed = new Set([...allowed].filter((m) => upper.has(m)));
  }
  if (ALL_METHODS.every((m) => allowed.has(m))) return undefined;
  const types: string[] = [];
  if (allowed.has('CARD') || allowed.has('WALLET')) types.push('card');
  if (allowed.has('WALLET')) types.push('link');
  if (allowed.has('ACH')) types.push('us_bank_account');
  return types.length ? types : undefined;
}

/** Elements options for an order's PaymentIntent before it exists (deferred intent). */
export function deferredIntentOptions(o: { amountMinor: number; currency: string; save?: boolean; customer?: string; paymentMethodTypes?: string[] }): DeferredIntentOptions {
  return {
    mode: 'payment',
    amount: Math.max(o.amountMinor, 0),
    currency: o.currency.toLowerCase(),
    captureMethod: 'automatic_async',
    ...(o.save ? { setupFutureUsage: 'off_session' as const } : {}),
    ...(o.customer ? { customer: o.customer } : {}),
    ...(o.paymentMethodTypes?.length
      ? { paymentMethodTypes: o.paymentMethodTypes }
      : { automaticPaymentMethods: { enabled: true } }),
    appearance: stripeAppearance(),
    fonts: STRIPE_FONTS
  };
}

export type ConfirmResult = { ok: true; status?: string } | { ok: false; message: string };

const failure = (e: StripeError | undefined, fallback: string): ConfirmResult => ({ ok: false, message: e?.message ?? fallback });

/** PaymentIntent states that mean the shopper's part is done (authorised for manual capture, or settling). */
const PAID = new Set(['requires_capture', 'succeeded', 'processing']);

/**
 * Confirms the order's PaymentIntent with the details collected by the Payment Element (call elements.submit() before
 * the intent is created). Card 3-D Secure opens in Stripe's modal; redirect-based methods (Cash App Pay, Affirm, bank
 * redirects) leave the page for Stripe and come back to returnUrl, which checks the intent through the BFF.
 */
export async function confirmWithElements(stripe: StripeInstance, elements: StripeElements, clientSecret: string, returnUrl: string): Promise<ConfirmResult> {
  try {
    const res = await stripe.confirmPayment({ elements, clientSecret, redirect: 'if_required', confirmParams: { return_url: returnUrl } });
    if (res.error) return failure(res.error, 'Your payment could not be confirmed.');
    const status = res.paymentIntent?.status;
    if (status && !PAID.has(status)) return { ok: false, message: 'Your payment was not completed. Try again or choose another payment method.' };
    return { ok: true, status };
  } catch (e) {
    return { ok: false, message: e instanceof Error ? e.message : 'Payments are unavailable right now.' };
  }
}

/**
 * Confirms a PaymentIntent (order or booking deposit) with a saved method. 3-D Secure challenges open in Stripe's
 * modal; the BFF is then told to continue (orders confirm / bookings payment sync) by the caller.
 */
export async function confirmWithSavedMethod(publishableKey: string, clientSecret: string, paymentMethodId: string, returnUrl?: string): Promise<ConfirmResult> {
  if (isMockKey(publishableKey)) return clientSecret.includes('decline') ? { ok: false, message: 'Your card was declined.' } : { ok: true };
  try {
    const stripe = await getStripe(publishableKey);
    const res = await stripe.confirmPayment({
      clientSecret,
      redirect: 'if_required',
      confirmParams: { payment_method: paymentMethodId, return_url: returnUrl ?? window.location.href }
    });
    if (res.error) return failure(res.error, 'Your payment could not be confirmed.');
    return { ok: true, status: res.paymentIntent?.status };
  } catch (e) {
    return { ok: false, message: e instanceof Error ? e.message : 'Payments are unavailable right now.' };
  }
}
