'use client';

import { Button, Select, TextField } from '@afrotransact/ui';
import { Bank, CheckCircle, LockSimple } from '@phosphor-icons/react/ssr';
import Link from 'next/link';
import { useImperativeHandle, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { sellerApi } from '@/lib/api';
import { fieldId, payoutValues, toPayoutsInput, validatePayouts } from '@/lib/onboarding-form';
import { onboardingHref } from '@/lib/onboarding-wizard';

import { Cell, FieldGrid, StepErrors, type StepProps, useStepForm } from '../WizardKit';

const digits = (v: string, max: number) => v.replace(/\D/g, '').slice(0, max);

/**
 * Step 5 "Payouts": the bank account Stripe Connect pays out to. The numbers go to Stripe once (through the seller
 * service) and are never stored; the step is complete only once Stripe accepted the account.
 */
export function PayoutsStep({ status, ref }: StepProps) {
  const form = status.form;
  const p = form.payouts;
  const disabled = !form.editable;
  const stripeReady = status.stripe.status !== 'NOT_STARTED';
  const [replacing, setReplacing] = useState(!p.linked);
  const f = useStepForm(() => payoutValues(form));
  const v = f.values;

  useImperativeHandle(ref, () => ({
    save: (mode) => {
      if (!replacing) return Promise.resolve(true);
      // A draft keeps nothing half-typed: bank numbers are only ever sent whole, to Stripe.
      if (mode === 'draft' && Object.keys(validatePayouts(f.values)).length) return Promise.resolve(true);
      return f
        .run(
          mode,
          () => validatePayouts(f.values),
          () => sellerApi().saveOnboardingPayouts(toPayoutsInput(f.values))
        )
        .then((ok) => {
          if (ok) setReplacing(false);
          return ok;
        });
    }
  }));

  if (p.linked && !replacing) {
    return (
      <div className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center gap-3 rounded-md border border-border px-4 py-3">
          <Bank size={28} className="flex-none" aria-hidden />
          <div className="min-w-0 flex-1">
            <strong className="text-sm">
              {p.bankName ?? 'Bank account'} ••{p.accountLast4}
            </strong>
            <div className="text-xs text-muted">
              {p.accountType === 'savings' ? 'Savings' : 'Checking'} · {p.accountHolderName} · routing •••••{p.routingLast4}
            </div>
          </div>
          <span className="inline-flex items-center gap-1 text-sm font-semibold text-success">
            <CheckCircle size={18} weight="fill" aria-hidden /> Linked to Stripe
          </span>
          {!disabled ? (
            <Button size="sm" variant="link" onClick={() => setReplacing(true)}>
              Use a different account
            </Button>
          ) : null}
        </div>
        <p className="m-0 text-xs text-muted">Payouts go here weekly, after delivery. The account must belong to the business or owner.</p>
      </div>
    );
  }

  const set = (k: 'accountHolderName' | 'routingNumber' | 'accountNumber' | 'accountNumberConfirmation', max = 0) => ({
    id: fieldId(k),
    value: v[k],
    onChange: (e: { target: { value: string } }) => f.patch({ [k]: max ? digits(e.target.value, max) : e.target.value }, [k]),
    error: f.errors[k],
    disabled: disabled || !stripeReady
  });
  return (
    <div className="flex flex-col gap-3">
      {!stripeReady ? (
        <Notice tone="info" title="Verify with Stripe first.">
          Payouts go to the Stripe account that’s created when you <Link href={onboardingHref('stripe-verification')}>start Stripe verification</Link>.
        </Notice>
      ) : null}
      <StepErrors errors={f.errors} submitCount={f.submitCount} />
      <FieldGrid>
        <Cell span={6}>
          <TextField label="Account holder name" autoComplete="name" {...set('accountHolderName')} />
        </Cell>
        <Cell span={3}>
          <TextField label="Routing number" inputMode="numeric" placeholder="9 digits" autoComplete="off" {...set('routingNumber', 9)} />
        </Cell>
        <Cell span={3}>
          <Select
            id={fieldId('accountType')}
            label="Account type"
            placeholder="Choose…"
            options={[
              { value: 'checking', label: 'Checking' },
              { value: 'savings', label: 'Savings' }
            ]}
            value={v.accountType}
            onChange={(e) =>
              f.patch(
                {
                  accountType: e.target.value === 'savings' ? 'savings' : e.target.value === 'checking' ? 'checking' : ''
                },
                ['accountType']
              )
            }
            error={f.errors.accountType}
            disabled={disabled || !stripeReady}
          />
        </Cell>
        <Cell span={6}>
          <TextField type="password" label="Account number" inputMode="numeric" autoComplete="off" {...set('accountNumber', 17)} />
        </Cell>
        <Cell span={6}>
          <TextField type="password" label="Confirm account number" inputMode="numeric" autoComplete="off" {...set('accountNumberConfirmation', 17)} />
        </Cell>
      </FieldGrid>
      <p className="m-0 inline-flex items-center gap-1 text-xs text-muted">
        <LockSimple size={12} aria-hidden /> Sent once to Stripe as your payout account. AfroTransact keeps only the last 4 digits.
      </p>
      {p.linked ? (
        <div>
          <Button size="sm" variant="link" onClick={() => setReplacing(false)}>
            Keep ••{p.accountLast4}
          </Button>
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
