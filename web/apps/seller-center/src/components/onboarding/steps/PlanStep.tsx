'use client';

import { type OnboardingPlanInput, type OnboardingPlanOffer, sellerQueries } from '@afrotransact/api-client';
import { Button, cx, TextField } from '@afrotransact/ui';
import { Check, CreditCard, Flask, LockSimple } from '@phosphor-icons/react/ssr';
import { useQuery } from '@tanstack/react-query';
import { type Ref, useEffect, useImperativeHandle, useRef, useState } from 'react';

import { ErrorState, Spinner } from '@/components/ui/States';
import { sellerApi } from '@/lib/api';
import { fieldId } from '@/lib/onboarding-form';
import { useSeller } from '@/lib/session';
import { type CardElement, getStripe, hasStripeKey, type StripeCardInstance } from '@/lib/stripe';

import { StepErrors, type StepProps, useStepForm } from '../WizardKit';

type Cadence = OnboardingPlanInput['cadence'];
const CADENCES: { value: Cadence; label: string; save: string }[] = [
  { value: 'MONTHLY', label: 'Monthly', save: '' },
  { value: 'QUARTERLY', label: 'Quarterly', save: 'Save 5%' },
  { value: 'ANNUAL', label: 'Annually', save: '2 months free' }
];

interface TokenisedCard {
  paymentMethodId: string;
  brand: string;
  last4: string;
  expMonth: number;
  expYear: number;
}

interface CardHandle {
  tokenise(): Promise<TokenisedCard | string>;
}

/** Luhn check of a card number. */
export function luhn(raw: string): boolean {
  const n = raw.replace(/\D/g, '');
  if (n.length < 13) return false;
  let sum = 0;
  n.split('')
    .reverse()
    .forEach((d, i) => {
      let x = Number(d);
      if (i % 2) {
        x *= 2;
        if (x > 9) x -= 9;
      }
      sum += x;
    });
  return sum % 10 === 0;
}

const brandOf = (n: string): string =>
  /^4/.test(n) ? 'visa' : /^(5[1-5]|2[2-7])/.test(n) ? 'mastercard' : /^3[47]/.test(n) ? 'amex' : /^6(011|5)/.test(n) ? 'discover' : '';

/**
 * Local stand-in card form (no Stripe key: local stack or mock BFF), clearly labelled; the "payment method" it makes
 * is pm_local_…, which only the local Stripe stand-in accepts.
 */
function LocalTestCard({ ref }: { ref: Ref<CardHandle> }) {
  const [number, setNumber] = useState('');
  const [exp, setExp] = useState('');
  const [cvc, setCvc] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  useImperativeHandle(ref, () => ({
    tokenise: () => {
      const n = number.replace(/\D/g, '');
      const m = /^(\d{2})\/(\d{2})$/.exec(exp);
      const e: Record<string, string> = {};
      if (!luhn(n) || !brandOf(n)) e.cardNumber = 'Card number is invalid';
      const month = m ? Number(m[1]) : 0;
      const year = m ? 2000 + Number(m[2]) : 0;
      const now = new Date();
      if (!m || month < 1 || month > 12 || year * 12 + month < now.getFullYear() * 12 + now.getMonth() + 1) e.cardExpiry = 'Invalid or expired';
      if (!/^\d{3,4}$/.test(cvc)) e.cardCvc = '3 or 4 digits';
      setErrors(e);
      if (Object.keys(e).length) return Promise.resolve(Object.values(e)[0] ?? 'Check the card');
      return Promise.resolve({
        paymentMethodId: `pm_local_${brandOf(n)}_${n.slice(-4)}`,
        brand: brandOf(n),
        last4: n.slice(-4),
        expMonth: month,
        expYear: year
      });
    }
  }));
  return (
    <div className="flex flex-col gap-2 rounded-md border border-dashed border-gold-500 bg-gold-50 p-4" role="group" aria-label="Local test card">
      <p className="m-0 inline-flex items-center gap-2 text-sm">
        <Flask size={18} aria-hidden />
        <span>
          <strong>Local test card.</strong> Stripe isn’t configured on this machine; nothing is charged. Use 4242 4242 4242 4242.
        </span>
      </p>
      <div className="grid grid-cols-1 gap-x-4 md:grid-cols-12">
        <TextField
          id={fieldId('cardNumber')}
          className="md:col-span-6"
          label="Card number"
          inputMode="numeric"
          autoComplete="cc-number"
          value={number}
          maxLength={23}
          onChange={(e) => setNumber(e.target.value.replace(/[^\d ]/g, ''))}
          error={errors.cardNumber}
        />
        <TextField
          id={fieldId('cardExpiry')}
          className="md:col-span-3"
          label="Expiry"
          placeholder="MM/YY"
          autoComplete="cc-exp"
          value={exp}
          maxLength={5}
          onChange={(e) => {
            const d = e.target.value.replace(/\D/g, '').slice(0, 4);
            setExp(d.length > 2 ? `${d.slice(0, 2)}/${d.slice(2)}` : d);
          }}
          error={errors.cardExpiry}
        />
        <TextField
          id={fieldId('cardCvc')}
          className="md:col-span-3"
          label="Security code"
          type="password"
          inputMode="numeric"
          autoComplete="cc-csc"
          value={cvc}
          maxLength={4}
          onChange={(e) => setCvc(e.target.value.replace(/\D/g, ''))}
          error={errors.cardCvc}
        />
      </div>
    </div>
  );
}

/** Stripe's Card Element: the card is tokenised by Stripe.js into a pm_… (AfroTransact keeps brand, last 4 and expiry). */
function StripeCard({ ref, publishableKey, holder }: { ref: Ref<CardHandle>; publishableKey: string; holder: string }) {
  const box = useRef<HTMLDivElement>(null);
  const stripe = useRef<{ s: StripeCardInstance; card: CardElement } | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let cancelled = false;
    getStripe(publishableKey)
      .then((s) => {
        if (cancelled || !box.current) return;
        const card = s
          .elements({
            fonts: [
              {
                cssSrc: 'https://fonts.googleapis.com/css2?family=Figtree:wght@400;600&display=swap'
              }
            ]
          })
          .create('card', { hidePostalCode: false });
        card.mount(box.current);
        card.on('change', (e) => setError(e.error?.message ?? null));
        stripe.current = { s, card };
      })
      .catch(() => setError('The card form couldn’t load. Check your connection and try again.'));
    return () => {
      cancelled = true;
      stripe.current?.card.destroy();
      stripe.current = null;
    };
  }, [publishableKey]);
  useImperativeHandle(ref, () => ({
    tokenise: async () => {
      const cur = stripe.current;
      if (!cur) return 'The card form isn’t ready yet';
      const r = await cur.s.createPaymentMethod({
        type: 'card',
        card: cur.card,
        billing_details: { name: holder }
      });
      const c = r.paymentMethod?.card;
      if (r.error || !r.paymentMethod || !c) return r.error?.message ?? 'Your card couldn’t be added';
      return {
        paymentMethodId: r.paymentMethod.id,
        brand: c.brand ?? '',
        last4: c.last4 ?? '',
        expMonth: c.exp_month ?? 0,
        expYear: c.exp_year ?? 0
      };
    }
  }));
  return (
    <div className="flex flex-col gap-1">
      <span className="text-sm font-semibold">Card</span>
      <div ref={box} id={fieldId('card')} className="rounded-sm border border-border-strong bg-surface px-3 py-3" />
      <span className={cx('min-h-4 text-xs', error ? 'text-danger' : 'text-muted')}>
        {error ?? 'Visa, Mastercard, American Express or Discover · secured by Stripe'}
      </span>
    </div>
  );
}

const priceOf = (p: OnboardingPlanOffer, c: Cadence) => p.prices.find((x) => x.cadence === c) ?? p.prices[0];

/** Step 6 "Plan": choose a subscription (Stripe Billing, free trial) and how it is paid; the fee summary as designed. */
export function PlanStep({ status, ref }: StepProps) {
  const { orgId } = useSeller();
  const api = sellerApi();
  const plans = useQuery(sellerQueries.onboardingPlans(api, orgId));
  const form = status.form;
  const disabled = !form.editable;
  const saved = form.plan;
  const f = useStepForm(() => ({
    planCode: saved.planCode ?? '',
    cadence: (saved.cadence ?? 'MONTHLY') as Cadence
  }));
  const [newCard, setNewCard] = useState(!saved.cardLast4);
  const card = useRef<CardHandle>(null);
  const plan = plans.data?.plans.find((p) => p.code === f.values.planCode) ?? null;
  const paid = !!plan && plan.monthlyCents > 0;
  // The saved card belongs to the saved plan: re-saving would drop its payment method, so it is kept by not saving.
  const unchanged = saved.planCode === f.values.planCode && saved.cadence === f.values.cadence;
  const cardKept = !newCard && !!saved.cardLast4 && unchanged;
  const holder = form.details.legalName ?? [form.details.legalFirstName, form.details.legalLastName].filter(Boolean).join(' ');

  useImperativeHandle(ref, () => ({
    save: async (mode) => {
      const v = f.values;
      if (!v.planCode) {
        if (mode === 'draft') return true;
        f.setErrors({ planCode: 'Choose a plan' });
        f.setSubmitCount((n) => n + 1);
        return false;
      }
      let body: OnboardingPlanInput = {
        planCode: v.planCode,
        cadence: v.cadence
      };
      if (paid && cardKept) return true;
      if (paid) {
        if (mode === 'draft')
          return f.run(
            mode,
            () => ({}),
            () => api.saveOnboardingPlan(body)
          );
        const t = await card.current?.tokenise();
        if (!t || typeof t === 'string') {
          f.setErrors({ card: t ?? 'Add a card for this plan' });
          f.setSubmitCount((n) => n + 1);
          return false;
        }
        body = {
          ...body,
          paymentMethodKind: 'CARD',
          stripePaymentMethodId: t.paymentMethodId,
          cardBrand: t.brand,
          cardLast4: t.last4,
          cardExpMonth: t.expMonth,
          cardExpYear: t.expYear
        };
      }
      const ok = await f.run(
        mode,
        () => ({}),
        () => api.saveOnboardingPlan(body)
      );
      if (ok && paid) setNewCard(false);
      return ok;
    }
  }));

  if (plans.isPending) return <Spinner />;
  if (plans.isError) return <ErrorState error={plans.error} onRetry={() => void plans.refetch()} />;
  const price = plan ? priceOf(plan, f.values.cadence) : null;
  const first =
    saved.planCode === f.values.planCode && saved.cadence === f.values.cadence && saved.firstCharge
      ? saved.firstCharge.sentence
      : (price?.firstCharge?.sentence ?? null);
  const key = plans.data.publishableKey;

  return (
    <div className="flex flex-col gap-4">
      <StepErrors errors={f.errors} submitCount={f.submitCount} />
      <div role="radiogroup" aria-label="Billing frequency" className="inline-flex flex-wrap gap-1 self-start rounded-pill bg-surface-muted p-1">
        {CADENCES.map((c) => (
          <button
            key={c.value}
            type="button"
            role="radio"
            aria-checked={f.values.cadence === c.value}
            disabled={disabled}
            onClick={() => f.patch({ cadence: c.value })}
            className={cx(
              'inline-flex min-h-10 cursor-pointer items-center gap-1.5 rounded-pill border-0 px-4 text-sm font-semibold text-text',
              f.values.cadence === c.value ? 'bg-surface shadow-sm' : 'bg-transparent'
            )}
          >
            {c.label}
            {c.save ? <span className="rounded-pill bg-success-bg px-2 text-2xs font-bold text-success">{c.save}</span> : null}
          </button>
        ))}
      </div>
      <div id={fieldId('planCode')} tabIndex={-1} role="radiogroup" aria-label="Plan" className="grid grid-cols-1 gap-3 outline-none md:grid-cols-3">
        {plans.data.plans.map((p) => {
          const pr = priceOf(p, f.values.cadence);
          const on = p.code === f.values.planCode;
          return (
            <button
              key={p.code}
              type="button"
              role="radio"
              aria-checked={on}
              disabled={disabled}
              onClick={() => f.patch({ planCode: p.code }, ['planCode', 'card'])}
              className={cx(
                'flex cursor-pointer flex-col gap-1.5 rounded-md border-2 p-4 text-left text-text',
                on ? 'border-gold-500 bg-gold-50' : 'border-border bg-surface hover:border-border-strong'
              )}
            >
              {p.tagline ? <span className="text-2xs font-bold tracking-wide text-muted uppercase">{p.tagline}</span> : null}
              <strong className="text-lg">{p.name}</strong>
              <span className="text-2xl font-black">{pr?.perMonthLabel}</span>
              <span className="text-sm">{pr?.billedLabel}</span>
              {pr?.savingLabel ? <span className="text-xs font-semibold text-success">{pr.savingLabel}</span> : null}
              {p.trialDays ? <span className="text-xs text-muted">{p.trialDays}-day free trial</span> : null}
              <ul className="m-0 mt-1 flex list-none flex-col gap-1 p-0 text-xs">
                {p.features.map((x) => (
                  <li key={x} className="flex gap-1.5">
                    <Check size={14} aria-hidden className="mt-0.5 flex-none text-success" />
                    {x}
                  </li>
                ))}
              </ul>
            </button>
          );
        })}
      </div>
      <p className="m-0 min-h-4 text-xs text-danger" aria-live="polite">
        {f.errors.planCode}
      </p>
      {plan ? (
        <section aria-label="Fee summary" className="flex flex-col gap-1 rounded-md bg-surface-muted px-4 py-3 text-sm">
          <strong>
            {plan.name} · {CADENCES.find((c) => c.value === f.values.cadence)?.label}
          </strong>
          <span>{price?.billedLabel}</span>
          <span className="text-muted">{first ?? (paid ? '' : 'No subscription fee. Fees are charged per sale.')}</span>
          <span className="text-muted">{plan.features.find((x) => /fee/i.test(x)) ?? ''}</span>
        </section>
      ) : null}
      {paid ? (
        <div className="flex flex-col gap-2">
          <h3 className="m-0 text-md font-bold">How should we bill you?</h3>
          {cardKept ? (
            <div className="flex flex-wrap items-center gap-3 rounded-md border border-border px-4 py-3">
              <CreditCard size={24} aria-hidden />
              <span className="flex-1 text-sm">
                <strong className="capitalize">{saved.cardBrand}</strong> ••
                {saved.cardLast4} · expires {String(saved.cardExpMonth).padStart(2, '0')}/{String(saved.cardExpYear).slice(-2)}
              </span>
              {!disabled ? (
                <Button size="sm" variant="link" onClick={() => setNewCard(true)}>
                  Use a different card
                </Button>
              ) : null}
            </div>
          ) : hasStripeKey(key) ? (
            <StripeCard ref={card} publishableKey={key} holder={holder} />
          ) : (
            <LocalTestCard ref={card} />
          )}
          <p className="m-0 inline-flex items-center gap-1 text-xs text-muted">
            <LockSimple size={12} aria-hidden /> The subscription starts when your application is approved; you’re not charged before the trial ends.
          </p>
        </div>
      ) : null}
      {f.failure ? (
        <p role="alert" className="m-0 text-sm text-danger">
          {f.failure}
        </p>
      ) : null}
    </div>
  );
}
