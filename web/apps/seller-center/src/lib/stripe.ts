'use client';

/* Stripe in the application wizard, without an npm dependency (like the storefront): the official scripts that
   @stripe/connect-js and @stripe/stripe-js load, plus the small typed surface the wizard uses.
   - Connect.js (connect-js.stripe.com): Stripe's embedded components inside Seller Center (no Stripe-hosted page, no
     Stripe login). Seller accounts are Stripe-collected, so these components collect everything Stripe needs:
     account onboarding (identity, tax ID and the payout bank account), account management ("Change bank account")
     and the notification banner (what Stripe needs later, in Account health);
   - Stripe.js (js.stripe.com): the Card Element that tokenises a paid plan's card into a pm_… (only brand, last 4
     and expiry reach AfroTransact), and the Payment Element that adds a card or US bank account on Plan & billing
     (a SetupIntent confirmed in the browser; S292–S297).
   Without a publishable key (local stack without Stripe, mock BFF) the wizard shows its labelled local stand-ins. */

const CONNECT_JS = 'https://connect-js.stripe.com/v1.0/connect.js';
const STRIPE_JS = 'https://js.stripe.com/v3/';

export const hasStripeKey = (publishableKey: string | null | undefined): boolean => !!publishableKey && !publishableKey.startsWith('pk_test_mock');

/** An embedded Connect component (a custom element). */
export interface ConnectElement extends HTMLElement {
  setOnExit?(listener: () => void): void;
  /** The component's own loader is showing (its UI is about to render). */
  setOnLoaderStart?(listener: () => void): void;
  setOnLoadError?(listener: (e: { error: { type?: string; message?: string } }) => void): void;
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
  appearance?: { overlays?: 'dialog' | 'drawer'; variables?: Record<string, string> };
  fonts?: { cssSrc: string }[];
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

/** The Payment Element (Plan & billing › Add payment method). */
export interface PaymentElement {
  mount(target: HTMLElement): void;
  destroy(): void;
  on(event: 'change', handler: (e: { complete?: boolean }) => void): void;
}

export interface StripePaymentElements {
  create(type: 'payment', options?: Record<string, unknown>): PaymentElement;
  submit(): Promise<{ error?: { message?: string } }>;
}

/** A payment method created from the Payment Element: a card or a US bank account. */
export interface ElementsPaymentMethodResult {
  error?: { message?: string };
  paymentMethod?: {
    id: string;
    type: string;
    card?: { brand?: string; last4?: string; exp_month?: number; exp_year?: number };
    us_bank_account?: { bank_name?: string; last4?: string; account_type?: string };
  };
}

export interface SetupConfirmation {
  error?: { message?: string };
  setupIntent?: { id: string; status: string };
}

/** The Stripe.js surface the Payment Element flow uses (deferred intent: the SetupIntent is confirmed with the method). */
export interface StripePaymentInstance {
  elements(options: {
    mode: 'setup';
    currency: string;
    paymentMethodCreation: 'manual';
    appearance?: { variables: Record<string, string> };
  }): StripePaymentElements;
  createPaymentMethod(options: { elements: StripePaymentElements }): Promise<ElementsPaymentMethodResult>;
  confirmSetup(options: {
    clientSecret: string;
    confirmParams: { payment_method: string; return_url: string };
    redirect: 'if_required';
  }): Promise<SetupConfirmation>;
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
export function appearance(): { variables: Record<string, string> } {
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

/**
 * Connect.js appearance for the embedded components, from the same tokens: Stripe's own first button ("Get started", "Add information") is our gold primary pill, the form uses Figtree and
 * our radii, and borders stay as quiet as the rest of the wizard.
 */
function connectAppearance(): { overlays: 'dialog'; variables: Record<string, string> } {
  const css = getComputedStyle(document.documentElement);
  const v = (name: string) => css.getPropertyValue(name).trim();
  const vars: Record<string, string> = {
    fontFamily: 'Figtree, system-ui, sans-serif',
    fontSizeBase: '16px',
    colorPrimary: v('--at-color-text'),
    colorText: v('--at-color-text'),
    colorSecondaryText: v('--at-color-text-muted'),
    colorBackground: v('--at-color-surface'),
    colorBorder: v('--at-color-border'),
    colorDanger: v('--at-color-danger'),
    buttonPrimaryColorBackground: v('--at-color-action-primary'),
    buttonPrimaryColorBorder: v('--at-color-action-primary'),
    buttonPrimaryColorText: v('--at-color-action-primary-text'),
    buttonSecondaryColorBackground: v('--at-color-surface'),
    buttonSecondaryColorBorder: v('--at-color-border-strong'),
    buttonSecondaryColorText: v('--at-color-text'),
    borderRadius: v('--at-radius-md'),
    buttonBorderRadius: v('--at-radius-pill'),
    formBorderRadius: v('--at-radius-sm'),
    badgeBorderRadius: v('--at-radius-pill'),
    overlayBorderRadius: v('--at-radius-lg'),
    spacingUnit: '10px'
  };
  return {
    overlays: 'dialog',
    variables: Object.fromEntries(Object.entries(vars).filter(([, x]) => x))
  };
}

/** Figtree for the components (they render in Stripe's iframe, so they load the font themselves). */
const FONTS = [{ cssSrc: 'https://fonts.googleapis.com/css2?family=Figtree:wght@400;500;600;700&display=swap' }];

/** Connect.js with the Account Session's client secret (fetched again by Stripe when the session expires). */
export async function initConnect(publishableKey: string, fetchClientSecret: () => Promise<string>): Promise<ConnectInstance> {
  await loadScript(CONNECT_JS, () => !!window.StripeConnect);
  const connect = window.StripeConnect;
  if (!connect) throw new Error('Stripe Connect did not load');
  return connect.init({
    publishableKey,
    fetchClientSecret,
    appearance: connectAppearance(),
    fonts: FONTS
  });
}

export async function getStripe(publishableKey: string): Promise<StripeCardInstance> {
  await loadScript(STRIPE_JS, () => !!window.Stripe);
  const factory = window.Stripe;
  if (!factory) throw new Error('Stripe.js did not load');
  return factory(publishableKey);
}

/** Stripe.js for the Payment Element (same script and instance as the Card Element; a wider typed surface). */
export async function getStripePayments(publishableKey: string): Promise<StripePaymentInstance> {
  await loadScript(STRIPE_JS, () => !!window.Stripe);
  const factory = window.Stripe as ((key: string) => unknown) | undefined;
  if (!factory) throw new Error('Stripe.js did not load');
  return factory(publishableKey) as StripePaymentInstance;
}
