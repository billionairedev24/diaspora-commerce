'use client';

import { accountKeys, checkoutKeys, isApiError, type PaymentMethod, PaymentMethodSchema, type SetupIntent } from '@afrotransact/api-client';
import { Bank, Button, CreditCard, Dialog, ShieldCheck, TextField } from '@afrotransact/ui';
import { useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useEffect, useRef, useState } from 'react';

import { FormErrors } from '@/components/common/FormErrors';
import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { BFF_BASE_PATH } from '@/lib/config';
import { getStripe, isMockKey, stripeAppearance,type StripeElements, type StripeInstance } from '@/lib/payments/stripe';

import styles from './Payments.module.css';

/* ── Mock mode (pk_test_mock…): the storefront's own form, confirmed against the mock BFF ───────────── */

const digits = (s: string) => s.replace(/\D/g, '');

export function luhn(num: string): boolean {
  const d = digits(num);
  if (d.length < 12 || d.length > 19) return false;
  let sum = 0;
  for (let i = 0; i < d.length; i += 1) {
    let n = Number(d[d.length - 1 - i]);
    if (i % 2 === 1) n = n * 2 > 9 ? n * 2 - 9 : n * 2;
    sum += n;
  }
  return sum % 10 === 0;
}

export function cardBrand(num: string): string {
  const d = digits(num);
  if (d.startsWith('4')) return 'visa';
  if (/^(5[1-5]|2[2-7])/.test(d)) return 'mastercard';
  if (/^3[47]/.test(d)) return 'amex';
  if (/^6(011|5)/.test(d)) return 'discover';
  return 'card';
}

/** Stripe's test card that is always declined. */
const DECLINED = '4000000000009995';

async function confirmMock(clientSecret: string, body: Record<string, unknown>, failed: string): Promise<PaymentMethod> {
  const res = await fetch(`${window.location.origin}${BFF_BASE_PATH}/v1/mock-stripe/setup-intents/${encodeURIComponent(clientSecret)}/confirm`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(body),
    credentials: 'include'
  });
  if (!res.ok) throw new Error(failed);
  return PaymentMethodSchema.parse(await res.json());
}

function MockMethodForm({ clientSecret, onSaved }: { clientSecret: string; onSaved: (pm: PaymentMethod) => void }) {
  const t = useT();
  const [tab, setTab] = useState<'card' | 'bank'>('card');
  const [f, setF] = useState({ number: '', exp: '', cvc: '', zip: '', bank: '', routing: '', account: '' });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF((x) => ({ ...x, [k]: e.target.value }));

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const next: Record<string, string> = {};
    let body: Record<string, unknown>;
    if (tab === 'card') {
      const [mm, yy] = f.exp.split('/').map((x) => Number(x.trim()));
      const expYear = 2000 + (yy ?? 0);
      const now = new Date();
      if (!luhn(f.number)) next.number = t('pay.cardNumberInvalid');
      else if (digits(f.number) === DECLINED) next.number = t('pay.cardDeclined');
      if (!mm || mm > 12 || !yy || expYear < now.getFullYear() || (expYear === now.getFullYear() && mm < now.getMonth() + 1)) next.exp = t('pay.expInvalid');
      if (!/^\d{3,4}$/.test(f.cvc)) next.cvc = t('pay.cvcInvalid');
      if (!/^\d{5}$/.test(f.zip)) next.zip = t('pay.zipInvalid');
      body = { type: 'card', brand: cardBrand(f.number), last4: digits(f.number).slice(-4), expMonth: mm, expYear };
    } else {
      if (!/^\d{9}$/.test(digits(f.routing))) next.routing = t('pay.routingInvalid');
      if (!/^\d{4,17}$/.test(digits(f.account))) next.account = t('pay.accountInvalid');
      if (!f.bank.trim()) next.bank = t('pay.bankRequired');
      body = { type: 'us_bank_account', bankName: f.bank.trim(), last4: digits(f.account).slice(-4) };
    }
    setErrors(next);
    if (Object.keys(next).length) return;
    setBusy(true);
    try {
      onSaved(await confirmMock(clientSecret, body, t('pay.saveFailed')));
    } catch (err) {
      setErrors({ _: err instanceof Error ? err.message : t('pay.saveFailed') });
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className={styles.form} onSubmit={submit} noValidate data-testid="mock-payment-element">
      <FormErrors errors={errors} ids={{ number: 'pm-number', exp: 'pm-exp', cvc: 'pm-cvc', zip: 'pm-zip', bank: 'pm-bank', routing: 'pm-routing', account: 'pm-account' }} />
      <div className={styles.tabs} role="tablist" aria-label={t('pay.methodType')}>
        <button type="button" role="tab" aria-selected={tab === 'card'} className={styles.tab} onClick={() => setTab('card')}>
          <CreditCard aria-hidden /> {t('pay.tabCard')}
        </button>
        <button type="button" role="tab" aria-selected={tab === 'bank'} className={styles.tab} onClick={() => setTab('bank')}>
          <Bank aria-hidden /> {t('pay.tabBank')}
        </button>
      </div>
      {tab === 'card' ? (
        <>
          <TextField id="pm-number" label={t('pay.cardNumber')} value={f.number} onChange={set('number')} error={errors.number} inputMode="numeric" autoComplete="cc-number" placeholder="1234 1234 1234 1234" />
          <div className={styles.three}>
            <TextField id="pm-exp" label={t('pay.expiry')} value={f.exp} onChange={set('exp')} error={errors.exp} placeholder={t('pay.expiryPlaceholder')} autoComplete="cc-exp" />
            <TextField id="pm-cvc" label={t('pay.cvc')} value={f.cvc} onChange={set('cvc')} error={errors.cvc} inputMode="numeric" autoComplete="cc-csc" maxLength={4} />
            <TextField id="pm-zip" label={t('pay.zip')} value={f.zip} onChange={set('zip')} error={errors.zip} inputMode="numeric" autoComplete="postal-code" maxLength={5} />
          </div>
        </>
      ) : (
        <>
          <TextField id="pm-bank" label={t('pay.bankName')} value={f.bank} onChange={set('bank')} error={errors.bank} />
          <div className={styles.two}>
            <TextField id="pm-routing" label={t('pay.routing')} value={f.routing} onChange={set('routing')} error={errors.routing} inputMode="numeric" maxLength={9} />
            <TextField id="pm-account" label={t('pay.account')} value={f.account} onChange={set('account')} error={errors.account} inputMode="numeric" maxLength={17} />
          </div>
          <p className={styles.fine}>{t('pay.achMandate')}</p>
        </>
      )}
      {errors._ && <p className={styles.error} role="alert">{errors._}</p>}
      <Button type="submit" variant="secondary" loading={busy} block>
        {t('pay.saveMethod')}
      </Button>
      <p className={styles.fine}>{t('pay.testHint')}</p>
    </form>
  );
}

/* ── Stripe Payment Element (SetupIntent from payments) ─────────────────────────────────────────── */

function StripeSetupForm({ setup, onSaved }: { setup: SetupIntent; onSaved: () => void }) {
  const t = useT();
  const mountRef = useRef<HTMLDivElement>(null);
  const stripe = useRef<{ s: StripeInstance; e: StripeElements } | null>(null);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let disposed = false;
    let el: { unmount(): void } | null = null;
    getStripe(setup.publishableKey)
      .then((s) => {
        if (disposed || !mountRef.current) return;
        const e = s.elements({ clientSecret: setup.clientSecret, appearance: stripeAppearance() });
        const payment = e.create('payment', { layout: 'tabs', paymentMethodOrder: ['card', 'us_bank_account', 'cashapp'] });
        payment.mount(mountRef.current);
        payment.on('ready', () => setReady(true));
        el = payment;
        stripe.current = { s, e };
      })
      .catch(() => setError(t('pay.stripeUnavailable')));
    return () => {
      disposed = true;
      el?.unmount();
    };
  }, [setup, t]);

  const submit = async (ev: FormEvent) => {
    ev.preventDefault();
    if (!stripe.current) return;
    setBusy(true);
    setError(null);
    const res = await stripe.current.s.confirmSetup({ elements: stripe.current.e, redirect: 'if_required', confirmParams: { return_url: window.location.href } });
    setBusy(false);
    if (res.error) setError(res.error.message ?? t('pay.saveFailed'));
    else onSaved();
  };

  return (
    <form className={styles.form} onSubmit={(e) => void submit(e)}>
      <div ref={mountRef} className={styles.element} aria-busy={!ready} />
      {error && <p className={styles.error} role="alert">{error}</p>}
      <Button type="submit" variant="secondary" loading={busy} disabled={!ready} block>
        {t('pay.saveMethod')}
      </Button>
    </form>
  );
}

/**
 * Add a payment method (S047, S148): card or US bank account (ACH) through Stripe's Payment Element on a SetupIntent
 * from payments; the method is saved on the shopper's Stripe Customer (AfroTransact keeps brand, last 4, expiry).
 */
export function AddPaymentMethodDialog({ open, onOpenChange, onSaved }: { open: boolean; onOpenChange: (o: boolean) => void; onSaved?: (pm: PaymentMethod | null) => void }) {
  const t = useT();
  const qc = useQueryClient();
  const [setup, setSetup] = useState<SetupIntent | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setSetup(null);
    setError(null);
    browserApi()
      .createSetupIntent(['card', 'us_bank_account'])
      .then(setSetup)
      .catch((e: unknown) => setError(isApiError(e) ? e.message : t('pay.stripeUnavailable')));
  }, [open, t]);

  const done = async (pm: PaymentMethod | null) => {
    await Promise.all([qc.invalidateQueries({ queryKey: accountKeys.paymentMethods() }), qc.invalidateQueries({ queryKey: checkoutKeys.all }), qc.invalidateQueries({ queryKey: accountKeys.me() })]);
    onSaved?.(pm);
    onOpenChange(false);
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={t('pay.addTitle')} width={480}>
      {error && <p className={styles.error} role="alert">{error}</p>}
      {!setup && !error && <p className={styles.fine}>{t('common.loading')}</p>}
      {setup &&
        (isMockKey(setup.publishableKey) ? (
          <MockMethodForm clientSecret={setup.clientSecret} onSaved={(pm) => void done(pm)} />
        ) : (
          <StripeSetupForm setup={setup} onSaved={() => void done(null)} />
        ))}
      <p className={styles.secure}>
        <ShieldCheck aria-hidden /> {t('pay.storedByStripe')}
      </p>
    </Dialog>
  );
}
