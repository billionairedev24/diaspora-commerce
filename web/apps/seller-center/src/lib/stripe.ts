'use client';

/* Stripe in the application wizard, without an npm dependency (like the storefront): the official scripts that
   @stripe/connect-js and @stripe/stripe-js load, plus the small typed surface the wizard uses.
   - Connect.js (connect-js.stripe.com): Stripe's embedded components inside Seller Center (no Stripe-hosted page, no
     Stripe login). Seller accounts are Stripe-collected, so these components collect everything Stripe needs:
     account onboarding (identity, tax ID and the payout bank account), account management ("Change bank account")
     and the notification banner (what Stripe needs later, in Account health);
   - Stripe.js (js.stripe.com): the Card Element that tokenises a paid plan's card into a pm_… (only brand, last 4
     and expiry reach AfroTransact).
   Without a publishable key (local stack without Stripe, mock BFF) the wizard shows its labelled local stand-ins. */

const CONNECT_JS = 'https://connect-js.stripe.com/v1.0/connect.js';
const STRIPE_JS = 'https://js.stripe.com/v3/';

export const hasStripeKey = (publishableKey: string | null | undefined): boolean => !!publishableKey && !publishableKey.startsWith('pk_test_mock');

/** An embedded Connect component (a custom element). */
export interface ConnectElement extends HTMLElement {
  setOnExit?(listener: () => void): void;
  setCollectionOptions?(options: { fields: 'currently_due' | 'eventually_due'; futureRequirements?: 'omit' | 'include' }): void;
}

/** The embedded components Seller Center mounts. */
export type ConnectComponent = 'account-onboarding' | 'account-management' | 'notification-banner';

export interface ConnectInstance {
  create(component: ConnectComponent): ConnectElement;
  logout?(): Promise<void>;
}

interface ConnectInitParams {
  publishableKey: string;
  fetchClientSecret: () => Promise<string>;
  appearance?: { variables?: Record<string, string> };
}

export interface CardElement {
  mount(target: HTMLElement): void;
  destroy(): void;
  on(event: 'change', handler: (e: { complete?: boolean; error?: { message?: string } }) => void): void;
}

export interface PaymentMethodResult {
  error?: { message?: string };
  paymentMethod?: {
    id: string;
    card?: {
      brand?: string;
      last4?: string;
      exp_month?: number;
      exp_year?: number;
    };
  };
}

export interface StripeCardInstance {
  elements(options?: { fonts?: { cssSrc: string }[] }): {
    create(type: 'card', options?: Record<string, unknown>): CardElement;
  };
  createPaymentMethod(options: { type: 'card'; card: CardElement; billing_details?: { name?: string } }): Promise<PaymentMethodResult>;
}

declare global {
  interface Window {
    StripeConnect?: { init(params: ConnectInitParams): ConnectInstance };
    Stripe?: (publishableKey: string) => StripeCardInstance;
  }
}

const loaders = new Map<string, Promise<void>>();

function loadScript(src: string, ready: () => boolean): Promise<void> {
  const existing = loaders.get(src);
  if (existing) return existing;
  const p = new Promise<void>((resolve, reject) => {
    if (ready()) {
      resolve();
      return;
    }
    const script = document.createElement('script');
    script.src = src;
    script.async = true;
    script.onload = () => (ready() ? resolve() : reject(new Error(`${src} did not load`)));
    script.onerror = () => {
      loaders.delete(src);
      reject(new Error(`${src} could not be loaded`));
    };
    document.head.appendChild(script);
  });
  loaders.set(src, p);
  return p;
}

/** Brand appearance for the embedded component, from the design tokens (resolved at runtime, no raw colours here). */
function appearance(): { variables: Record<string, string> } {
  const css = getComputedStyle(document.documentElement);
  const v = (name: string) => css.getPropertyValue(name).trim();
  const vars: Record<string, string> = {
    colorPrimary: v('--at-color-action-primary'),
    colorText: v('--at-color-text'),
    colorBackground: v('--at-color-surface'),
    colorDanger: v('--at-color-danger'),
    fontFamily: 'Figtree, system-ui, sans-serif',
    borderRadius: v('--at-radius-sm')
  };
  return {
    variables: Object.fromEntries(Object.entries(vars).filter(([, x]) => x))
  };
}

/** Connect.js with the Account Session's client secret (fetched again by Stripe when the session expires). */
export async function initConnect(publishableKey: string, fetchClientSecret: () => Promise<string>): Promise<ConnectInstance> {
  await loadScript(CONNECT_JS, () => !!window.StripeConnect);
  const connect = window.StripeConnect;
  if (!connect) throw new Error('Stripe Connect did not load');
  return connect.init({
    publishableKey,
    fetchClientSecret,
    appearance: appearance()
  });
}

export async function getStripe(publishableKey: string): Promise<StripeCardInstance> {
  await loadScript(STRIPE_JS, () => !!window.Stripe);
  const factory = window.Stripe;
  if (!factory) throw new Error('Stripe.js did not load');
  return factory(publishableKey);
}
