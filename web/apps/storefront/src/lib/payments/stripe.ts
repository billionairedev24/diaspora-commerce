'use client';

/* Stripe.js without an npm dependency: the official script from js.stripe.com (what @stripe/stripe-js loads) plus the
   small typed surface the storefront uses — the Payment Element for saving methods (SetupIntent from payments),
   PaymentIntent confirmation for orders and booking deposits, and the Express Checkout Element (Apple Pay, Google Pay,
   Link). Mock mode (publishable key pk_test_mock…, used with NEXT_PUBLIC_API_MOCKS=1 and in tests) renders the
   storefront's own card form and confirms against the mock BFF instead. */

export interface StripeError {
  type?: string;
  code?: string;
  message?: string;
}

export interface StripeElement {
  mount(target: HTMLElement): void;
  unmount(): void;
  destroy?(): void;
  on(event: string, handler: (e: Record<string, unknown>) => void): void;
}

export interface StripeElements {
  create(type: 'payment' | 'expressCheckout', options?: Record<string, unknown>): StripeElement;
  submit(): Promise<{ error?: StripeError }>;
}

export interface StripeInstance {
  elements(options: Record<string, unknown>): StripeElements;
  confirmSetup(options: { elements: StripeElements; redirect: 'if_required'; confirmParams?: Record<string, unknown> }): Promise<{ error?: StripeError; setupIntent?: { status: string } }>;
  confirmPayment(options: {
    clientSecret: string;
    elements?: StripeElements;
    redirect: 'if_required';
    confirmParams?: Record<string, unknown>;
  }): Promise<{ error?: StripeError; paymentIntent?: { status: string } }>;
}

type StripeFactory = (publishableKey: string) => StripeInstance;

declare global {
  interface Window {
    Stripe?: StripeFactory;
  }
}

export const STRIPE_JS = 'https://js.stripe.com/v3/';

/** True for the mock BFF's key: no Stripe.js, the storefront's own test form and confirmations. */
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

/** Payment Element appearance from the design tokens (Stripe renders in its own iframe, so values are resolved). */
export function stripeAppearance(): Record<string, unknown> {
  const css = typeof window === 'undefined' ? null : getComputedStyle(document.documentElement);
  const v = (name: string, fallback: string) => css?.getPropertyValue(name).trim() || fallback;
  return {
    theme: 'stripe',
    variables: {
      colorPrimary: v('--at-color-action-secondary', 'green'),
      colorText: v('--at-color-text', 'black'),
      colorDanger: v('--at-color-danger', 'red'),
      fontFamily: 'Figtree, system-ui, sans-serif',
      borderRadius: '8px'
    }
  };
}

export type ConfirmResult = { ok: true } | { ok: false; message: string };

const failure = (e: StripeError | undefined, fallback: string): ConfirmResult => ({ ok: false, message: e?.message ?? fallback });

/**
 * Confirms a PaymentIntent (order or booking deposit) with a saved method. 3-D Secure challenges open in Stripe's
 * modal; the BFF is then told to continue (orders confirm / bookings payment sync) by the caller.
 */
export async function confirmWithSavedMethod(publishableKey: string, clientSecret: string, paymentMethodId: string): Promise<ConfirmResult> {
  if (isMockKey(publishableKey)) return clientSecret.includes('decline') ? { ok: false, message: 'Your card was declined.' } : { ok: true };
  try {
    const stripe = await getStripe(publishableKey);
    const res = await stripe.confirmPayment({
      clientSecret,
      redirect: 'if_required',
      confirmParams: { payment_method: paymentMethodId, return_url: window.location.href }
    });
    if (res.error) return failure(res.error, 'Your payment could not be confirmed.');
    return { ok: true };
  } catch (e) {
    return { ok: false, message: e instanceof Error ? e.message : 'Payments are unavailable right now.' };
  }
}
