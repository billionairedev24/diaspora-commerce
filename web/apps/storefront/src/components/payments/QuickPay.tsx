'use client';

import { type Wallet } from '@afrotransact/api-client';
import { AppleGlyph, GooglePlayLogo, Lightning } from '@afrotransact/ui';
import { useEffect, useRef, useState } from 'react';

import { useT } from '@/i18n/t';
import { type ConfirmResult, getStripe, isMockKey, stripeAppearance,type StripeElements, type StripeInstance } from '@/lib/payments/stripe';

import styles from './Payments.module.css';

export interface QuickPayProps {
  publishableKey: string;
  amountMinor: number;
  disabled?: boolean;
  /** Places the order for the wallet and returns the PaymentIntent client secret (or null when placing failed). */
  onPay: (wallet: Wallet) => Promise<string | null>;
  /** Called with the outcome of the wallet confirmation. */
  onConfirmed: (result: ConfirmResult) => void;
}

const WALLETS: Record<string, Wallet> = { apple_pay: 'apple_pay', google_pay: 'google_pay', link: 'link' };

/** Apple Pay, Google Pay and Link (S047): Stripe's Express Checkout Element; mock mode shows the three buttons. */
export function QuickPay({ publishableKey, amountMinor, disabled, onPay, onConfirmed }: QuickPayProps) {
  const t = useT();
  const mount = useRef<HTMLDivElement>(null);
  const handlers = useRef({ onPay, onConfirmed });
  handlers.current = { onPay, onConfirmed };
  const [unavailable, setUnavailable] = useState(false);
  const mock = isMockKey(publishableKey);

  useEffect(() => {
    if (mock || amountMinor <= 0) return;
    let disposed = false;
    let el: { unmount(): void } | null = null;
    getStripe(publishableKey)
      .then((stripe: StripeInstance) => {
        if (disposed || !mount.current) return;
        const elements: StripeElements = stripe.elements({ mode: 'payment', amount: amountMinor, currency: 'usd', appearance: stripeAppearance() });
        const express = elements.create('expressCheckout', { buttonHeight: 44, paymentMethods: { applePay: 'always', googlePay: 'always', link: 'auto' } });
        express.on('confirm', (ev) => {
          void (async () => {
            const wallet = WALLETS[String(ev.expressPaymentType ?? '')] ?? 'link';
            const submitted = await elements.submit();
            if (submitted.error) return handlers.current.onConfirmed({ ok: false, message: submitted.error.message ?? t('pay.walletFailed') });
            const secret = await handlers.current.onPay(wallet);
            if (!secret) return;
            const res = await stripe.confirmPayment({ elements, clientSecret: secret, redirect: 'if_required', confirmParams: { return_url: window.location.href } });
            handlers.current.onConfirmed(res.error ? { ok: false, message: res.error.message ?? t('pay.walletFailed') } : { ok: true });
          })();
        });
        express.mount(mount.current);
        el = express;
      })
      .catch(() => setUnavailable(true));
    return () => {
      disposed = true;
      el?.unmount();
    };
  }, [mock, publishableKey, amountMinor, t]);

  if (!mock) return unavailable ? null : <div ref={mount} className={styles.express} aria-label={t('co.quickPay')} />;

  const pay = async (w: Wallet) => {
    const secret = await onPay(w);
    if (secret) onConfirmed({ ok: true });
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
