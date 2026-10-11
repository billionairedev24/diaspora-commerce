'use client';

import { type Wallet } from '@afrotransact/api-client';
import { AppleGlyph, GooglePlayLogo, Lightning } from '@afrotransact/ui';
import { useEffect, useRef, useState } from 'react';

import { useT } from '@/i18n/t';
import { type ConfirmResult, confirmWithElements, deferredIntentOptions, getStripe, isMockKey, type StripeElements } from '@/lib/payments/stripe';

import styles from './Payments.module.css';

/** The order placed for a wallet payment: its PaymentIntent client secret and where redirects come back to. */
export interface WalletOrder {
  clientSecret: string;
  returnUrl: string;
}

export interface QuickPayProps {
  publishableKey: string;
  amountMinor: number;
  currency?: string;
  /** Restricted types (pre-order windows), the same list the order's PaymentIntent is created with. */
  paymentMethodTypes?: string[];
  disabled?: boolean;
  /** Places the order for the wallet (or null when placing failed). */
  onPay: (wallet: Wallet) => Promise<WalletOrder | null>;
  /** Called with the outcome of the wallet confirmation. */
  onConfirmed: (result: ConfirmResult) => void;
}

const WALLETS: Record<string, Wallet> = { apple_pay: 'apple_pay', google_pay: 'google_pay', link: 'link' };

/**
 * Apple Pay, Google Pay and Link (S047, S282): Stripe's Express Checkout Element on the deferred-intent options the
 * Payment Element uses (amount, currency, manual capture). On confirm: elements.submit(), the order is placed, then
 * stripe.confirmPayment with the order's client secret. Without a Stripe key (local only) it shows the three buttons.
 */
export function QuickPay({ publishableKey, amountMinor, currency = 'USD', paymentMethodTypes, disabled, onPay, onConfirmed }: QuickPayProps) {
  const t = useT();
  const mount = useRef<HTMLDivElement>(null);
  const handlers = useRef({ onPay, onConfirmed, amountMinor });
  handlers.current = { onPay, onConfirmed, amountMinor };
  const elementsRef = useRef<StripeElements | null>(null);
  const [unavailable, setUnavailable] = useState(false);
  const mock = isMockKey(publishableKey);
  const types = paymentMethodTypes?.join(',') ?? '';

  useEffect(() => {
    if (mock) return;
    let disposed = false;
    let el: { unmount(): void } | null = null;
    getStripe(publishableKey)
      .then((stripe) => {
        if (disposed || !mount.current) return;
        const elements = stripe.elements(deferredIntentOptions({ amountMinor: handlers.current.amountMinor, currency, paymentMethodTypes: types ? types.split(',') : undefined }));
        const express = elements.create('expressCheckout', { buttonHeight: 44, buttonTheme: { applePay: 'black', googlePay: 'black' }, paymentMethods: { applePay: 'always', googlePay: 'always', link: 'auto' } });
        express.on('confirm', (ev) => {
          void (async () => {
            const wallet = WALLETS[String(ev.expressPaymentType ?? '')] ?? 'link';
            const submitted = await elements.submit();
            if (submitted.error) return handlers.current.onConfirmed({ ok: false, message: submitted.error.message ?? t('pay.walletFailed') });
            const order = await handlers.current.onPay(wallet);
            if (!order) return;
            handlers.current.onConfirmed(await confirmWithElements(stripe, elements, order.clientSecret, order.returnUrl));
          })();
        });
        express.mount(mount.current);
        el = express;
        elementsRef.current = elements;
      })
      .catch(() => setUnavailable(true));
    return () => {
      disposed = true;
      el?.unmount();
      elementsRef.current = null;
    };
  }, [mock, publishableKey, currency, types, t]);

  useEffect(() => {
    elementsRef.current?.update({ amount: Math.max(amountMinor, 0) });
  }, [amountMinor]);

  if (!mock) return unavailable ? null : <div ref={mount} className={styles.express} aria-label={t('co.quickPay')} data-testid="stripe-express-checkout" />;

  const pay = async (w: Wallet) => {
    const order = await onPay(w);
    if (order) onConfirmed({ ok: true });
  };
  return (
    <div className={styles.quick} role="group" aria-label={t('co.quickPay')}>
      <button type="button" className={styles.quickBtn} disabled={disabled} onClick={() => void pay('apple_pay')}>
        <AppleGlyph weight="fill" aria-hidden /> Pay
      </button>
      <button type="button" className={styles.quickBtn} disabled={disabled} onClick={() => void pay('google_pay')}>
        <GooglePlayLogo weight="fill" aria-hidden /> Pay
      </button>
      <button type="button" className={`${styles.quickBtn} ${styles.quickLink}`} disabled={disabled} onClick={() => void pay('link')}>
        <Lightning weight="fill" aria-hidden /> Link
      </button>
    </div>
  );
}
