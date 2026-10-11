'use client';

import { TextField } from '@afrotransact/ui';
import { type Ref, useEffect, useId, useImperativeHandle, useRef, useState } from 'react';

import { type MessageKey, useT } from '@/i18n/t';
import {
  type ConfirmResult,
  confirmWithElements,
  deferredIntentOptions,
  getStripe,
  isMockKey,
  type StripeElement,
  type StripeElements,
  type StripeInstance
} from '@/lib/payments/stripe';

import { checkMockCard, DECLINED_CARD, digits, type MockCard, THREE_DS_CARD } from './mockCard';
import styles from './Payments.module.css';
import { StripeDevNotice } from './StripeDevNotice';

/** What the checkout does with the inline Payment Element. */
export interface PaymentElementHandle {
  /** elements.submit(): validates and collects the details. Call it before the order (and its PaymentIntent) exists. */
  submit(): Promise<ConfirmResult>;
  /** stripe.confirmPayment({ elements, clientSecret }) for the order's PaymentIntent; 3-D Secure inline, redirect
   *  methods come back to returnUrl. */
  confirm(clientSecret: string, returnUrl: string): Promise<ConfirmResult>;
}

export interface PaymentElementChange {
  complete: boolean;
  /** Nothing entered yet (the shopper may still pay with a saved method instead). */
  empty: boolean;
  /** Stripe payment method type the shopper is on (card, link, cashapp, affirm, us_bank_account, …). */
  type: string | null;
}

export interface PaymentElementFieldProps {
  ref?: Ref<PaymentElementHandle>;
  publishableKey: string;
  /** Order total in minor units; kept current with elements.update({ amount }). */
  amountMinor: number;
  currency: string;
  /** "Save for future purchases": setupFutureUsage off_session (must match the PaymentIntent orders creates). */
  save: boolean;
  /** Restricted types (pre-order windows); undefined = the payment methods enabled in Stripe. */
  paymentMethodTypes?: string[];
  onChange?: (change: PaymentElementChange) => void;
}

/** Confirmation status of the dev-only form when it sends the browser to the return page (the 3-D Secure test card). */
export const REDIRECTED = 'redirected';

/** Payment Element: a compact accordion (card first, then Apple Pay / Google Pay / Link, Cash App Pay, Klarna, Amazon
 *  Pay, Affirm, US bank, as enabled in Stripe). Wallets live here too: there is no separate Express Checkout row. */
const PAYMENT_ELEMENT_OPTIONS = {
  layout: { type: 'accordion', defaultCollapsed: false, radios: true, spacedAccordionItems: false },
  wallets: { applePay: 'auto', googlePay: 'auto' },
  paymentMethodOrder: ['card', 'apple_pay', 'google_pay', 'link', 'cashapp', 'klarna', 'amazon_pay', 'affirm', 'us_bank_account']
};

/**
 * Stripe's Payment Element inline in checkout (deferred intent, S282). The Elements instance is created from the
 * cart total before any PaymentIntent exists; Place order runs submit(), creates the order, then confirm().
 * Without a Stripe key (local only) it renders the labelled stand-in card form.
 */
export function PaymentElementField(props: PaymentElementFieldProps) {
  return isMockKey(props.publishableKey) ? <MockPaymentElement {...props} /> : <StripePaymentElement {...props} />;
}

function StripePaymentElement({ ref, publishableKey, amountMinor, currency, save, paymentMethodTypes, onChange }: PaymentElementFieldProps) {
  const t = useT();
  const mountRef = useRef<HTMLDivElement>(null);
  const stripe = useRef<{ s: StripeInstance; e: StripeElements } | null>(null);
  const latest = useRef({ amountMinor, save, onChange });
  latest.current = { amountMinor, save, onChange };
  const [ready, setReady] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const types = paymentMethodTypes?.join(',') ?? '';

  useEffect(() => {
    let disposed = false;
    let el: StripeElement | null = null;
    setReady(false);
    setError(null);
    getStripe(publishableKey)
      .then((s) => {
        if (disposed || !mountRef.current) return;
        const { amountMinor: amount, save: keep } = latest.current;
        const e = s.elements(deferredIntentOptions({ amountMinor: amount, currency, save: keep, paymentMethodTypes: types ? types.split(',') : undefined }));
        const payment = e.create('payment', PAYMENT_ELEMENT_OPTIONS);
        payment.on('ready', () => setReady(true));
        payment.on('change', (ev) => {
          setError(null);
          latest.current.onChange?.({ complete: !!ev.complete, empty: ev.empty ?? !ev.complete, type: ev.value?.type ?? null });
        });
        payment.on('loaderror', (ev) => setError(ev.error?.message ?? t('pay.stripeUnavailable')));
        payment.mount(mountRef.current);
        el = payment;
        stripe.current = { s, e };
      })
      .catch(() => setError(t('pay.stripeUnavailable')));
    return () => {
      disposed = true;
      el?.unmount();
      stripe.current = null;
    };
  }, [publishableKey, currency, types, t]);

  // Delivery choices change the total; saving changes setup_future_usage. Both update the same Elements instance.
  useEffect(() => {
    stripe.current?.e.update({ amount: Math.max(amountMinor, 0) });
  }, [amountMinor]);
  useEffect(() => {
    stripe.current?.e.update({ setupFutureUsage: save ? 'off_session' : null });
  }, [save]);

  useImperativeHandle(
    ref,
    () => ({
      submit: async () => {
        const cur = stripe.current;
        if (!cur) return { ok: false, message: t('pay.stripeUnavailable') };
        const res = await cur.e.submit();
        if (!res.error) return { ok: true };
        const message = res.error.message ?? t('pay.stripeUnavailable');
        // Stripe shows field problems inside the element; anything else is shown under it.
        if (res.error.type !== 'validation_error') setError(message);
        return { ok: false, message };
      },
      confirm: async (clientSecret, returnUrl) => {
        const cur = stripe.current;
        if (!cur) return { ok: false, message: t('pay.stripeUnavailable') };
        const res = await confirmWithElements(cur.s, cur.e, clientSecret, returnUrl);
        if (!res.ok) setError(res.message);
        return res;
      }
    }),
    [t]
  );

  return (
    <div className={styles.paymentElement}>
      <div ref={mountRef} className={styles.element} aria-busy={!ready} data-testid="stripe-payment-element" />
      {!ready && !error && <p className={styles.fine}>{t('pay.elementLoading')}</p>}
      {error && (
        <p className={styles.error} role="alert" data-testid="payment-element-error">
          {error}
        </p>
      )}
    </div>
  );
}

const EMPTY: MockCard = { number: '', exp: '', cvc: '', zip: '' };

function MockPaymentElement({ ref, onChange }: PaymentElementFieldProps) {
  const t = useT();
  const id = useId();
  const [card, setCard] = useState<MockCard>(EMPTY);
  const [errors, setErrors] = useState<Partial<Record<keyof MockCard, MessageKey>>>({});
  const [error, setError] = useState<string | null>(null);
  const latest = useRef({ card, onChange });
  latest.current = { card, onChange };

  const set = (k: keyof MockCard) => (e: { target: { value: string } }) => {
    const next = { ...latest.current.card, [k]: e.target.value };
    setCard(next);
    setError(null);
    const complete = Object.values(next).every(Boolean) && Object.keys(checkMockCard(next)).length === 0;
    latest.current.onChange?.({ complete, empty: !Object.values(next).some(Boolean), type: 'card' });
  };

  useImperativeHandle(
    ref,
    () => ({
      submit: async () => {
        const found = checkMockCard(latest.current.card);
        setErrors(found);
        const first = Object.values(found)[0];
        return first ? { ok: false, message: t(first) } : { ok: true };
      },
      confirm: async (clientSecret, returnUrl) => {
        const number = digits(latest.current.card.number);
        if (number === DECLINED_CARD) {
          setError(t('pay.cardDeclined'));
          return { ok: false, message: t('pay.cardDeclined') };
        }
        if (number === THREE_DS_CARD) {
          // Like Stripe's redirect: the browser comes back to the return page, which checks the payment with the BFF.
          const pi = clientSecret.split('_secret')[0] ?? clientSecret;
          const sep = returnUrl.includes('?') ? '&' : '?';
          window.location.assign(`${returnUrl}${sep}payment_intent=${encodeURIComponent(pi)}&payment_intent_client_secret=${encodeURIComponent(clientSecret)}&redirect_status=succeeded`);
          return { ok: true, status: REDIRECTED };
        }
        return { ok: true, status: 'requires_capture' };
      }
    }),
    [t]
  );

  const err = (k: keyof MockCard) => (errors[k] ? t(errors[k]) : undefined);
  return (
    <div className={styles.paymentElement} data-testid="mock-payment-element">
      <StripeDevNotice />
      <div className={styles.mockFields}>
        <TextField id={`${id}-number`} label={t('pay.cardNumber')} value={card.number} onChange={set('number')} error={err('number')} inputMode="numeric" autoComplete="cc-number" placeholder="1234 1234 1234 1234" />
        <div className={styles.three}>
          <TextField id={`${id}-exp`} label={t('pay.expiry')} value={card.exp} onChange={set('exp')} error={err('exp')} placeholder={t('pay.expiryPlaceholder')} autoComplete="cc-exp" />
          <TextField id={`${id}-cvc`} label={t('pay.cvc')} value={card.cvc} onChange={set('cvc')} error={err('cvc')} inputMode="numeric" autoComplete="cc-csc" maxLength={4} />
          <TextField id={`${id}-zip`} label={t('pay.zip')} value={card.zip} onChange={set('zip')} error={err('zip')} inputMode="numeric" autoComplete="postal-code" maxLength={5} />
        </div>
      </div>
      {error && (
        <p className={styles.error} role="alert" data-testid="payment-element-error">
          {error}
        </p>
      )}
      <p className={styles.fine}>
        {t('pay.testHint')} {t('pay.testHint3ds')}
      </p>
    </div>
  );
}
